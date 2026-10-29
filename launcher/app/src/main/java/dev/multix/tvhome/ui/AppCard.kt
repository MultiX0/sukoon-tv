package dev.multix.tvhome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import dev.multix.tvhome.R
import dev.multix.tvhome.apps.AppEntry
import dev.multix.tvhome.apps.Apps
import dev.multix.tvhome.apps.Shapes
import dev.multix.tvhome.core.Colors
import dev.multix.tvhome.core.Images
import dev.multix.tvhome.core.Lang
import dev.multix.tvhome.core.Motion
import dev.multix.tvhome.core.SText
import dev.multix.tvhome.core.W
import dev.multix.tvhome.core.dp
import dev.multix.tvhome.core.frame
import dev.multix.tvhome.core.rounded
import kotlin.math.roundToInt

/** Card geometry shared by the dock, the grid and the bitmap renderer. */
object CardSpec {
    const val FOCUS_SCALE = 1.1f
    var w = 0
    var h = 0
    var radius = 0f
    var glowBlur = 0f
    var shadowBlur = 0f

    private var glowBmp: Bitmap? = null
    private var shadowBmp: Bitmap? = null
    private val iconGlows = HashMap<Int, Bitmap>()

    fun init() {
        if (w != 0) return
        w = 148.dp
        h = (w * 9f / 16f).roundToInt()
        glowBlur = 14f.dp
        shadowBlur = 9f.dp
        Apps.cardW = (w * FOCUS_SCALE).roundToInt()
        Apps.cardH = (h * FOCUS_SCALE).roundToInt()
        applyShape()
    }

    /** Card corners follow the dock's shape choice: circle gives fully round ends. */
    fun applyShape() {
        val r = when (dev.multix.tvhome.core.Look.iconShape) {
            "circle" -> h / 2f
            "rounded" -> 8f.dp
            else -> 14f.dp
        }
        if (r == radius) return
        radius = r
        Apps.radius = radius * FOCUS_SCALE
        glowBmp = null
        shadowBmp = null
    }

    fun glow(): Bitmap = glowBmp ?: Images.glow(w, h, radius, glowBlur).also { glowBmp = it }
    fun shadow(): Bitmap = shadowBmp ?: Images.glow(w, h, radius, shadowBlur).also { shadowBmp = it }

    /** A soft halo for round-ish icons of [size] px. */
    fun iconGlow(size: Int): Bitmap = iconGlows.getOrPut(size) { Images.glow(size, size, size * 0.42f, size * 0.16f) }
}

/** What the dock and the grid need from any app tile, whatever its style. */
interface DockItem {
    val entry: AppEntry?
    val view: View
    fun bind(e: AppEntry)
    fun bindAllApps(text: String)
    fun cardReady()
    fun nudge(dir: Int, vertical: Boolean = false)
    fun tileView(): View
}

/** Where a tile shows its app name. */
enum class Label { TOP, BELOW, END, NONE }

/**
 * Banner card. The tile (banner, glow, shadow) scales on focus; the label is
 * a separate child so it can fade without being scaled.
 */
class AppCard(ctx: Context, private val labelMode: Label) : FrameLayout(ctx), DockItem {
    override var entry: AppEntry? = null
        private set
    override val view: View get() = this

    private val tile = FrameLayout(ctx)
    private val shadow = ImageView(ctx)
    private val glow = ImageView(ctx)
    private val image = ImageView(ctx)
    private val badge = ImageView(ctx)
    val label = SText(ctx, W.MEDIUM, if (labelMode == Label.BELOW) 13f else 15f, Colors.WHITE)

    private var focusedNow = false
    var dimmed = false
        set(v) { field = v; image.alpha = if (v) 0.35f else 1f }
    var radius = CardSpec.radius
        set(v) { field = v; if (entry == null) applyAllAppsBg(focusedNow) }

    init {
        CardSpec.init()
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false

        val labelH = 22.dp
        val gap = 10.dp
        val labelW = (CardSpec.w * 1.3f).toInt()
        layoutParams = when (labelMode) {
            Label.END -> LayoutParams(CardSpec.w + gap + labelW, CardSpec.h)
            Label.NONE -> LayoutParams(CardSpec.w, CardSpec.h)
            else -> LayoutParams(CardSpec.w, CardSpec.h + labelH + gap)
        }

        tile.clipChildren = false
        tile.clipToPadding = false
        val tileGravity = if (labelMode == Label.END) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.TOP
        addView(tile, frame(CardSpec.w, CardSpec.h, tileGravity).apply {
            if (labelMode == Label.TOP) topMargin = labelH + gap
        })

        val sb = CardSpec.shadow()
        shadow.setImageBitmap(sb)
        shadow.colorFilter = PorterDuffColorFilter(0xFF000000.toInt(), PorterDuff.Mode.SRC_IN)
        shadow.alpha = 0.55f
        shadow.translationY = 5f.dp
        tile.addView(shadow, frame(sb.width, sb.height, Gravity.CENTER))

        val gb = CardSpec.glow()
        glow.setImageBitmap(gb)
        glow.alpha = 0f
        tile.addView(glow, frame(gb.width, gb.height, Gravity.CENTER))

        image.scaleType = ImageView.ScaleType.FIT_XY
        tile.addView(image, frame(CardSpec.w, CardSpec.h))

        badge.visibility = GONE
        tile.addView(badge, frame(22.dp, 22.dp, Gravity.TOP or Gravity.END).apply {
            topMargin = (-7).dp
            marginEnd = (-7).dp
        })

        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        when (labelMode) {
            Label.TOP -> {
                label.gravity = Gravity.CENTER
                label.textAlignment = View.TEXT_ALIGNMENT_CENTER
                label.alpha = 0f
                label.translationY = 6f.dp
                addView(label, frame((CardSpec.w * 1.5f).toInt(), labelH, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            }
            Label.BELOW -> {
                label.gravity = Gravity.CENTER
                label.textAlignment = View.TEXT_ALIGNMENT_CENTER
                label.alpha = 0.62f
                addView(label, frame(CardSpec.w, labelH, Gravity.TOP).apply { topMargin = CardSpec.h + gap })
            }
            Label.END -> {
                label.alpha = 0f
                label.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                addView(label, frame(labelW, labelH + 6.dp, Gravity.END or Gravity.CENTER_VERTICAL))
            }
            Label.NONE -> {}
        }
    }

    override fun hasOverlappingRendering() = false

    override fun bind(e: AppEntry) {
        entry = e
        label.text = e.label
        contentDescription = e.label
        val card = e.card
        if (card != null) {
            image.setImageBitmap(card)
            image.background = null
        } else {
            image.setImageDrawable(null)
            image.background = rounded(Colors.GLASS, CardSpec.radius)
        }
        glow.colorFilter = PorterDuffColorFilter(e.glow, PorterDuff.Mode.SRC_IN)
    }

    /** The "All apps" tile: a glass card with the grid icon. */
    override fun bindAllApps(text: String) {
        entry = null
        label.text = text
        contentDescription = text
        image.scaleType = ImageView.ScaleType.CENTER
        image.setImageResource(R.drawable.ic_apps)
        applyAllAppsBg(false)
        glow.colorFilter = PorterDuffColorFilter(Colors.WHITE, PorterDuff.Mode.SRC_IN)
    }

    private fun applyAllAppsBg(f: Boolean) {
        image.background = rounded(if (f) 0xF2FFFFFF.toInt() else Colors.GLASS_STRONG, CardSpec.radius)
        image.setColorFilter(if (f) Colors.INK else Colors.WHITE, PorterDuff.Mode.SRC_IN)
    }

    /** Fades the banner in once its bitmap is ready, so nothing pops. */
    override fun cardReady() {
        val e = entry ?: return
        val card = e.card ?: return
        image.setImageBitmap(card)
        image.background = null
        glow.colorFilter = PorterDuffColorFilter(e.glow, PorterDuff.Mode.SRC_IN)
        image.alpha = 0f
        image.animate().alpha(if (dimmed) 0.35f else 1f).setDuration(Motion.MED).setInterpolator(Motion.out).start()
    }

    fun setBadge(res: Int?) {
        if (res == null) {
            if (badge.visibility == VISIBLE) {
                badge.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(Motion.FAST)
                    .withEndAction { badge.visibility = GONE }.start()
            }
            return
        }
        badge.setImageResource(res)
        if (badge.visibility != VISIBLE) {
            badge.visibility = VISIBLE
            badge.alpha = 0f
            badge.scaleX = 0.6f
            badge.scaleY = 0.6f
            badge.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.MED).setInterpolator(Motion.spring).start()
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        applyFocus(gainFocus, true)
    }

    fun applyFocus(f: Boolean, animate: Boolean) {
        if (f == focusedNow && animate) return
        focusedNow = f
        val s = if (f) CardSpec.FOCUS_SCALE else 1f
        translationZ = if (f) 1f else 0f
        val d = if (animate) (if (f) Motion.SLOW else Motion.MED) else 0L
        tile.animate().scaleX(s).scaleY(s).setDuration(d)
            .setInterpolator(if (f) Motion.spring else Motion.out).start()
        glow.animate().alpha(if (f) 0.75f else 0f).setDuration(d).setInterpolator(Motion.out).start()
        shadow.animate().alpha(if (f) 0.8f else 0.55f).translationY(if (f) 9f.dp else 5f.dp)
            .setDuration(d).setInterpolator(Motion.out).start()
        when (labelMode) {
            Label.TOP -> label.animate().alpha(if (f) 1f else 0f).translationY(if (f) 0f else 6f.dp)
                .setDuration(d).setInterpolator(Motion.out).start()
            Label.BELOW -> label.animate().alpha(if (f) 1f else 0.62f).translationY(if (f) 5f.dp else 0f)
                .setDuration(d).setInterpolator(Motion.out).start()
            Label.END -> {
                val dx = (if (Lang.isArabic) -1 else 1) * 8f.dp
                label.animate().alpha(if (f) 1f else 0f).translationX(if (f) dx else 0f)
                    .setDuration(d).setInterpolator(Motion.out).start()
            }
            Label.NONE -> {}
        }
        if (entry == null) applyAllAppsBg(f)
    }

    override fun nudge(dir: Int, vertical: Boolean) = bump(tile, dir, vertical)

    override fun tileView(): View = tile
}

/**
 * Round app icon for the glass pill and the icon-only dock. The icon bitmap
 * is rendered once per shape and size on the background thread.
 */
class AppIcon(ctx: Context, private val size: Int, private val labelMode: Label, private val focusScale: Float) :
    FrameLayout(ctx), DockItem {
    override var entry: AppEntry? = null
        private set
    override val view: View get() = this

    private val tile = FrameLayout(ctx)
    private val glow = ImageView(ctx)
    private val image = ImageView(ctx)
    val label = SText(ctx, W.MEDIUM, 13f, Colors.WHITE)
    private var focusedNow = false
    var shape = "squircle"

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        val labelH = 20.dp
        val labelW = (size * 2.2f).toInt()
        layoutParams = when (labelMode) {
            Label.BELOW -> LayoutParams(size, size + 8.dp + labelH)
            Label.END -> LayoutParams(size + 12.dp + labelW, size)
            Label.TOP -> LayoutParams(size, size)
            Label.NONE -> LayoutParams(size, size)
        }
        tile.clipChildren = false
        addView(tile, frame(size, size, if (labelMode == Label.END) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.TOP))
        val gb = CardSpec.iconGlow(size)
        glow.setImageBitmap(gb)
        glow.alpha = 0f
        tile.addView(glow, frame(gb.width, gb.height, Gravity.CENTER))
        image.scaleType = ImageView.ScaleType.FIT_XY
        tile.addView(image, frame(size, size))

        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        when (labelMode) {
            Label.BELOW -> {
                label.gravity = Gravity.CENTER
                label.textAlignment = View.TEXT_ALIGNMENT_CENTER
                label.alpha = 0.7f
                addView(label, frame((size * 1.5f).toInt(), labelH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = size + 8.dp })
            }
            Label.TOP -> {
                // Tooltip above the icon, shown only on focus.
                label.gravity = Gravity.CENTER
                label.textAlignment = View.TEXT_ALIGNMENT_CENTER
                label.alpha = 0f
                addView(label, frame(labelW, labelH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = -(labelH + 14.dp) })
            }
            Label.END -> {
                label.alpha = 0f
                label.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                addView(label, frame(labelW, labelH + 6.dp, Gravity.END or Gravity.CENTER_VERTICAL))
            }
            Label.NONE -> {}
        }
    }

    override fun hasOverlappingRendering() = false

    override fun bind(e: AppEntry) {
        entry = e
        label.text = e.label
        contentDescription = e.label
        glow.colorFilter = PorterDuffColorFilter(e.glow, PorterDuff.Mode.SRC_IN)
        val icon = e.icon
        if (icon != null && e.iconKey == "$shape:$size") {
            image.setImageBitmap(icon)
            image.background = null
        } else {
            image.setImageDrawable(null)
            image.background = ShapeFill(shape, Colors.GLASS)
        }
    }

    override fun bindAllApps(text: String) {
        entry = null
        label.text = text
        contentDescription = text
        image.scaleType = ImageView.ScaleType.CENTER
        image.setImageResource(R.drawable.ic_apps)
        image.setColorFilter(Colors.WHITE, PorterDuff.Mode.SRC_IN)
        image.background = ShapeFill(shape, Colors.GLASS_STRONG)
        glow.colorFilter = PorterDuffColorFilter(Colors.WHITE, PorterDuff.Mode.SRC_IN)
    }

    override fun cardReady() {
        val e = entry ?: return
        val icon = e.icon ?: return
        if (e.iconKey != "$shape:$size") return
        image.setImageBitmap(icon)
        image.background = null
        glow.colorFilter = PorterDuffColorFilter(e.glow, PorterDuff.Mode.SRC_IN)
        image.alpha = 0f
        image.animate().alpha(1f).setDuration(Motion.MED).setInterpolator(Motion.out).start()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus == focusedNow) return
        focusedNow = gainFocus
        val f = gainFocus
        translationZ = if (f) 1f else 0f
        val d = if (f) Motion.SLOW else Motion.MED
        tile.animate().scaleX(if (f) focusScale else 1f).scaleY(if (f) focusScale else 1f).setDuration(d)
            .setInterpolator(if (f) Motion.spring else Motion.out).start()
        glow.animate().alpha(if (f) 0.8f else 0f).setDuration(d).start()
        when (labelMode) {
            Label.BELOW -> label.animate().alpha(if (f) 1f else 0.7f).translationY(if (f) size * (focusScale - 1f) / 2f else 0f).setDuration(d).start()
            Label.TOP -> label.animate().alpha(if (f) 1f else 0f).translationY(if (f) -size * (focusScale - 1f) / 2f else 0f).setDuration(d).start()
            Label.END -> {
                val dx = (if (Lang.isArabic) -1 else 1) * size * (focusScale - 1f) / 2f
                label.animate().alpha(if (f) 1f else 0f).translationX(if (f) dx else 0f).setDuration(d).start()
            }
            Label.NONE -> {}
        }
        if (entry == null) {
            image.background = ShapeFill(shape, if (f) 0xF2FFFFFF.toInt() else Colors.GLASS_STRONG)
            image.setColorFilter(if (f) Colors.INK else Colors.WHITE, PorterDuff.Mode.SRC_IN)
        }
    }

    override fun nudge(dir: Int, vertical: Boolean) = bump(tile, dir, vertical)

    override fun tileView(): View = tile
}

/**
 * A line in the minimal text-list dock. The frosted highlight behind the
 * focused line belongs to the dock and slides between lines, like the tvOS
 * sidebar; the line itself only brightens.
 */
class AppText(ctx: Context) : FrameLayout(ctx), DockItem {
    override var entry: AppEntry? = null
        private set
    override val view: View get() = this
    val label = SText(ctx, W.MEDIUM, 20f, Colors.WHITE)
    private var focusedNow = false

    init {
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        clipChildren = false
        layoutParams = LayoutParams(WIDTH.dp, HEIGHT.dp)
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.alpha = 0.75f
        // Keeps the names readable on bright photos.
        label.setShadowLayer(8f.dp, 0f, 1f.dp, 0x99000000.toInt())
        addView(label, frame(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = 18.dp
            marginEnd = 18.dp
        })
    }

    override fun bind(e: AppEntry) {
        entry = e
        label.text = e.label
        contentDescription = e.label
    }

    override fun bindAllApps(text: String) {
        entry = null
        label.text = text
        contentDescription = text
    }

    override fun cardReady() {}

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus == focusedNow) return
        focusedNow = gainFocus
        label.animate().alpha(if (gainFocus) 1f else 0.75f)
            .setDuration(if (gainFocus) Motion.SLOW else Motion.MED).setInterpolator(Motion.out).start()
    }

    companion object {
        const val WIDTH = 260
        const val HEIGHT = 46
    }

    override fun nudge(dir: Int, vertical: Boolean) = bump(this, dir, vertical)

    override fun tileView(): View = label
}

/** A small bump when the user pushes against the end of a row or column. */
fun bump(v: View, dir: Int, vertical: Boolean) {
    val d = 7f.dp * dir
    val a = v.animate()
    if (vertical) a.translationY(d) else a.translationX(d)
    a.setDuration(110).setInterpolator(Motion.out).withEndAction {
        v.animate().translationX(0f).translationY(0f).setDuration(320).setInterpolator(Motion.spring).start()
    }.start()
}

/** Solid fill in one of the icon shapes; used as a placeholder and for the All apps icon. */
class ShapeFill(private val shape: String, color: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var path = android.graphics.Path()
    private var built = 0

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (built != b.width()) {
            path = Shapes.path(shape, b.width().toFloat())
            path.offset(b.left.toFloat(), b.top.toFloat())
            built = b.width()
        }
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
