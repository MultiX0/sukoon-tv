package dev.multix.tvhome.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.multix.tvhome.HomeActivity
import dev.multix.tvhome.R
import dev.multix.tvhome.apps.Apps
import dev.multix.tvhome.core.Colors
import dev.multix.tvhome.core.Fonts
import dev.multix.tvhome.core.Lang
import dev.multix.tvhome.core.Look
import dev.multix.tvhome.core.Motion
import dev.multix.tvhome.core.Prefs
import dev.multix.tvhome.core.Presets
import dev.multix.tvhome.core.SText
import dev.multix.tvhome.core.W
import dev.multix.tvhome.core.dp
import dev.multix.tvhome.core.frame
import dev.multix.tvhome.core.margins
import dev.multix.tvhome.core.rounded
import dev.multix.tvhome.wall.Walls

/** Which part of the home screen a change touches, so only that part redraws. */
enum class Area { CLOCK, DATE, DOCK, NOW_PLAYING, WALLPAPER, TEXT, ALL }

/**
 * Home screen editor in the spirit of the iOS lock screen editor. The home
 * screen shrinks into a framed preview; up and down pick a part, OK steps
 * through that part's options, left and right change the value. Every change
 * is saved and shown at once; Back leaves.
 */
class CustomizeOverlay(host: HomeActivity) : OverlayView(host) {

    private class Opt(
        val name: Int,
        val values: () -> List<String>,
        val label: (String) -> String,
        val get: () -> String,
        val set: (String) -> Unit,
        val area: Area,
    )

    private class Part(val name: Int, val icon: Int, val area: Area, val opts: List<Opt>)

    private val frameView = FocusFrame(host)
    private val top = LinearLayout(host)
    private val title = SText(host, W.SEMIBOLD, 15f, Colors.WHITE)
    private val chips = LinearLayout(host)
    private val pill = LinearLayout(host)
    private val pillIcon = ImageView(host)
    private val pillPart = SText(host, W.MEDIUM, 12f, Colors.TEXT3)
    private val pillValue = SText(host, W.SEMIBOLD, 17f, Colors.WHITE)
    private val dots = LinearLayout(host)

    private val parts: List<Part>
    private var part = 0
    private var opt = 0

    init {
        clipChildren = false
        // The shrunken home screen casts a shadow (it has elevation); sit above it.
        translationZ = 40f.dp
        addView(frameView, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER
        title.text = host.getString(R.string.cz_title)
        top.addView(title)
        // One chip per key, each its own views, so Arabic and Latin never mix in one line.
        chips.orientation = LinearLayout.HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        chip("\u2191\u2193", R.string.cz_k_part)
        chip("\u2190\u2192", R.string.cz_k_change)
        chip("OK", R.string.cz_k_next)
        chip(host.getString(R.string.cz_k_back), R.string.cz_k_done)
        top.addView(chips, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = 20.dp })
        addView(top, frame(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).margins(top = 18.dp))

        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = rounded(0xF01A1C21.toInt(), 28f.dp)
        pill.elevation = 16f.dp
        pill.setPadding(20.dp, 0, 26.dp, 0)
        pillIcon.setColorFilter(Colors.WHITE)
        pill.addView(pillIcon, LinearLayout.LayoutParams(22.dp, 22.dp))
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        col.addView(pillPart)
        col.addView(pillValue)
        pill.addView(col, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = 14.dp })
        pill.addView(dots, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = 22.dp })
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER_VERTICAL
        addView(pill, frame(LayoutParams.WRAP_CONTENT, 58.dp, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).margins(bottom = 12.dp))

        parts = buildParts()
    }

    private fun s(id: Int) = host.getString(id)

    private fun chip(key: String, label: Int) {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cap = SText(host, W.MEDIUM, 11f, Colors.WHITE)
        cap.text = key
        if (key.all { it.code < 0x0600 }) {
            cap.typeface = Fonts.of(host, Fonts.INTER, W.MEDIUM)
            cap.fixedFace = true
        }
        cap.background = rounded(0x2EFFFFFF, 6f.dp)
        cap.setPadding(7.dp, 2.dp, 7.dp, 3.dp)
        row.addView(cap)
        val text = SText(host, W.REGULAR, 12f, Colors.TEXT2)
        text.text = s(label)
        row.addView(text, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = 7.dp })
        chips.addView(row, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            if (chips.childCount > 0) marginStart = 16.dp
        })
    }
    private fun onOff(v: String) = s(if (v == "1") R.string.on else R.string.off)
    private fun bool(get: () -> Boolean, set: (Boolean) -> Unit, name: Int, area: Area) =
        Opt(name, { listOf("1", "0") }, ::onOff, { if (get()) "1" else "0" }, { set(it == "1") }, area)

    private fun buildParts(): List<Part> {
        val clock = Part(R.string.cz_clock, R.drawable.ic_clock, Area.CLOCK, listOf(
            Opt(R.string.p_font, { Fonts.clock.map { it.id } }, { id -> Fonts.clock.firstOrNull { it.id == id }?.name ?: "" },
                { Fonts.clockDef().id }, { Look.clockFont = it }, Area.CLOCK),
            Opt(R.string.p_style, { listOf("line", "stacked", "outline") },
                { s(when (it) { "stacked" -> R.string.v_stacked; "outline" -> R.string.v_outline; else -> R.string.v_line }) },
                { Look.clockStyle }, { Look.clockStyle = it }, Area.CLOCK),
            Opt(R.string.p_size, { listOf("0", "1", "2", "3") },
                { s(when (it) { "0" -> R.string.v_s; "1" -> R.string.v_m; "3" -> R.string.v_xl; else -> R.string.v_l }) },
                { Look.clockSize.toString() }, { Look.clockSize = it.toInt() }, Area.CLOCK),
            Opt(R.string.p_position, { listOf("tl", "center", "bl") },
                { s(when (it) { "center" -> R.string.v_pos_center; "bl" -> R.string.v_pos_bl; else -> R.string.v_pos_tl }) },
                { Look.clockPos }, { Look.clockPos = it }, Area.ALL),
            Opt(R.string.p_color, { Look.colors.map { it.first } }, { colorName(it) },
                { Look.clockColor }, { Look.clockColor = it }, Area.CLOCK),
            bool({ Prefs.clockSeconds }, { Prefs.clockSeconds = it }, R.string.p_seconds, Area.CLOCK),
            Opt(R.string.p_format, { listOf("12", "24") }, { s(if (it == "24") R.string.c_24h_short else R.string.c_12h_short) },
                { if (Prefs.clock24(host)) "24" else "12" }, { Prefs.setClock24(it == "24") }, Area.CLOCK),
            Opt(R.string.p_digits, { listOf("latin", "arabic") }, { s(if (it == "arabic") R.string.v_arabic_digits else R.string.v_latin_digits) },
                { if (Look.arabicDigits) "arabic" else "latin" }, { Look.arabicDigits = it == "arabic" }, Area.CLOCK),
        ))
        val date = Part(R.string.cz_date, R.drawable.ic_calendar, Area.DATE, listOf(
            bool({ Look.dateOn }, { Look.dateOn = it }, R.string.p_date, Area.CLOCK),
            Opt(R.string.p_calendar, { listOf("greg", "hijri") }, { s(if (it == "hijri") R.string.v_hijri else R.string.v_greg) },
                { if (Look.hijri) "hijri" else "greg" }, { Look.hijri = it == "hijri" }, Area.CLOCK),
            bool({ Look.greetOn }, { Look.greetOn = it }, R.string.p_greeting, Area.CLOCK),
        ))
        val dock = Part(R.string.cz_dock, R.drawable.ic_dock, Area.DOCK, listOf(
            Opt(R.string.p_style, { listOf("cards", "pill", "icons", "list") },
                { s(when (it) { "pill" -> R.string.v_pill; "icons" -> R.string.v_icons; "list" -> R.string.v_list; else -> R.string.v_cards }) },
                { Look.dockStyle }, { Look.dockStyle = it }, Area.ALL),
            Opt(R.string.p_shape, { listOf("squircle", "circle", "rounded") },
                { s(when (it) { "circle" -> R.string.v_circle; "rounded" -> R.string.v_rounded; else -> R.string.v_squircle }) },
                { Look.iconShape }, { Look.iconShape = it; CardSpec.applyShape(); host.redrawCards() }, Area.DOCK),
            bool({ Look.dockLabels }, { Look.dockLabels = it }, R.string.p_labels, Area.ALL),
            Opt(R.string.p_tile_art, { listOf("banner", "icon") }, { s(if (it == "icon") R.string.v_art_icon else R.string.v_art_banner) },
                { Look.cardArt }, { Look.cardArt = it; host.redrawCards() }, Area.DOCK),
            Opt(R.string.p_position, { listOf("bottom", "left") }, { s(if (it == "left") R.string.v_side else R.string.v_bottom) },
                { Look.dockPos }, { Look.dockPos = it }, Area.ALL),
            Opt(R.string.p_sort, { if (Apps.hasUsageAccess(host)) listOf("custom", "recent", "az") else listOf("custom", "az") },
                { s(when (it) { "recent" -> R.string.v_recent; "az" -> R.string.v_az; else -> R.string.v_custom }) },
                { Look.sortApps }, { Look.sortApps = it }, Area.DOCK),
        ))
        val np = Part(R.string.cz_np, R.drawable.ic_music, Area.NOW_PLAYING, listOf(
            Opt(R.string.p_style, { listOf("card", "pill", "takeover") },
                { s(when (it) { "pill" -> R.string.v_compact; "takeover" -> R.string.v_takeover; else -> R.string.v_card }) },
                { Look.npStyle }, { Look.npStyle = it }, Area.ALL),
            Opt(R.string.p_lyrics_size, { listOf("0", "1", "2") },
                { s(when (it) { "0" -> R.string.l_small; "2" -> R.string.l_large; else -> R.string.l_medium }) },
                { Prefs.lyricsSize.toString() }, { Prefs.lyricsSize = it.toInt() }, Area.NOW_PLAYING),
        ))
        val wall = Part(R.string.cz_wall, R.drawable.ic_image, Area.WALLPAPER, listOf(
            Opt(R.string.p_set, { Walls.sets.map { it.id } }, { id -> Walls.sets.firstOrNull { it.id == id }?.name ?: "" },
                { Walls.find(Prefs.wallSet)?.id ?: "" }, { host.commitWallpaper(it) }, Area.WALLPAPER),
            Opt(R.string.w_rotation, { listOf("0", "1", "5", "10", "30") },
                { val n = it.toInt(); if (n == 0) s(R.string.off) else host.resources.getQuantityString(R.plurals.minutes, n, n) },
                { Prefs.wallInterval.toString() }, { Prefs.wallInterval = it.toInt() }, Area.WALLPAPER),
            bool({ Prefs.wallMotion }, { Prefs.wallMotion = it }, R.string.p_motion, Area.WALLPAPER),
            Opt(R.string.p_dim, { (0..6).map { it.toString() } }, { "${it.toInt() * 10}%" },
                { Look.wallDim.toString() }, { Look.wallDim = it.toInt() }, Area.WALLPAPER),
            Opt(R.string.p_blur, { (0..5).map { it.toString() } }, { if (it == "0") s(R.string.off) else it },
                { Look.wallBlur.toString() }, { Look.wallBlur = it.toInt() }, Area.WALLPAPER),
        ))
        val text = Part(R.string.cz_text, R.drawable.ic_globe, Area.TEXT, listOf(
            Opt(R.string.p_language, { listOf("system", "en", "ar") },
                { s(when (it) { "en" -> R.string.lang_en; "ar" -> R.string.lang_ar; else -> R.string.v_system }) },
                { Prefs.lang }, { Prefs.lang = it }, Area.TEXT),
            Opt(R.string.p_ui_font, { (if (Lang.isArabic) Fonts.uiAr else Fonts.uiEn).map { it.id } },
                { id -> (Fonts.uiAr + Fonts.uiEn).firstOrNull { it.id == id }?.name ?: "" },
                { Fonts.uiFont().id }, { if (Lang.isArabic) Look.uiFontAr = it else Look.uiFontEn = it }, Area.TEXT),
        ))
        // "Your look" first, then the built-in looks, then the user's saved looks ("u:<id>"). The setup the user
        // had is kept before the first switch away from it, so scrolling through looks never loses it.
        val looks = Part(R.string.cz_presets, R.drawable.ic_customize, Area.ALL, listOf(
            Opt(R.string.p_look, { listOf(MINE) + Look.presets + Presets.list(host).map { "u:${it.id}" } }, { lookName(it) },
                { lastPreset }, { pickLook(it) }, Area.ALL),
        ))
        return listOf(clock, date, dock, np, wall, text, looks)
    }

    private fun pickLook(id: String) {
        if (id == lastPreset) return
        if (lastPreset == MINE) Presets.rememberPrevious(host)
        lastPreset = id
        when {
            id == MINE -> Presets.previous(host)?.let {
                Presets.restore(host, it)
                host.applySavedLook()
            }
            id.startsWith("u:") -> Presets.find(host, id.removePrefix("u:"))?.let {
                Presets.restore(host, it.data)
                host.applySavedLook()
            }
            else -> host.applyPreset(id)
        }
    }

    private fun lookName(id: String): String = when {
        id == MINE -> s(R.string.pr_yours)
        id.startsWith("u:") -> Presets.find(host, id.removePrefix("u:"))?.name ?: s(R.string.look_pick)
        else -> builtInName(id)
    }

    private fun builtInName(id: String) = s(when (id) {
        "calm" -> R.string.look_calm
        "cinema" -> R.string.look_cinema
        "arabic" -> R.string.look_arabic
        "minimal" -> R.string.look_minimal
        "default" -> R.string.look_default
        else -> R.string.look_pick
    })

    private fun colorName(id: String) = s(when (id) {
        "auto" -> R.string.c_auto
        "sand" -> R.string.c_sand
        "rose" -> R.string.c_rose
        "sky" -> R.string.c_sky
        "mint" -> R.string.c_mint
        "lilac" -> R.string.c_lilac
        "amber" -> R.string.c_amber
        else -> R.string.c_white
    })

    fun open(startPart: Int = 0, startOpt: Int = 0, animate: Boolean = true) {
        part = startPart.coerceIn(0, parts.size - 1)
        opt = startOpt.coerceIn(0, parts[part].opts.size - 1)
        isOpen = true
        visibility = VISIBLE
        requestFocus()
        host.setEditing(true, animate)
        top.alpha = 0f
        top.animate().alpha(1f).setDuration(Motion.SLOW).setStartDelay(120).start()
        pill.alpha = 0f
        pill.translationY = 24f.dp
        pill.animate().alpha(1f).translationY(0f).setDuration(Motion.SLOW).setStartDelay(160).setInterpolator(Motion.out).start()
        render()
        postDelayed({ moveFrame(false) }, if (animate) Motion.SLOW + 80 else 60)
    }

    /** Current part and option, kept across the restart a language change needs. */
    fun position(): Pair<Int, Int> = part to opt

    override fun dismiss(animate: Boolean) {
        if (!isOpen) return
        isOpen = false
        // Whatever is on screen now is "your look" the next time Customize opens.
        lastPreset = MINE
        host.setEditing(false, animate)
        frameView.hide()
        val done = Runnable { visibility = GONE }
        if (animate) {
            top.animate().alpha(0f).setDuration(Motion.FAST).start()
            pill.animate().alpha(0f).translationY(24f.dp).setDuration(Motion.MED).setInterpolator(Motion.accel).withEndAction(done).start()
        } else done.run()
        host.overlayClosed(this)
    }

    override fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU -> if (e.repeatCount == 0) dismiss()
            KeyEvent.KEYCODE_DPAD_UP -> selectPart(part - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> selectPart(part + 1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> change(readingStep(e.keyCode))
            else -> if (isConfirm(e.keyCode) && e.repeatCount == 0) {
                opt = (opt + 1) % parts[part].opts.size
                host.navSound(View.FOCUS_FORWARD)
                render()
                pulse()
            }
        }
        return true
    }

    private fun selectPart(i: Int) {
        if (i < 0 || i >= parts.size) {
            bump(pill, if (i < 0) -1 else 1, true)
            return
        }
        part = i
        opt = 0
        host.navSound(View.FOCUS_DOWN)
        host.previewPart(parts[part].area)
        render()
        pulse()
        postDelayed({ moveFrame(true) }, 60)
    }

    private fun change(step: Int) {
        val o = parts[part].opts[opt]
        val values = o.values()
        if (values.isEmpty()) return
        val cur = values.indexOf(o.get())
        val next = if (cur < 0) (if (step > 0) 0 else values.size - 1) else (cur + step + values.size) % values.size
        o.set(values[next])
        host.navSound(if (step > 0) View.FOCUS_RIGHT else View.FOCUS_LEFT)
        render()
        host.applyLook(o.area)
        // Sizes and places change with the value; follow once the layout settles.
        postDelayed({ if (isOpen) moveFrame(true) }, 320)
    }

    private fun render() {
        val p = parts[part]
        val o = p.opts[opt]
        pillIcon.setImageResource(p.icon)
        pillPart.text = "${s(p.name)}  ·  ${s(o.name)}"
        val v = o.label(o.get())
        // Bidi mirrors these in right-to-left text, so they always point outwards.
        pillValue.text = "‹  $v  ›"
        dots.removeAllViews()
        if (p.opts.size > 1) {
            for (i in p.opts.indices) {
                val d = View(host)
                d.background = rounded(if (i == opt) Colors.WHITE else 0x4DFFFFFF, 3f.dp)
                dots.addView(d, LinearLayout.LayoutParams(if (i == opt) 14.dp else 6.dp, 6.dp).apply { marginStart = 5.dp })
            }
        }
    }

    private fun pulse() {
        pillValue.animate().cancel()
        pillValue.alpha = 0.4f
        pillValue.translationY = 4f.dp
        pillValue.animate().alpha(1f).translationY(0f).setDuration(Motion.MED).setInterpolator(Motion.out).start()
    }

    private fun moveFrame(animate: Boolean) {
        if (!isOpen) return
        val r = host.areaRect(parts[part].area)
        val me = IntArray(2)
        getLocationInWindow(me)
        r.offset(-me[0].toFloat(), -me[1].toFloat())
        frameView.moveTo(r, animate)
    }

    companion object {
        private const val MINE = "mine"
        /** The look picked in this Customize session; survives the screen being rebuilt after a look is restored. */
        private var lastPreset = MINE
    }
}

/** Rounded outline that glides between the parts being edited. */
class FocusFrame(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f.dp
        color = 0xE6FFFFFF.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
    private val cur = RectF()
    private val from = RectF()
    private val to = RectF()
    private var anim: ValueAnimator? = null
    private var shown = false
    private val radius = 18f.dp

    init { alpha = 0f }

    fun moveTo(r: RectF, animate: Boolean) {
        r.inset(-10f.dp, -10f.dp)
        anim?.cancel()
        if (!shown || !animate) {
            cur.set(r)
            invalidate()
            if (!shown) {
                shown = true
                animate().alpha(1f).setDuration(Motion.MED).start()
            }
            return
        }
        from.set(cur)
        to.set(r)
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.SLOW
            interpolator = Motion.out
            addUpdateListener {
                val t = it.animatedFraction
                cur.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t)
                invalidate()
            }
            start()
        }
    }

    fun hide() {
        shown = false
        anim?.cancel()
        animate().alpha(0f).setDuration(Motion.FAST).start()
    }

    override fun onDraw(canvas: Canvas) {
        if (cur.isEmpty) return
        canvas.drawRoundRect(cur, radius, radius, fill)
        canvas.drawRoundRect(cur, radius, radius, paint)
    }
}
