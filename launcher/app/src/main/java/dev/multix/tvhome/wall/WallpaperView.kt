package dev.multix.tvhome.wall

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import dev.multix.tvhome.core.Motion
import kotlin.random.Random

/**
 * Two stacked image layers. A new image fades in on the top layer while the
 * old one stays put underneath, then the old layer lets go of its bitmap.
 *
 * The slow drift only animates RenderNode properties (scale and translation),
 * so the bitmap is uploaded to the GPU once and never redrawn on the CPU.
 */
class WallpaperView(ctx: Context) : FrameLayout(ctx) {

    private class Layer(ctx: Context) : ImageView(ctx) {
        var drift: ObjectAnimator? = null

        init {
            scaleType = ScaleType.CENTER_CROP
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun hasOverlappingRendering() = false
    }

    private val a = Layer(ctx)
    private val b = Layer(ctx)
    private var front: Layer = a
    private var running = false

    var current: Bitmap? = null
        private set

    var motion = true
        set(v) {
            if (field == v) return
            field = v
            if (v) startDrift(front) else settle(front)
        }

    init {
        addView(a, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(b, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        a.alpha = 0f
        b.alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun hasOverlappingRendering() = false

    /** Darkens the photo with a colour filter on the image itself: no extra full screen layer. */
    fun setDim(f: Float) {
        val filter = if (f <= 0f) null else {
            val m = (255 * (1f - f)).toInt().coerceIn(0, 255)
            android.graphics.LightingColorFilter((0xFF shl 24) or (m shl 16) or (m shl 8) or m, 0)
        }
        a.colorFilter = filter
        b.colorFilter = filter
    }

    fun show(bmp: Bitmap, fadeMs: Long) {
        if (bmp === current) return
        val incoming = if (front === a) b else a
        val outgoing = front
        incoming.animate().cancel()
        incoming.drift?.cancel()
        incoming.setImageBitmap(bmp)
        incoming.alpha = 0f
        incoming.bringToFront()
        front = incoming
        current = bmp
        if (motion) startDrift(incoming) else reset(incoming)
        incoming.animate()
            .alpha(1f)
            .setDuration(fadeMs)
            .setInterpolator(Motion.inOut)
            .withEndAction {
                outgoing.drift?.cancel()
                outgoing.drift = null
                outgoing.setImageDrawable(null)
                outgoing.alpha = 0f
            }
            .start()
    }

    /** Called from onStart/onStop so the drift never renders frames nobody sees. */
    fun setRunning(run: Boolean) {
        running = run
        for (l in arrayOf(a, b)) {
            val d = l.drift ?: continue
            if (run) d.resume() else d.pause()
        }
    }

    private fun startDrift(l: Layer) {
        l.drift?.cancel()
        val w = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
        val h = (if (height > 0) height else resources.displayMetrics.heightPixels).toFloat()
        val zoomIn = Random.nextBoolean()
        val s0 = if (zoomIn) 1.02f else 1.10f
        val s1 = if (zoomIn) 1.10f else 1.02f
        fun limit(s: Float, size: Float) = (s - 1f) * size / 2f * 0.9f
        fun pick(s: Float, size: Float): Float {
            val m = limit(s, size)
            return (Random.nextFloat() * 2f - 1f) * m
        }
        l.pivotX = w / 2f
        l.pivotY = h / 2f
        val anim = ObjectAnimator.ofPropertyValuesHolder(
            l,
            PropertyValuesHolder.ofFloat(View.SCALE_X, s0, s1),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, s0, s1),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, pick(s0, w), pick(s1, w)),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, pick(s0, h), pick(s1, h)),
        )
        anim.duration = DRIFT_MS
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.repeatCount = ValueAnimator.INFINITE
        anim.repeatMode = ValueAnimator.REVERSE
        l.drift = anim
        anim.start()
        if (!running) anim.pause()
    }

    private fun settle(l: Layer) {
        l.drift?.cancel()
        l.drift = null
        l.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(900).setInterpolator(Motion.inOut).start()
    }

    private fun reset(l: Layer) {
        l.scaleX = 1f
        l.scaleY = 1f
        l.translationX = 0f
        l.translationY = 0f
    }

    companion object {
        /** One leg of the drift. About 2 px per second at 720p: calm, but alive. */
        const val DRIFT_MS = 45_000L
    }
}
