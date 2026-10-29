package dev.multix.tvhome.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import dev.multix.tvhome.core.Bg
import dev.multix.tvhome.core.Colors
import dev.multix.tvhome.core.Images
import dev.multix.tvhome.core.Prefs
import java.text.Collator
import kotlin.math.max

class AppEntry(
    val pkg: String,
    val cls: String,
    /** The label the app itself declares. */
    val rawLabel: String,
    val tv: Boolean,
    val updated: Long,
) {
    /** What the launcher shows: a friendlier name for some vendor apps, in the UI language. */
    val label: String get() = Friendly.name(pkg) ?: rawLabel

    /** Pre-rendered rounded card at the focused size. Written on the background thread. */
    @Volatile var card: Bitmap? = null
    @Volatile var glow: Int = Colors.DEFAULT_ACCENT
    /** Shaped icon for the icon dock styles, rendered for one shape and size at a time. */
    @Volatile var icon: Bitmap? = null
    @Volatile var iconKey: String = ""
    /** When the app was last opened, from usage stats (0 when unknown). */
    @Volatile var lastUsed: Long = 0

    val component get() = ComponentName(pkg, cls)
}

/**
 * Every launchable app: the leanback launcher category first, then the normal
 * launcher category for phone apps that were sideloaded. Card bitmaps are
 * rendered once, in the background, at the exact size the UI draws them.
 */
object Apps {
    @Volatile
    var all: List<AppEntry> = emptyList()
        private set

    private val cache = HashMap<String, AppEntry>()

    var cardW = 0
    var cardH = 0
    var radius = 0f

    private val preferredDock = listOf(
        "com.google.android.youtube.tv",
        "com.spotify.tv.android",
        "com.google.android.youtube.tvmusic",
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "com.google.android.videos",
        "com.android.vending",
    )

    /** Rescans installed apps. Returns true when the list changed. Background thread only. */
    fun scan(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val own = ctx.packageName
        val found = LinkedHashMap<String, Pair<android.content.pm.ResolveInfo, Boolean>>()
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(leanback, 0)) found.putIfAbsent(ri.activityInfo.packageName, ri to true)
        for (ri in pm.queryIntentActivities(launcher, 0)) found.putIfAbsent(ri.activityInfo.packageName, ri to false)
        found.remove(own)

        val next = ArrayList<AppEntry>(found.size)
        for ((pkg, pair) in found) {
            val (ri, tv) = pair
            val updated = try { pm.getPackageInfo(pkg, 0).lastUpdateTime } catch (e: Exception) { 0L }
            val old = cache[pkg]
            if (old != null && old.cls == ri.activityInfo.name && old.updated == updated) {
                next.add(old)
                continue
            }
            val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
            next.add(AppEntry(pkg, ri.activityInfo.name, label, tv, updated))
        }
        val collator = Collator.getInstance()
        next.sortWith { x, y -> collator.compare(x.label, y.label) }

        val changed = next.size != all.size || next.indices.any { next[it] !== all[it] }
        cache.clear()
        for (e in next) cache[e.pkg] = e
        all = next
        return changed
    }

    fun find(pkg: String): AppEntry? = all.firstOrNull { it.pkg == pkg }

    /** Drops every rendered card so the next renderAll draws them again (tile art changed). */
    fun forgetCards() {
        for (e in all) {
            e.card = null
            e.icon = null
            e.iconKey = ""
        }
    }

    /** Renders the card bitmap and the glow colour for one app. Background thread only. */
    fun render(ctx: Context, e: AppEntry) {
        if (e.card != null || cardW == 0) return
        val pm = ctx.packageManager
        val info = try { pm.getActivityInfo(e.component, 0) } catch (x: PackageManager.NameNotFoundException) { null }
        val banner: Drawable? = try {
            info?.loadBanner(pm) ?: pm.getApplicationBanner(e.pkg)
        } catch (x: Exception) { null }

        val raw = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888)
        val c = Canvas(raw)
        if (banner != null && dev.multix.tvhome.core.Look.cardArt != "icon" && !garish(banner)) {
            drawCover(c, banner, cardW, cardH)
        } else {
            val icon = try { info?.loadIcon(pm) ?: pm.getApplicationIcon(e.pkg) } catch (x: Exception) { null }
            drawIconCard(c, icon, cardW, cardH)
        }
        val sw = Images.swatches(raw)
        e.glow = sw.accent
        e.card = Images.roundedCover(raw, cardW, cardH, radius)
        raw.recycle()
    }

    /**
     * Some vendor banners are a flat, loud colour that fights every wallpaper.
     * Those get the calmer icon card instead.
     */
    private fun garish(d: Drawable): Boolean {
        val probe = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        drawCover(Canvas(probe), d, 32, 18)
        val hsv = FloatArray(3)
        var loud = 0
        for (y in 0 until 18) for (x in 0 until 32) {
            android.graphics.Color.colorToHSV(probe.getPixel(x, y), hsv)
            if (hsv[1] > 0.6f && hsv[2] > 0.55f) loud++
        }
        probe.recycle()
        return loud > 32 * 18 * 0.55f
    }

    private fun drawCover(c: Canvas, d: Drawable, w: Int, h: Int) {
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, w, h)
        } else {
            val s = max(w.toFloat() / iw, h.toFloat() / ih)
            val dw = (iw * s).toInt()
            val dh = (ih * s).toInt()
            val l = (w - dw) / 2
            val t = (h - dh) / 2
            d.setBounds(l, t, l + dw, t + dh)
        }
        d.draw(c)
    }

    /**
     * Apps without a usable banner: the icon centred on a white tile, so it
     * sits in the same family as the white banners most TV apps ship.
     */
    private fun drawIconCard(c: Canvas, icon: Drawable?, w: Int, h: Int) {
        val size = (h * 0.58f).toInt()
        val p = Paint(Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFFF7F8FA.toInt(), 0xFFE9ECF0.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        if (icon != null) {
            val l = (w - size) / 2
            val t = (h - size) / 2
            icon.setBounds(l, t, l + size, t + size)
            icon.draw(c)
        }
    }

    /**
     * Renders the app icon inside [shape] at [size] px. Adaptive icons fill the
     * shape edge to edge; old-style icons sit on a light plate. Apps with no
     * usable icon fall back to the middle of their banner.
     */
    fun renderIcon(ctx: Context, e: AppEntry, viewSize: Int, shape: String, size: Int = viewSize) {
        val key = "$shape:$viewSize"
        if (e.iconKey == key && e.icon != null) return
        val pm = ctx.packageManager
        val d: Drawable? = try { pm.getActivityIcon(e.component) } catch (x: Exception) { null }
        val content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(content)
        if (d is android.graphics.drawable.AdaptiveIconDrawable) {
            // The visible part of an adaptive icon is the middle 72 of 108 dp.
            val pad = (size * 0.25f).toInt()
            d.background?.let { it.setBounds(-pad, -pad, size + pad, size + pad); it.draw(c) }
            d.foreground?.let { it.setBounds(-pad, -pad, size + pad, size + pad); it.draw(c) }
        } else if (d != null && !isGenericIcon(pm, d)) {
            c.drawColor(0xFFF3F4F6.toInt())
            val inset = (size * 0.16f).toInt()
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(c)
        } else {
            val banner = e.card
            if (banner != null) {
                val src = android.graphics.Rect((banner.width - banner.height) / 2, 0, (banner.width + banner.height) / 2, banner.height)
                c.drawBitmap(banner, src, android.graphics.Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                c.drawColor(0xFF2A2D35.toInt())
            }
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.shader = android.graphics.BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(out).drawPath(Shapes.path(shape, size.toFloat()), p)
        content.recycle()
        e.icon = out
        e.iconKey = key
    }

    /** The stock Android robot icon looks worse than the banner, so treat it as missing. */
    private fun isGenericIcon(pm: PackageManager, d: Drawable): Boolean {
        val mine = d.constantState ?: return false
        return mine == pm.defaultActivityIcon.constantState
    }

    /** Fills [Apps.all] lastUsed values. Needs the usage access app-op; silently skipped without it. */
    fun loadUsage(ctx: Context) {
        if (!hasUsageAccess(ctx)) return
        val usm = ctx.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return
        val end = System.currentTimeMillis()
        val stats = try {
            usm.queryAndAggregateUsageStats(end - 60L * 24 * 3600 * 1000, end)
        } catch (x: Exception) { return }
        for (e in all) e.lastUsed = stats[e.pkg]?.lastTimeUsed ?: 0L
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        return if (mode == android.app.AppOpsManager.MODE_DEFAULT)
            ctx.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        else mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /** Applies the "Sort apps" choice to a list of entries. */
    fun sorted(list: List<AppEntry>, forDock: Boolean): List<AppEntry> = when (dev.multix.tvhome.core.Look.sortApps) {
        "recent" -> list.sortedWith(compareByDescending<AppEntry> { it.lastUsed }.thenBy { it.label.lowercase() })
        "az" -> list.sortedBy { it.label.lowercase() }
        else -> if (forDock) list else list.sortedBy { it.label.lowercase() }
    }

    // Dock and hidden apps. Stored as package names.

    fun dock(): List<String> {
        val installed = all.mapTo(HashSet()) { it.pkg }
        if (!Prefs.hasDock) return defaultDock()
        return Prefs.dock.filter { it in installed }
    }

    fun defaultDock(): List<String> {
        val installed = all.mapTo(HashSet()) { it.pkg }
        val hidden = Prefs.hidden
        val out = preferredDock.filter { it in installed && it !in hidden }.toMutableList()
        for (e in all) {
            if (out.size >= 6) break
            if (e.tv && e.pkg !in out && e.pkg !in hidden) out.add(e.pkg)
        }
        return out
    }

    fun setDock(list: List<String>) { Prefs.dock = list }

    fun inDock(pkg: String) = pkg in dock()

    fun addToDock(pkg: String) {
        val d = dock().toMutableList()
        if (pkg !in d) d.add(pkg)
        setDock(d)
    }

    fun removeFromDock(pkg: String) { setDock(dock().filter { it != pkg }) }

    fun moveInDock(pkg: String, delta: Int): Boolean {
        val d = dock().toMutableList()
        val i = d.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j < 0 || j >= d.size) return false
        d.removeAt(i)
        d.add(j, pkg)
        setDock(d)
        return true
    }

    fun isHidden(pkg: String) = pkg in Prefs.hidden

    fun setHidden(pkg: String, hide: Boolean) {
        val h = Prefs.hidden.toMutableSet()
        if (hide) h.add(pkg) else h.remove(pkg)
        Prefs.hidden = h
        if (hide) removeFromDock(pkg)
    }

    fun launchIntent(ctx: Context, e: AppEntry): Intent? {
        val pm = ctx.packageManager
        val i = (if (e.tv) pm.getLeanbackLaunchIntentForPackage(e.pkg) else null)
            ?: pm.getLaunchIntentForPackage(e.pkg)
            ?: Intent(Intent.ACTION_MAIN).setComponent(e.component)
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    /** Warm all card bitmaps; dock apps first so the home row fills in quickly. */
    fun renderAll(ctx: Context, first: List<String>, each: (AppEntry) -> Unit) {
        val order = ArrayList<AppEntry>()
        for (p in first) find(p)?.let { order.add(it) }
        for (e in all) if (e !in order) order.add(e)
        for (e in order) {
            if (e.card == null) {
                try { render(ctx, e) } catch (t: Throwable) { Bg.warn("card for ${e.pkg} failed", t) }
                each(e)
            }
        }
    }
}

/**
 * Vendor apps on these boxes ship labels like "FileBrowser" or
 * "MiracastReceiver". Keyed by package, in both UI languages.
 */
object Friendly {
    private val names = mapOf(
        "com.oranth.filebrowser" to ("File Browser" to "متصفح الملفات"),
        "com.softwinner.TvdFileManager" to ("Files" to "الملفات"),
        "com.softwinner.miracastReceiver" to ("Screen Mirroring" to "عرض الشاشة"),
        "com.android.gallery3d" to ("Gallery" to "المعرض"),
        "com.android.tv.settings" to ("Settings" to "الإعدادات"),
    )

    fun name(pkg: String): String? {
        val n = names[pkg] ?: return null
        return if (dev.multix.tvhome.core.Lang.isArabic) n.second else n.first
    }
}
