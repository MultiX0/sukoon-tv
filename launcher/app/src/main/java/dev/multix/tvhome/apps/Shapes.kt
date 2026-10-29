package dev.multix.tvhome.apps

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Icon outlines for the dock: squircle (superellipse), circle and rounded square. */
object Shapes {
    fun path(shape: String, size: Float): Path {
        val p = Path()
        val r = size / 2f
        when (shape) {
            "circle" -> p.addCircle(r, r, r, Path.Direction.CW)
            "rounded" -> p.addRoundRect(0f, 0f, size, size, size * 0.22f, size * 0.22f, Path.Direction.CW)
            else -> {
                // |x|^n + |y|^n = 1 with n = 5 is close to the iOS icon outline.
                val n = 5.0
                val steps = 96
                for (i in 0..steps) {
                    val t = 2.0 * Math.PI * i / steps
                    val c = cos(t)
                    val s = sin(t)
                    val x = r + r * sign(c) * abs(c).pow(2.0 / n)
                    val y = r + r * sign(s) * abs(s).pow(2.0 / n)
                    if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
                }
                p.close()
            }
        }
        return p
    }
}
