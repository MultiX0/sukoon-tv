package dev.multix.tvhome.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.icu.text.DateFormat
import android.icu.util.ULocale
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.multix.tvhome.R
import dev.multix.tvhome.core.Colors
import dev.multix.tvhome.core.Fonts
import dev.multix.tvhome.core.Lang
import dev.multix.tvhome.core.Look
import dev.multix.tvhome.core.Motion
import dev.multix.tvhome.core.Prefs
import dev.multix.tvhome.core.SText
import dev.multix.tvhome.core.W
import dev.multix.tvhome.core.dp
import dev.multix.tvhome.core.rounded
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Greeting, the clock and the date. Every visual choice comes from Look:
 * face, style (one line, stacked, outline), size, colour, digits and the
 * Gregorian or Hijri calendar. It ticks on the exact second or minute
 * boundary and only while the screen is visible.
 */
class ClockView(ctx: Context, private val big: Boolean = true) : LinearLayout(ctx) {
    val greeting = SText(ctx, W.REGULAR, if (big) 20f else 15f, Colors.TEXT2)
    private val time = ClockFace(ctx)
    private val seconds = SText(ctx, W.DISPLAY, 34f, Colors.TEXT3)
    private val ampm = SText(ctx, W.DISPLAY, 24f, Colors.TEXT3)
    val date = SText(ctx, W.LIGHT, if (big) 19f else 14f, Colors.TEXT2)
    private val row = LinearLayout(ctx)
    private val plate = rounded(0x1FFFFFFF, 22f.dp)

    private var running = false
    private var hm: SimpleDateFormat? = null
    private var hh: SimpleDateFormat? = null
    private var mm: SimpleDateFormat? = null
    private var ss: SimpleDateFormat? = null
    private var ap: SimpleDateFormat? = null
    private var dt: DateFormat? = null
    private var formatsFor = ""
    private val now = Date()
    var autoColor: Int = Colors.WHITE
    var extra: String = ""
        set(v) { field = v; update() }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        for (t in arrayOf(seconds, ampm)) {
            t.fixedFace = true
            // Digits never need the extra line padding Arabic text does.
            t.includeFontPadding = false
        }
        if (big) {
            // Room for the focus plate; callers offset the margins by the same amount.
            setPadding(PAD.dp, PAD.dp, PAD.dp, PAD.dp)
            isFocusable = true
            isClickable = true
            defaultFocusHighlightEnabled = false
            plate.alpha = 0
            background = plate
            addView(greeting)
        }
        row.orientation = HORIZONTAL
        // Clock digits always read left to right, even in Arabic.
        row.layoutDirection = View.LAYOUT_DIRECTION_LTR
        row.isBaselineAligned = true
        row.addView(time)
        row.addView(seconds, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (if (big) 10 else 5).dp })
        row.addView(ampm, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (if (big) 10 else 5).dp })
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        if (big) {
            addView(date, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        applyLook()
    }

    private val tick = object : Runnable {
        override fun run() {
            update()
            if (!running) return
            val secs = big && Prefs.clockSeconds && Look.clockStyle != "stacked"
            val period = if (secs) 1000L else 60_000L
            postDelayed(this, period - System.currentTimeMillis() % period + 5)
        }
    }

    /** The digits alone, for the Customize frame. */
    fun timeView(): View = row

    fun start() {
        if (running) return
        running = true
        removeCallbacks(tick)
        tick.run()
    }

    fun stop() {
        running = false
        removeCallbacks(tick)
    }

    /** Re-reads every Look option that affects the clock. */
    fun applyLook() {
        val face = Fonts.clock(context)
        val size = if (!big) 28f else when (Look.clockStyle) {
            "stacked" -> floatArrayOf(76f, 92f, 110f, 128f)[Look.clockSize.coerceIn(0, 3)]
            else -> floatArrayOf(72f, 88f, 104f, 128f)[Look.clockSize.coerceIn(0, 3)]
        }
        seconds.typeface = face
        ampm.typeface = face
        seconds.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * 0.33f)
        ampm.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * 0.23f)
        val color = if (big) Look.clockColor(autoColor) else Colors.WHITE
        seconds.setTextColor(color and 0x00FFFFFF or 0x99000000.toInt())
        ampm.setTextColor(color and 0x00FFFFFF or 0x99000000.toInt())
        val outline = big && Look.clockStyle == "outline"
        val centered = big && Look.clockPos == "center"
        time.configure(
            face = face,
            px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics),
            color = color,
            outline = outline,
            tracking = if (Lang.isArabic || Look.clockStyle == "stacked") 0f else -0.02f,
            arabicDigits = Look.arabicDigits,
            align = when {
                centered -> 0
                Lang.isArabic -> 1
                else -> -1
            },
        )
        seconds.fontFeatureSettings = "tnum"
        val g = if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        gravity = g
        for (t in arrayOf(greeting, date)) {
            t.textAlignment = if (centered) View.TEXT_ALIGNMENT_CENTER else View.TEXT_ALIGNMENT_VIEW_START
        }
        (row.layoutParams as? LayoutParams)?.let {
            it.gravity = g
            // ClockFace is exactly as tall as its ink, so plain gaps are enough.
            it.topMargin = if (big) 10.dp else 0
            it.bottomMargin = if (big) 12.dp else 0
        }
        if (big) {
            greeting.visibility = if (Look.greetOn) VISIBLE else GONE
            date.visibility = if (Look.dateOn || extra.isNotEmpty()) VISIBLE else GONE
        }
        formatsFor = ""
        update()
        requestLayout()
    }

    private fun formats() {
        val is24 = Prefs.clock24(context)
        val key = "${Lang.locale()}|$is24|${Look.hijri}"
        if (key == formatsFor) return
        formatsFor = key
        val loc = Lang.locale()
        hm = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", loc)
        hh = SimpleDateFormat(if (is24) "HH" else "h", loc)
        mm = SimpleDateFormat("mm", loc)
        ss = SimpleDateFormat("ss", loc)
        ap = if (is24) null else SimpleDateFormat("a", loc)
        val lang = if (Lang.isArabic) "ar" else "en"
        val uloc = ULocale(if (Look.hijri) "$lang@calendar=islamic-umalqura;numbers=latn" else "$lang@numbers=latn")
        dt = DateFormat.getInstanceForSkeleton(if (Look.hijri) "EEEEdMMMMy" else "EEEEMMMMd", uloc)
    }

    private fun digits(s: String): String {
        if (!Look.arabicDigits) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') ('٠' + (c - '0')) else c)
        return sb.toString()
    }

    fun update() {
        formats()
        now.time = System.currentTimeMillis()
        val stacked = big && Look.clockStyle == "stacked"
        time.setText(digits(if (stacked) "${hh!!.format(now)}\n${mm!!.format(now)}" else hm!!.format(now)))
        val showSecs = big && Prefs.clockSeconds && !stacked
        seconds.visibility = if (showSecs) VISIBLE else GONE
        if (showSecs) seconds.text = digits(ss!!.format(now))
        val a = ap
        ampm.visibility = if (a != null && !stacked) VISIBLE else GONE
        if (a != null) ampm.text = a.format(now)
        if (big) {
            greeting.text = Lang.greeting(context)
            val d = if (Look.dateOn) digits(dt!!.format(now)) else ""
            date.text = when {
                d.isEmpty() -> extra
                extra.isEmpty() -> d
                else -> "$d   ·   $extra"
            }
            date.visibility = if (date.text.isEmpty()) GONE else VISIBLE
        }
    }

    private var plateAnim: ValueAnimator? = null

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        plateAnim?.cancel()
        plateAnim = ValueAnimator.ofInt(plate.alpha, if (gainFocus) 255 else 0).apply {
            duration = Motion.MED
            interpolator = Motion.out
            addUpdateListener { plate.alpha = it.animatedValue as Int }
            start()
        }
    }

    companion object {
        const val PAD = 16
    }
}

/**
 * The clock digits, drawn directly with a Paint and sized to the ink of the
 * digits rather than the font's line box. Fonts differ a lot in how much
 * empty space they put above and below numbers (IBM Plex Arabic most of all),
 * so a TextView would sit every face at a different distance from the
 * greeting and the date, and stacked lines would run into the date. All
 * measuring happens when the text or the style changes; onDraw only draws
 * precomputed lines.
 */
class ClockFace(ctx: Context) : View(ctx) {
    private val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var lines: Array<String> = arrayOf("")
    private var raw = ""
    private val widths = FloatArray(4)
    private val ink = Rect()
    private var inkTop = 0f
    private var lineInk = 0f
    private var gap = 0f
    private var padX = 0f
    private var padY = 0f
    private var align = -1
    private var lastLineCount = 1

    init {
        paint.fontFeatureSettings = "tnum"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun hasOverlappingRendering() = false

    fun configure(face: android.graphics.Typeface, px: Float, color: Int, outline: Boolean, tracking: Float,
                  arabicDigits: Boolean, align: Int) {
        paint.typeface = face
        paint.textSize = px
        paint.color = color
        paint.letterSpacing = tracking
        paint.style = if (outline) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = if (outline) maxOf(1.5f.dp, px * 0.016f) else 0f
        this.align = align
        val ref = if (arabicDigits) "٠١٢٣٤٥٦٧٨٩" else "0123456789"
        paint.getTextBounds(ref, 0, ref.length, ink)
        inkTop = -ink.top.toFloat()
        lineInk = (ink.bottom - ink.top).toFloat()
        gap = lineInk * 0.14f
        padX = paint.strokeWidth + 2f.dp
        padY = paint.strokeWidth + 1f.dp
        // Outlined glyphs are paths; a layer keeps them to one raster per
        // minute instead of one per frame while the wallpaper drifts below.
        setLayerType(if (outline) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE, null)
        measureLines()
        requestLayout()
        invalidate()
    }

    fun setText(s: String) {
        if (s == raw) return
        raw = s
        val oldW = maxWidth()
        lines = s.split('\n').toTypedArray()
        measureLines()
        if (maxWidth() != oldW || lines.size != lastLineCount) requestLayout()
        invalidate()
    }

    private fun measureLines() {
        for (i in lines.indices) if (i < widths.size) widths[i] = paint.measureText(lines[i])
    }

    private fun maxWidth(): Float {
        var m = 0f
        for (i in lines.indices) if (i < widths.size) m = maxOf(m, widths[i])
        return m
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        lastLineCount = lines.size
        val w = Math.ceil((maxWidth() + padX * 2).toDouble()).toInt()
        val h = Math.ceil((lines.size * lineInk + (lines.size - 1) * gap + padY * 2).toDouble()).toInt()
        setMeasuredDimension(w, h)
    }

    override fun getBaseline(): Int = (padY + inkTop + (lines.size - 1) * (lineInk + gap)).toInt()

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat()
        for (i in lines.indices) {
            val lw = if (i < widths.size) widths[i] else 0f
            val x = when (align) {
                0 -> (w - lw) / 2f
                1 -> w - padX - lw
                else -> padX
            }
            canvas.drawText(lines[i], x, padY + inkTop + i * (lineInk + gap), paint)
        }
    }
}
