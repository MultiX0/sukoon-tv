package dev.multix.tvhome.core

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

object Ui {
    var density = 1f
        private set

    fun init(ctx: Context) {
        density = ctx.resources.displayMetrics.density
    }
}

val Int.dp: Int get() = (this * Ui.density + 0.5f).toInt()
val Float.dp: Float get() = this * Ui.density

/** Shared colours. The UI is always light text on a dark, image based scene. */
object Colors {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val TEXT2 = 0xD9FFFFFF.toInt()
    const val TEXT3 = 0x99FFFFFF.toInt()
    const val TEXT4 = 0x66FFFFFF.toInt()
    const val INK = 0xFF0E0F12.toInt()
    const val INK2 = 0xB30E0F12.toInt()
    const val GLASS = 0x29FFFFFF
    const val GLASS_STRONG = 0x3DFFFFFF
    const val PANEL = 0xD914161A.toInt()
    const val DEFAULT_ACCENT = 0xFF8FA8FF.toInt()
}

/**
 * Motion curves. Everything settles with a decelerating curve; focus scale
 * uses a lightly damped spring so it feels physical without wobbling.
 */
object Motion {
    val out: Interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    val inOut: Interpolator = PathInterpolator(0.45f, 0f, 0.2f, 1f)
    val accel: Interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
    val spring: Interpolator = Spring(0.78f, 8f)

    const val FAST = 200L
    const val MED = 300L
    const val SLOW = 400L
}

class Spring(private val zeta: Float, private val omega: Float) : Interpolator {
    private val wd = omega * sqrt(1f - zeta * zeta)
    private val k = zeta * omega / wd

    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        val e = exp(-zeta * omega * t)
        return 1f - e * (cos(wd * t) + k * sin(wd * t))
    }
}

class RoundOutline(private val radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}

fun View.roundClip(radius: Float) {
    outlineProvider = RoundOutline(radius)
    clipToOutline = true
}

fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

fun frame(w: Int, h: Int, gravity: Int = -1): FrameLayout.LayoutParams =
    FrameLayout.LayoutParams(w, h, gravity)

fun FrameLayout.LayoutParams.margins(start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0): FrameLayout.LayoutParams {
    marginStart = start
    topMargin = top
    marginEnd = end
    bottomMargin = bottom
    return this
}

fun lerpColor(a: Int, b: Int, t: Float): Int {
    val aa = a ushr 24; val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
    val ba = b ushr 24; val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
    return ((aa + (ba - aa) * t).toInt() shl 24) or
        ((ar + (br - ar) * t).toInt() shl 16) or
        ((ag + (bg - ag) * t).toInt() shl 8) or
        (ab + (bb - ab) * t).toInt()
}

fun withAlpha(color: Int, alpha: Float): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24)
