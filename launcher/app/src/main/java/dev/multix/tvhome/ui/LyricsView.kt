package dev.multix.tvhome.ui

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.multix.tvhome.core.Colors
import dev.multix.tvhome.core.Motion
import dev.multix.tvhome.core.SText
import dev.multix.tvhome.core.W
import dev.multix.tvhome.media.Lyrics

/**
 * Lyric lines that glide up as the song moves on, Apple Music style.
 *
 * A small pool of TextViews is assigned to the lines around the current one.
 * Every line is laid out once at the current-line size; dimmer lines are the
 * same view scaled down. A line change only animates translation, scale and
 * alpha, all RenderNode properties, inside a short-lived hardware layer.
 *
 * The card shows the current line and the next two, anchored at the top. The
 * full screen view shows a few lines either side, centred on the current one.
 */
class LyricsView(
    ctx: Context,
    private val before: Int,
    private val after: Int,
    private val centered: Boolean,
    private val dimScale: Float,
    private val alphas: FloatArray,
) : ViewGroup(ctx) {

    private val pool = ArrayList<SText>()
    private val assigned = HashMap<SText, Int>()
    private var lyrics: Lyrics? = null
    private var current = -1
    private var sizeSp = 24f
    var gap = 0
    var lineSpacingExtra = 0f

    init {
        clipChildren = true
        val n = before + after + 3
        repeat(n) {
            val t = SText(ctx, W.SEMIBOLD, sizeSp, Colors.WHITE)
            t.alpha = 0f
            t.maxLines = 2
            t.ellipsize = TextUtils.TruncateAt.END
            t.textAlignment = if (centered) View.TEXT_ALIGNMENT_CENTER else View.TEXT_ALIGNMENT_VIEW_START
            t.gravity = if (centered) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START
            pool.add(t)
            addView(t, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    override fun hasOverlappingRendering() = false

    fun setTextSize(sp: Float) {
        if (sp == sizeSp) return
        sizeSp = sp
        for (t in pool) {
            t.textSize = sp
            if (lineSpacingExtra > 0f) t.setLineSpacing(lineSpacingExtra, 1f)
        }
        requestLayout()
        post { place(false) }
    }

    fun setLyrics(l: Lyrics?, index: Int) {
        lyrics = l
        current = index
        assigned.clear()
        for (t in pool) {
            t.animate().cancel()
            t.alpha = 0f
            t.text = ""
        }
        place(false)
        if (l != null) {
            // Fade the whole block in rather than popping it.
            alpha = 0f
            animate().alpha(1f).setDuration(Motion.SLOW).setInterpolator(Motion.out).start()
        }
    }

    fun setIndex(index: Int, animate: Boolean) {
        if (index == current) return
        current = index
        place(animate && isShown)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        for (t in pool) {
            t.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (v in pool) v.layout(0, 0, r - l, v.measuredHeight)
        if (changed) place(false)
    }

    private fun measureLine(t: TextView): Int {
        val w = width
        if (w == 0) return 0
        t.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return t.measuredHeight
    }

    private fun textFor(i: Int): String {
        val s = lyrics?.line(i) ?: return ""
        return if (s.isEmpty()) "• • •" else s
    }

    /** Assigns pool views to lines around [current] and moves them into place. */
    private fun place(animate: Boolean) {
        val l = lyrics
        if (l == null || width == 0) {
            for (t in pool) t.alpha = 0f
            return
        }
        val lo = current - before - 1
        val hi = current + after + 1

        // Free views whose line scrolled out of the window.
        val free = ArrayList<SText>()
        for (t in pool) {
            val idx = assigned[t]
            if (idx == null || idx < lo || idx > hi || idx >= l.size || idx < -1) {
                assigned.remove(t)
                free.add(t)
            }
        }
        val byLine = HashMap<Int, SText>()
        for ((t, i) in assigned) byLine[i] = t

        // New lines enter from below (or above when scrolling backwards).
        for (i in lo..hi) {
            if (i < 0 || i >= l.size || byLine.containsKey(i)) continue
            val t = free.removeLastOrNull() ?: break
            t.animate().cancel()
            t.text = textFor(i)
            assigned[t] = i
            byLine[i] = t
            t.alpha = 0f
            t.tag = true // fresh: position before animating
        }
        for (t in free) {
            t.animate().cancel()
            t.alpha = 0f
        }

        // Heights at full size; slot positions follow from the scaled heights.
        val heights = HashMap<Int, Int>()
        for ((i, t) in byLine) heights[i] = measureLine(t)
        val h0 = (heights[current] ?: heights[current + 1] ?: 0).toFloat()
        val anchor = if (centered) (height - h0) / 2f else 0f

        val ys = HashMap<Int, Float>()
        ys[current] = anchor
        var y = anchor + h0 * scaleOf(current) + gap
        for (i in current + 1..hi) {
            ys[i] = y
            y += (heights[i] ?: 0) * scaleOf(i) + gap
        }
        y = anchor
        for (i in current - 1 downTo lo) {
            val h = (heights[i] ?: 0) * scaleOf(i)
            y -= h + gap
            ys[i] = y
        }

        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for ((i, t) in byLine) {
            val ty = ys[i] ?: continue
            val s = scaleOf(i)
            val a = alphaOf(i)
            t.pivotX = if (centered) width / 2f else if (rtl) width.toFloat() else 0f
            t.pivotY = 0f
            val fresh = t.tag == true
            t.tag = null
            if (!animate) {
                t.animate().cancel()
                t.translationY = ty
                t.scaleX = s
                t.scaleY = s
                t.alpha = a
                continue
            }
            if (fresh) {
                // Start just beyond the visible window and slide in with the rest.
                val dir = if (i > current) 1 else -1
                t.translationY = ty + dir * (heights[i] ?: 0) * 0.6f
                t.scaleX = s
                t.scaleY = s
                t.alpha = 0f
            }
            val rel = i - current
            val delay = if (rel >= 0) rel * 40L else 0L
            t.animate()
                .translationY(ty).scaleX(s).scaleY(s).alpha(a)
                .setStartDelay(delay)
                .setDuration(520)
                .setInterpolator(Motion.out)
                .withLayer()
                .start()
        }
    }

    private fun scaleOf(i: Int): Float = if (i == current) 1f else dimScale

    private fun alphaOf(i: Int): Float {
        val rel = i - current
        return when {
            rel == 0 -> alphas[0]
            rel > 0 -> if (rel > after) 0f else alphas.getOrElse(rel) { alphas.last() }
            else -> if (-rel > before) 0f else alphas.getOrElse(-rel) { alphas.last() } * 0.8f
        }
    }
}
