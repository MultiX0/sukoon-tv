package dev.multix.tvhome.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import dev.multix.tvhome.core.Motion

/**
 * A single row or column of tiles that may be longer than the screen.
 * Children keep their own size; scrolling is a translation of the strip,
 * which only moves the RenderNode and never re-records the tiles. The strip
 * measures to its content, capped at the space it is given.
 */
class Strip(ctx: Context, val vertical: Boolean, private val gap: Int, private val edge: Int, private val lookahead: Int) : ViewGroup(ctx) {
    private var target = 0f

    /** Scroll offset the strip is heading to (it may still be animating there). */
    val scrollTarget: Float get() = target

    /**
     * Side dock: the strip takes the whole height, the focused tile glides to the middle and the others fade a
     * little with their distance from it. Without focus the column sits centred as a block.
     */
    var centerLock = false

    /** Length of the tiles plus edges along the strip. */
    private var content = 0

    init {
        clipChildren = false
        clipToPadding = false
    }

    private fun main(v: View) = if (vertical) v.measuredHeight else v.measuredWidth
    private fun cross(v: View) = if (vertical) v.measuredWidth else v.measuredHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var total = edge * 2
        var crossMax = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams
            c.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY))
            total += main(c) + if (i > 0) gap else 0
            crossMax = maxOf(crossMax, cross(c))
        }
        content = total
        val spec = if (vertical) heightMeasureSpec else widthMeasureSpec
        val avail = MeasureSpec.getSize(spec)
        val mainSize = when {
            MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED -> total
            centerLock -> avail
            else -> minOf(total, avail)
        }
        if (vertical) setMeasuredDimension(crossMax, mainSize) else setMeasuredDimension(mainSize, crossMax)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = !vertical && layoutDirection == LAYOUT_DIRECTION_RTL
        val crossSize = if (vertical) r - l else b - t
        var x = if (rtl) (r - l) - edge else edge
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val m = main(c)
            val off = (crossSize - cross(c)) / 2
            if (vertical) {
                val start = if (layoutDirection == LAYOUT_DIRECTION_RTL) crossSize - c.measuredWidth else 0
                c.layout(start, x, start + c.measuredWidth, x + m)
                x += m + gap
            } else if (rtl) {
                c.layout(x - m, off, x, off + c.measuredHeight)
                x -= m + gap
            } else {
                c.layout(x, off, x + m, off + c.measuredHeight)
                x += m + gap
            }
        }
        if (centerLock && !hasFocus()) post { if (!hasFocus()) centerBlock(false) }
    }

    /** Centre-lock: the whole column centred in the strip, used while no tile has focus. */
    fun centerBlock(animate: Boolean) {
        if (!centerLock) return
        val size = if (vertical) height else width
        if (size == 0) return
        scrollTo((size - content) / 2f, animate)
    }

    private fun scrollTo(next: Float, animate: Boolean) {
        val size = if (vertical) height else width
        // A column longer than the screen fades gently with distance from the middle, so it ends softly.
        val mid = size / 2f
        val fade = content > size
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val center = (if (vertical) (c.top + c.bottom) else (c.left + c.right)) / 2f + next
            val d = (kotlin.math.abs(center - mid) / mid).coerceAtMost(1f)
            val a = if (fade) (1f - 0.7f * d * d).coerceAtLeast(0.2f) else 1f
            if (animate) c.animate().alpha(a).setDuration(Motion.SLOW).setInterpolator(Motion.out).start() else c.alpha = a
        }
        if (next == target && (if (vertical) translationY else translationX) == next) return
        target = next
        val an = animate()
        if (animate) {
            (if (vertical) an.translationY(next) else an.translationX(next))
                .setDuration(Motion.SLOW).setInterpolator(Motion.out).start()
        } else {
            an.cancel()
            if (vertical) translationY = next else translationX = next
        }
    }

    /** Scrolls just enough to keep [child] and a peek of its neighbour in view. */
    fun ensureVisible(child: View, animate: Boolean) {
        val size = if (vertical) height else width
        if (size == 0) return
        if (centerLock) {
            // A column that fits stays centred as a whole; a longer one keeps the focused tile in the middle.
            if (content <= size) {
                centerBlock(animate)
            } else {
                val c = (if (vertical) (child.top + child.bottom) else (child.left + child.right)) / 2f
                scrollTo(size / 2f - c, animate)
            }
            return
        }
        val i = indexOfChild(child)
        val rtl = !vertical && layoutDirection == LAYOUT_DIRECTION_RTL
        val hasBefore = i > 0
        val hasAfter = i < childCount - 1
        val lowPeek = if (if (rtl) hasAfter else hasBefore) lookahead else 0
        val highPeek = if (if (rtl) hasBefore else hasAfter) lookahead else 0
        val minX = (edge + lowPeek).toFloat()
        val maxX = (size - edge - highPeek).toFloat()
        val lo = (if (vertical) child.top else child.left) + target
        val hi = (if (vertical) child.bottom else child.right) + target
        var next = target
        if (lo < minX) next += minX - lo
        else if (hi > maxX) next -= hi - maxX
        // Never scroll past the start.
        next = if (rtl) maxOf(0f, next) else minOf(0f, next)
        if (next == target) return
        target = next
        val a = animate()
        if (animate) {
            (if (vertical) a.translationY(next) else a.translationX(next))
                .setDuration(Motion.SLOW).setInterpolator(Motion.out).start()
        } else {
            a.cancel()
            if (vertical) translationY = next else translationX = next
        }
    }

    fun resetScroll() {
        if (centerLock) {
            centerBlock(false)
            return
        }
        target = 0f
        animate().cancel()
        translationX = 0f
        translationY = 0f
    }

    /**
     * Reorders children and animates every tile from its old spot to its new
     * one (record positions, relayout, then play the difference back).
     */
    fun reorder(views: List<View>) {
        val before = HashMap<View, Int>()
        for (i in 0 until childCount) getChildAt(i).let { before[it] = if (vertical) it.top else it.left }
        removeAllViews()
        for (v in views) addView(v)
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                for (v in views) {
                    val old = before[v] ?: continue
                    val d = (old - if (vertical) v.top else v.left).toFloat()
                    if (d != 0f) {
                        if (vertical) v.translationY = d else v.translationX = d
                        v.animate().translationX(0f).translationY(0f).setDuration(Motion.SLOW).setInterpolator(Motion.out).start()
                    }
                }
                return true
            }
        })
    }
}

/**
 * Fixed-column grid. Like HRow it scrolls with a translation, here vertical,
 * so moving through a long list of apps never re-records the cards.
 */
class Grid(ctx: Context, private val cols: Int, private val cellW: Int, private val cellH: Int,
           private val gapX: Int, private val gapY: Int) : ViewGroup(ctx) {
    private var target = 0f
    var viewport = 0
    var topInset = 0

    init {
        clipChildren = false
        clipToPadding = false
    }

    val rowCount: Int get() = (childCount + cols - 1) / cols

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams
            c.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY))
        }
        val rows = rowCount
        val h = if (rows == 0) 0 else rows * cellH + (rows - 1) * gapY
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val total = cols * cellW + (cols - 1) * gapX
        val start = ((r - l) - total) / 2
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val col = i % cols
            val row = i / cols
            val visualCol = if (rtl) cols - 1 - col else col
            val x = start + visualCol * (cellW + gapX)
            val y = row * (cellH + gapY)
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }

    fun ensureRowVisible(row: Int, animate: Boolean) {
        if (viewport == 0) return
        val rowTop = row * (cellH + gapY)
        val rowBottom = rowTop + cellH
        var next = target
        val margin = gapY
        if (rowTop + next < 0) next = (-rowTop + 0).toFloat()
        if (rowBottom + next > viewport - margin) next = (viewport - margin - rowBottom).toFloat()
        if (row == 0) next = 0f
        if (next == target) return
        target = next
        if (animate) animate().translationY(next).setDuration(Motion.SLOW).setInterpolator(Motion.out).start()
        else { animate().cancel(); translationY = next }
    }

    fun resetScroll() {
        target = 0f
        animate().cancel()
        translationY = 0f
    }
}

@Suppress("unused")
private fun ViewGroup.children(): List<View> = (0 until childCount).map { getChildAt(it) }
