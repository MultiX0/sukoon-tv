package dev.multix.tvhome.media

import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.session.PlaybackState
import android.os.SystemClock
import dev.multix.tvhome.core.Http
import dev.multix.tvhome.core.Images
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One reading of "what is playing", whichever source it came from. */
class Snap(
    val pkg: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val state: Int,
    val position: Long,
    /** elapsedRealtime of [position]. */
    val updated: Long,
    val speed: Float,
    val art: Bitmap? = null,
    val artUri: String? = null,
)

/**
 * Fallback for low-RAM Android TV builds, which refuse notification listener
 * access and therefore the media session API. With the DUMP permission
 * granted over adb, `dumpsys media_session` lists every session with its
 * metadata description and playback state. Only that one service is ever
 * dumped; the text is parsed in memory and dropped.
 */
object DumpSource {
    private val stateRe = Regex("""state=(\d+), position=(-?\d+), buffered position=-?\d+, speed=([-\d.E]+), updated=(\d+)""")

    fun available(ctx: Context): Boolean =
        ctx.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED

    /** Background thread only. */
    fun read(): Snap? {
        val text = try {
            val p = ProcessBuilder("/system/bin/dumpsys", "media_session").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { r ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                while (sb.length < 128 * 1024) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            p.waitFor(2, TimeUnit.SECONDS)
            p.destroy()
            out
        } catch (e: Exception) {
            return null
        }
        return parse(text)
    }

    private class Rec(val pkg: String) {
        var active = false
        var state = 0
        var position = 0L
        var updated = 0L
        var speed = 1f
        var title = ""
        var artist = ""
        var album = ""
    }

    fun parse(text: String): Snap? {
        val recs = ArrayList<Rec>()
        var inStack = false
        var cur: Rec? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Sessions Stack")) { inStack = true; continue }
            if (!inStack) continue
            if (line.startsWith("Session2Tokens") || line.startsWith("Audio playback")) break
            when {
                line.startsWith("package=") -> cur = Rec(line.removePrefix("package=")).also { recs.add(it) }
                cur == null -> {}
                line.startsWith("active=") -> cur.active = line.endsWith("true")
                line.startsWith("state=PlaybackState") -> stateRe.find(line)?.let { m ->
                    cur.state = m.groupValues[1].toInt()
                    cur.position = m.groupValues[2].toLong()
                    cur.speed = m.groupValues[3].toFloatOrNull() ?: 1f
                    cur.updated = m.groupValues[4].toLong()
                }
                line.startsWith("metadata:") -> {
                    val d = line.substringAfter("description=", "")
                    if (d.isNotEmpty()) {
                        // MediaDescription prints "title, subtitle, description"; titles may
                        // contain commas, so split from the right.
                        val parts = d.split(", ")
                        if (parts.size >= 3) {
                            cur.title = clean(parts.subList(0, parts.size - 2).joinToString(", "))
                            cur.artist = clean(parts[parts.size - 2])
                            cur.album = clean(parts[parts.size - 1])
                        } else {
                            cur.title = clean(parts[0])
                            cur.artist = clean(parts.getOrNull(1) ?: "")
                        }
                    }
                }
            }
        }
        val pick = recs.firstOrNull { it.state == PlaybackState.STATE_PLAYING && it.title.isNotEmpty() }
            ?: recs.firstOrNull { it.active && it.title.isNotEmpty() && it.state != PlaybackState.STATE_STOPPED && it.state != 0 }
            ?: return null
        return Snap(pick.pkg, pick.title, pick.artist, pick.album, 0, pick.state, pick.position,
            if (pick.updated > 0) pick.updated else SystemClock.elapsedRealtime(), pick.speed)
    }

    private fun clean(s: String) = if (s == "null") "" else s.trim()
}

/**
 * Spotify's own "device broadcast status" intents. They need no permission
 * and carry the track length and position. Registered only while home or the
 * screensaver is visible.
 */
object SpotifySource {
    private const val META = "com.spotify.music.metadatachanged"
    private const val STATE = "com.spotify.music.playbackstatechanged"

    @Volatile var title = ""
    @Volatile var artist = ""
    @Volatile var album = ""
    @Volatile var lengthMs = 0L
    @Volatile var playing = false
    @Volatile var position = 0L
    @Volatile var at = 0L

    private var registered: Context? = null
    var onChange: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                META -> {
                    title = i.getStringExtra("track") ?: title
                    artist = i.getStringExtra("artist") ?: artist
                    album = i.getStringExtra("album") ?: album
                    val len = i.getIntExtra("length", 0).toLong()
                    // Documented as seconds; some builds send milliseconds.
                    lengthMs = if (len in 1..9_999) len * 1000 else len
                }
                STATE -> {
                    playing = i.getBooleanExtra("playing", playing)
                    position = i.getIntExtra("playbackPosition", 0).toLong()
                    at = SystemClock.elapsedRealtime()
                }
            }
            onChange?.invoke()
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // the flag only exists from Android 13
    fun register(ctx: Context) {
        if (registered != null) return
        val f = IntentFilter().apply { addAction(META); addAction(STATE) }
        // Spotify's broadcasts come from another app; Android 14 requires saying so when registering.
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(receiver, f)
        registered = ctx
    }

    fun unregister() {
        registered?.let { try { it.unregisterReceiver(receiver) } catch (e: Exception) { } }
        registered = null
    }

    fun snap(): Snap? {
        if (title.isEmpty() || at == 0L) return null
        return Snap("com.spotify.tv.android", title, artist, album, lengthMs,
            if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            position, at, 1f)
    }

    fun matches(t: String, a: String) = title.isNotEmpty() && title.equals(t, true) && (a.isEmpty() || artist.equals(a, true))
}

/**
 * Fills in what the dump does not carry (length, album, cover) from the iTunes
 * Search API, which needs no key. Only the artist and title are sent. Results
 * are cached in files/art: one 384 px JPEG (about 30 KB) and a small JSON per
 * track, trimmed to the newest 150 tracks.
 */
object Enrich {
    class Info(val durationMs: Long, val album: String, val art: Bitmap?)

    private const val MAX_FILES = 300

    fun lookup(ctx: Context, title: String, artist: String): Info? {
        val dir = File(ctx.filesDir, "art").apply { mkdirs() }
        val key = sha1("${artist.lowercase()}|${title.lowercase()}")
        val meta = File(dir, "$key.json")
        val jpg = File(dir, "$key.jpg")
        if (meta.isFile) {
            return try {
                val o = JSONObject(meta.readText())
                val art = if (jpg.isFile) BitmapFactory.decodeFile(jpg.path) else null
                meta.setLastModified(System.currentTimeMillis())
                Info(o.optLong("duration"), o.optString("album"), art)
            } catch (e: Exception) { null }
        }
        val q = Clean.of(title, artist)
        val r = Http.get("https://itunes.apple.com/search?entity=song&limit=8&term=" + Http.enc("${q.artist} ${q.title}".trim()))
        val body = r.body ?: return null
        val arr = try { JSONObject(body).optJSONArray("results") } catch (e: Exception) { null } ?: return null
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            var score = 0
            if (o.optString("trackName").equals(q.title, true)) score += 3
            else if (o.optString("trackName").contains(q.title, true)) score += 1
            if (q.artist.isNotEmpty() && o.optString("artistName").contains(q.artist, true)) score += 2
            if (score > bestScore) { bestScore = score; best = o }
        }
        val b = best ?: return null
        if (bestScore < 2) return null
        val duration = b.optLong("trackTimeMillis")
        val album = b.optString("collectionName")
        var art: Bitmap? = null
        val url = b.optString("artworkUrl100").replace("100x100bb", "600x600bb")
        if (url.startsWith("https://")) {
            art = try {
                val raw = Images.decodeCover({ URL(url).openStream() }, 384, 384)
                raw?.let { Images.square(it, 384).also { sq -> if (sq !== it) it.recycle() } }
            } catch (e: Exception) { null }
            art?.let { a -> try { jpg.outputStream().use { a.compress(Bitmap.CompressFormat.JPEG, 88, it) } } catch (e: Exception) { } }
        }
        try {
            meta.writeText(JSONObject().put("duration", duration).put("album", album).toString())
        } catch (e: Exception) { }
        trim(dir)
        return Info(duration, album, art)
    }

    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
