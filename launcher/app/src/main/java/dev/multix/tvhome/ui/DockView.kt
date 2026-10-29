package dev.multix.tvhome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import dev.multix.tvhome.apps.AppEntry
import dev.multix.tvhome.core.Look
import dev.multix.tvhome.core.Motion
import dev.multix.tvhome.core.dp
import dev.multix.tvhome.core.frame
import dev.multix.tvhome.core.roundClip

/**
 * The favourite apps, in one of four looks: a row of banner cards, a
 * floating glass pill of icons, bare icons, or a plain text list. It sits
 * along the bottom or down the left edge (the list is always on the left).
 */
class DockView(ctx: Context) : FrameLayout(ctx) {
    var style = ""
        private set
    var vertical = false
        private set
    private var labels = true
    private var shape = ""

    private val glass = GlassBox(ctx)
    /** The frosted pill behind the focused line of the text list. */
    private val highlight = View(ctx)
    private val frost = ImageView(ctx)
    private val sheen = View(ctx)
    private val frostMatrix = Matrix()
    private var strip: Strip = Strip(ctx, false, 0, 0, 0)
        set(v) { field = v; glass.content = v }

    val items = ArrayList<DockItem>()
    private var allApps: DockItem? = null

    var onItemClick: ((DockItem) -> Unit)? = null
    var onItemFocus: ((DockItem) -> Unit)? = null
    var allAppsText = ""

    /** Pixel size of icons for the current style; 0 for cards and text. */
    val iconSize: Int get() = when (style) { "pill" -> 54.dp; "icons" -> 76.dp; else -> 0 }
    val iconShape: String get() = shape

    init {
        clipChildren = false
        clipToPadding = false
        glass.clipChildren = false
        glass.clipToPadding = false
        frost.scaleType = ImageView.ScaleType.MATRIX
        frost.colorFilter = LightingColorFilter(0xFF9A9A9A.toInt(), 0x00141414)
        glass.addView(frost, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        glass.addView(sheen, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        highlight.background = GradientDrawable().apply {
            cornerRadius = 23f.dp
            setColor(0x33FFFFFF)
            setStroke(1.dp, 0x40FFFFFF)
        }
        highlight.alpha = 0f
        highlight.elevation = 0f
        // Absolute LEFT so the translation is the same in both layout directions.
        addView(highlight, frame(AppText.WIDTH.dp, AppText.HEIGHT.dp, Gravity.TOP or Gravity.LEFT))
        addView(glass)
        glass.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignFrost() }
    }

    /** True when the look changed enough that the tiles must be rebuilt. */
    fun configure(): Boolean {
        val s = Look.dockStyle
        val v = Look.dockVertical
        val l = Look.dockLabels
        val sh = Look.iconShape
        if (s == style && v == vertical && l == labels && sh == shape) return false
        style = s
        vertical = v
        labels = l
        shape = sh
        val pill = s == "pill"
        val (gap, edge, look) = when (s) {
            "cards" -> Triple(if (v) 16.dp else 22.dp, if (v) 36.dp else 56.dp, if (v) 40.dp else 64.dp)
            "pill" -> Triple(16.dp, 16.dp, 30.dp)
            "icons" -> Triple(if (v) 22.dp else 30.dp, if (v) 36.dp else 56.dp, 48.dp)
            else -> Triple(2.dp, 0, 42.dp)
        }
        glass.removeView(strip)
        strip = Strip(context, v, gap, edge, look)
        // On the side the dock is 0.8 of its size, centred top to bottom, with the focused tile held in the middle.
        // (The pill keeps its block: its glass would not follow tiles sliding inside it.)
        strip.centerLock = v && !pill
        val scale = if (v) SIDE_SCALE else 1f
        scaleX = scale
        scaleY = scale
        val pad = if (pill) 14.dp else 0
        glass.setPadding(if (v || pill) pad else 0, if (pill) pad else 0, if (v || pill) pad else 0, if (pill) pad else 0)
        glass.addView(strip, frame(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        frost.visibility = if (pill) VISIBLE else GONE
        sheen.visibility = if (pill) VISIBLE else GONE
        if (pill) {
            val r = (54.dp + 2 * pad) / 2f
            sheen.background = GradientDrawable().apply {
                cornerRadius = r
                setColor(0x1AFFFFFF)
                setStroke(1.dp, 0x2EFFFFFF)
            }
            glass.roundClip(r)
            glass.elevation = 12f.dp
        } else {
            glass.clipToOutline = false
            glass.outlineProvider = null
            glass.elevation = 0f
        }
        val gravity = when {
            v -> Gravity.CENTER_VERTICAL or Gravity.START
            pill -> Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            else -> Gravity.START or Gravity.BOTTOM
        }
        glass.layoutParams = frame(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity)
        // A list longer than its space scrolls inside its own box, so the box
        // (and the Customize frame around it) always shows whole lines.
        glass.clipChildren = s == "list"
        items.clear()
        allApps = null
        return true
    }

    private fun labelMode(): Label = when {
        !labels -> Label.NONE
        vertical -> Label.END
        style == "cards" -> Label.TOP
        style == "pill" -> Label.TOP
        else -> Label.BELOW
    }

    private fun newItem(): DockItem {
        val item: DockItem = when (style) {
            "pill" -> AppIcon(context, 54.dp, labelMode(), 1.25f).also { it.shape = shape }
            "icons" -> AppIcon(context, 76.dp, labelMode(), 1.15f).also { it.shape = shape }
            "list" -> AppText(context)
            else -> AppCard(context, labelMode())
        }
        item.view.setOnClickListener { onItemClick?.invoke(item) }
        item.view.setOnFocusChangeListener { _, has ->
            if (has) {
                strip.ensureVisible(item.view, true)
                if (style == "list") moveHighlight(item.view)
                onItemFocus?.invoke(item)
            } else {
                post {
                    if (tiles().none { it.view.isFocused }) {
                        if (style == "list") highlight.animate().alpha(0f).setDuration(Motion.MED).start()
                        // Focus left the side dock: the column settles back to the middle.
                        strip.centerBlock(true)
                    }
                }
            }
        }
        return item
    }

    /**
     * Shows [entries] plus the All apps tile. Existing tiles are reused and
     * slide to their new places; returns true on the very first fill.
     */
    fun bind(entries: List<AppEntry>): Boolean {
        val first = strip.childCount == 0
        val old = HashMap<String, DockItem>()
        for (it in items) it.entry?.let { e -> old[e.pkg] = it }
        val next = ArrayList<DockItem>()
        for (e in entries) {
            val item = old.remove(e.pkg) ?: newItem()
            if (item.entry !== e) item.bind(e)
            next.add(item)
        }
        val all = allApps ?: newItem().also { it.bindAllApps(allAppsText); allApps = it }
        val same = next == items && strip.childCount == next.size + 1
        items.clear()
        items.addAll(next)
        if (!same) {
            val views = (items + all).map { it.view }
            if (first) {
                for (v in views) strip.addView(v)
            } else {
                strip.reorder(views)
            }
        }
        return first
    }

    /** Tiles in focus order, the All apps tile last. */
    fun tiles(): List<DockItem> = items + listOfNotNull(allApps)

    fun itemOf(v: View?): DockItem? = tiles().firstOrNull { it.view === v }

    fun contains(v: View?): Boolean = v != null && itemOf(v) != null

    /** Moves focus [step] tiles along the dock; bumps at the ends. */
    fun move(from: View, step: Int): Boolean {
        val t = tiles()
        val i = t.indexOfFirst { it.view === from }
        if (i < 0) return false
        val n = i + step
        if (n < 0 || n >= t.size) {
            t[i].nudge(if (vertical) step else (if (layoutDirection == LAYOUT_DIRECTION_RTL) -step else step), vertical)
            return false
        }
        t[n].view.requestFocus()
        return true
    }

    fun focus(pkg: String): Boolean {
        val t = tiles()
        val target = t.firstOrNull { it.entry?.pkg == pkg } ?: t.firstOrNull() ?: return false
        return target.view.requestFocus()
    }

    fun cardReady(e: AppEntry) {
        items.firstOrNull { it.entry === e }?.cardReady()
    }

    fun scrollTo(v: View) = strip.ensureVisible(v, true)

    private fun moveHighlight(v: View) {
        // Where the line will be once the strip finishes scrolling.
        val x = glass.left + glass.paddingLeft + strip.left + v.left
        val y = glass.top + glass.paddingTop + strip.top + v.top + strip.scrollTarget
        if (highlight.alpha == 0f) {
            highlight.animate().cancel()
            highlight.translationX = x.toFloat()
            highlight.translationY = y
            highlight.animate().alpha(1f).setDuration(Motion.MED).start()
        } else {
            highlight.animate().translationX(x.toFloat()).translationY(y).alpha(1f)
                .setDuration(Motion.SLOW).setInterpolator(Motion.out).start()
        }
    }

    fun setFrost(b: Bitmap?) {
        frost.setImageBitmap(b)
        alignFrost()
    }

    /** Lines the blurred wallpaper up with the screen so the pill reads as glass. */
    private fun alignFrost() {
        val d = frost.drawable ?: return
        val loc = IntArray(2)
        glass.getLocationInWindow(loc)
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val sh = resources.displayMetrics.heightPixels.toFloat()
        frostMatrix.setScale(sw / d.intrinsicWidth, sh / d.intrinsicHeight)
        frostMatrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
        frost.imageMatrix = frostMatrix
    }

    /** The visible block of the dock, for the Customize frame. */
    fun outlineView(): View = glass

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Scaled about the screen edge it hugs and its own middle, so it stays centred and against the edge.
        pivotX = if (layoutDirection == LAYOUT_DIRECTION_RTL) width.toFloat() else 0f
        pivotY = height / 2f
    }

    companion object {
        /** Size of the dock in the side position, relative to the bottom one. */
        const val SIDE_SCALE = 0.8f
    }
}

/**
 * Sizes itself to the strip of tiles plus padding; the frosted image and the
 * sheen behind it are then stretched to exactly that size. A plain
 * FrameLayout would let those match-parent layers grow to the whole screen.
 */
class GlassBox(ctx: Context) : FrameLayout(ctx) {
    var content: View? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val c = content
        if (c == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val padW = paddingLeft + paddingRight
        val padH = paddingTop + paddingBottom
        c.measure(
            MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(widthMeasureSpec) - padW).coerceAtLeast(0), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(heightMeasureSpec) - padH).coerceAtLeast(0), MeasureSpec.AT_MOST),
        )
        val w = c.measuredWidth + padW
        val h = c.measuredHeight + padH
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v !== c) v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v === content) v.layout(paddingLeft, paddingTop, paddingLeft + v.measuredWidth, paddingTop + v.measuredHeight)
            else v.layout(0, 0, r - l, b - t)
        }
    }
}
