package dev.multix.tvhome.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import dev.multix.tvhome.BuildConfig
import dev.multix.tvhome.core.Bg
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Manual updates from the GitHub releases of [REPO]. Nothing runs on its own: the user checks from Settings >
 * About, sees the version and notes, and chooses to download. The APK must be this app, newer, and signed with
 * the same key before Android's installer is asked to put it in place (and Android checks the key again).
 *
 * It has its own thread, so a 20 MB download never holds up wallpapers, lyrics or weather.
 */
object Updater {
    /** Where releases are published. */
    const val REPO = "MultiX0/sukoon-tv"
    private const val API = "https://api.github.com"
    private const val PREFS = "update"

    enum class Reason { OFFLINE, NO_RELEASES, RATE_LIMIT, BAD_RESPONSE, NO_APK, NO_SPACE, DOWNLOAD, CORRUPT, WRONG_APP, NOT_NEWER, SIGNATURE, BLOCKED, INSTALL, CANCELLED }

    sealed class State {
        object Idle : State()
        object Checking : State()
        class UpToDate(val latest: Version) : State()
        class Available(val release: Release) : State()
        class Downloading(val release: Release, val done: Long, val total: Long) : State()
        class Verifying(val release: Release) : State()
        class Installing(val release: Release) : State()
        /** [release] is kept when the failure came after a release was found, so the user can try again. */
        class Failed(val reason: Reason, val release: Release? = null, val minutes: Int = 0) : State()
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "sukoon-update")
    }
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    @Volatile
    var state: State = State.Idle
        private set

    @Volatile
    private var cancelled = false

    val busy: Boolean
        get() = state.let { it is State.Checking || it is State.Downloading || it is State.Verifying || it is State.Installing }

    val current: Version get() = Version.parse(BuildConfig.VERSION_NAME) ?: Version(listOf(0), null)

    fun addListener(l: (State) -> Unit) { listeners.add(l) }
    fun removeListener(l: (State) -> Unit) { listeners.remove(l) }

    private fun post(s: State) {
        state = s
        Bg.main.post { for (l in listeners) l(s) }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Test hook: a mock of the GitHub API on the computer, reached through `adb reverse`. Only plain http on the
     * box itself is accepted, so it can never point updates at another host.
     * `adb shell am start -n dev.multix.tvhome/.HomeActivity --es update_api http://127.0.0.1:8099` (empty clears it)
     */
    fun setTestApi(ctx: Context, url: String) {
        val ok = url.isEmpty() || Regex("""^http://(127\.0\.0\.1|localhost)(:\d{2,5})?$""").matches(url)
        if (ok) prefs(ctx).edit().putString("api", url).apply()
    }

    private fun apiBase(ctx: Context): String = prefs(ctx).getString("api", null)?.takeIf { it.isNotEmpty() } ?: API

    /** When the last check finished, or 0. */
    fun lastChecked(ctx: Context): Long = prefs(ctx).getLong("checked", 0L)

    fun check(ctx: Context) {
        if (busy) return
        val app = ctx.applicationContext
        cancelled = false
        post(State.Checking)
        worker.execute { post(fetchLatest(app)) }
    }

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "${BuildConfig.BRAND}/${BuildConfig.VERSION_NAME} (Android TV launcher)")
            setRequestProperty("Accept", accept)
        }

    private fun fetchLatest(ctx: Context): State {
        var conn: HttpURLConnection? = null
        return try {
            conn = open("${apiBase(ctx)}/repos/$REPO/releases/latest", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = conn.responseCode
            when {
                code == 404 -> State.Failed(Reason.NO_RELEASES)
                code == 429 || (code == 403 && conn.getHeaderField("X-RateLimit-Remaining") == "0") -> {
                    val reset = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                    val wait = if (reset != null) ((reset * 1000 - System.currentTimeMillis()) / 60_000 + 1).toInt() else 60
                    State.Failed(Reason.RATE_LIMIT, minutes = wait.coerceIn(1, 60))
                }
                code !in 200..299 -> State.Failed(Reason.BAD_RESPONSE)
                else -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val r = Releases.parse(body, BuildConfig.BRAND)
                    prefs(ctx).edit().putLong("checked", System.currentTimeMillis()).apply()
                    if (r.version > current) State.Available(r) else State.UpToDate(r.version)
                }
            }
        } catch (e: ReleaseError) {
            State.Failed(e.reason)
        } catch (e: IOException) {
            State.Failed(Reason.OFFLINE)
        } finally {
            conn?.disconnect()
        }
    }

    /** Downloads, verifies and installs the release found by the last check. */
    fun download(ctx: Context) {
        if (busy) return
        val r = when (val s = state) {
            is State.Available -> s.release
            is State.Failed -> s.release
            else -> null
        } ?: return
        val app = ctx.applicationContext
        cancelled = false
        post(State.Downloading(r, 0, r.apkSize))
        worker.execute { post(fetchAndInstall(app, r)) }
    }

    fun cancel() {
        if (state is State.Downloading) cancelled = true
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates")

    /** Removes downloaded APKs; called on start, so an installed update leaves nothing behind. */
    fun cleanUp(ctx: Context) {
        Bg.run { dir(ctx).listFiles()?.forEach { it.delete() } }
    }

    private fun fetchAndInstall(ctx: Context, r: Release): State {
        val dir = dir(ctx)
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        if (r.apkSize > 0 && dir.usableSpace < r.apkSize * 2) return State.Failed(Reason.NO_SPACE, r)
        val part = File(dir, "${BuildConfig.BRAND.lowercase()}-${r.version}.apk.part")
        val apk = File(dir, part.name.removeSuffix(".part"))
        var conn: HttpURLConnection? = null
        try {
            conn = open(r.apkUrl, "application/octet-stream")
            val code = conn.responseCode
            if (code !in 200..299) return State.Failed(Reason.DOWNLOAD, r)
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            val sha = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) {
                            part.delete()
                            return State.Failed(Reason.CANCELLED, r)
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        val now = SystemClock.uptimeMillis()
                        if (now - shown > 200) {
                            shown = now
                            post(State.Downloading(r, done, total))
                        }
                    }
                    out.fd.sync()
                }
            }
            post(State.Downloading(r, done, total))
            post(State.Verifying(r))
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if ((r.apkSize > 0 && done != r.apkSize) || (r.sha256 != null && hex != r.sha256)) {
                part.delete()
                return State.Failed(Reason.CORRUPT, r)
            }
            if (!part.renameTo(apk)) return State.Failed(Reason.DOWNLOAD, r)
            verify(ctx, apk)?.let {
                apk.delete()
                return State.Failed(it, r)
            }
            return install(ctx, apk, r)
        } catch (e: IOException) {
            part.delete()
            return State.Failed(if (cancelled) Reason.CANCELLED else Reason.DOWNLOAD, r)
        } finally {
            conn?.disconnect()
        }
    }

    /** Null when [apk] is a newer build of this app signed with the same key, else why not. */
    @Suppress("DEPRECATION")
    private fun verify(ctx: Context, apk: File): Reason? {
        val pm = ctx.packageManager
        // Android 9-12 leave signingInfo empty for an APK file and only fill the old signatures field, so both
        // are asked for. (Measured on the W01, Android 10: signingInfo was null for the downloaded file.)
        val flags = PackageManager.GET_SIGNATURES or (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return Reason.CORRUPT
        if (info.packageName != ctx.packageName) return Reason.WRONG_APP
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        if (code(info) <= code(mine)) return Reason.NOT_NEWER
        val theirs = certs(info)
        val ours = certs(mine)
        // No certificates at all would mean an unsigned file, which Android refuses anyway; a different set is
        // another developer's build, refused here before Android's install screen ever shows.
        if (theirs.isEmpty() || theirs != ours) return Reason.SIGNATURE
        return null
    }

    @Suppress("DEPRECATION")
    private fun code(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certs(p: PackageInfo): Set<String> {
        val modern = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else null
        val sigs = modern?.takeIf { it.isNotEmpty() } ?: p.signatures
        val md = MessageDigest.getInstance("SHA-256")
        return sigs?.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }?.toSet() ?: emptySet()
    }

    private fun install(ctx: Context, apk: File, r: Release): State {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) return State.Failed(Reason.BLOCKED, r)
        return try {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                setSize(apk.length())
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                FileInputStream(apk).use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                // The installer fills in the status extras, so the intent has to be mutable on Android 12+.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val status = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags)
                prefs(ctx).edit().putString("installing", r.version.toString()).apply()
                session.commit(status.intentSender)
            }
            State.Installing(r)
        } catch (e: Exception) {
            Bg.warn("update install failed", e)
            State.Failed(Reason.INSTALL, r)
        }
    }

    /** From [InstallReceiver]: the user declined, or Android refused the package. */
    internal fun installFinished(ctx: Context, status: Int) {
        val r = (state as? State.Installing)?.release
        prefs(ctx).edit().remove("installing").apply()
        post(
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> State.Idle
                PackageInstaller.STATUS_FAILURE_ABORTED -> State.Failed(Reason.CANCELLED, r)
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> State.Failed(Reason.SIGNATURE, r)
                PackageInstaller.STATUS_FAILURE_STORAGE -> State.Failed(Reason.NO_SPACE, r)
                else -> State.Failed(Reason.INSTALL, r)
            }
        )
    }

    /** True once after an update this app started installing; the mark is cleared. */
    fun consumeInstallMark(ctx: Context): Boolean {
        val p = prefs(ctx)
        val marked = p.getString("installing", null) != null
        if (marked) p.edit().remove("installing").commit()
        return marked
    }

    /**
     * The version this process was updated to, once, right after an update replaced the app; otherwise null.
     * Android restarts the launcher after replacing it, so this is how the new version says hello.
     */
    fun justUpdated(ctx: Context): String? {
        val p = prefs(ctx)
        val last = p.getString("ran", null)
        p.edit().putString("ran", BuildConfig.VERSION_NAME).apply()
        val now = Version.parse(BuildConfig.VERSION_NAME) ?: return null
        val before = Version.parse(last) ?: return null
        return if (now > before) BuildConfig.VERSION_NAME else null
    }
}
