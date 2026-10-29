package dev.multix.tvhome.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.palette.graphics.Palette
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Images {

    /**
     * Decodes an image so that it just covers [tw]x[th], then centre-crops it to
     * at most that size. inSampleSize does the coarse power-of-two reduction in
     * the decoder; inDensity/inTargetDensity does the exact final step without
     * a second full-size bitmap.
     */
    fun decodeCover(open: () -> InputStream?, tw: Int, th: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null, so only the stream itself is checked.
        val probe = open() ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        val scale = min(1f, max(tw.toFloat() / w, th.toFloat() / h))
        val needW = ceil(w * scale).toInt()
        val needH = ceil(h * scale).toInt()
        var sample = 1
        while (w / (sample * 2) >= needW && h / (sample * 2) >= needH) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            val sampledW = w / sample
            if (sampledW > needW) {
                inScaled = true
                inDensity = sampledW
                inTargetDensity = needW
            }
        }
        val bmp = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        if (bmp.width <= tw && bmp.height <= th) return bmp
        val cw = min(tw, bmp.width)
        val ch = min(th, bmp.height)
        val cropped = Bitmap.createBitmap(bmp, (bmp.width - cw) / 2, (bmp.height - ch) / 2, cw, ch)
        if (cropped !== bmp) bmp.recycle()
        return cropped
    }

    /** Scales [src] to cover w x h and returns a bitmap with antialiased round corners. */
    fun roundedCover(src: Bitmap, w: Int, h: Int, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
        }
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(m)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        p.shader = shader
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, p)
        return out
    }

    /** Centre-crops to a square of [size] pixels. */
    fun square(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = max(size.toFloat() / src.width, size.toFloat() / src.height)
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((size - src.width * s) / 2f, (size - src.height * s) / 2f)
        }
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        return out
    }

    /** A soft white rounded rectangle on a transparent margin, used for focus glows and shadows. */
    fun glow(w: Int, h: Int, radius: Float, blur: Float): Bitmap {
        val pad = ceil(blur * 2f).toInt()
        val out = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat()), radius, radius, p)
        return out
    }

    /** Downscales and blurs; the GPU later stretches the tiny result smoothly. */
    fun blurred(src: Bitmap, w: Int, h: Int, radius: Int): Bitmap {
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val out = StackBlur.blur(small, radius)
        if (small !== src) small.recycle()
        return out
    }

    class Swatches(val accent: Int, val dark: Int, val vibrant: Int)

    fun swatches(src: Bitmap): Swatches {
        val p = Palette.from(src).maximumColorCount(16).resizeBitmapArea(64 * 64).generate()
        val dominant = p.getDominantColor(Colors.DEFAULT_ACCENT)
        val vibrant = p.getVibrantColor(p.getLightVibrantColor(p.getMutedColor(dominant)))
        val dark = p.getDarkMutedColor(p.getDarkVibrantColor(darken(dominant)))
        return Swatches(accentOf(vibrant), dark, vibrant)
    }

    /** Pushes a colour into a range that reads as a glow on a dark scene. */
    fun accentOf(color: Int): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)
        if (hsl[1] > 0.12f) hsl[1] = max(hsl[1], 0.55f)
        hsl[2] = hsl[2].coerceIn(0.56f, 0.68f)
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }

    private fun darken(color: Int): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)
        hsl[2] = min(hsl[2], 0.22f)
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }

    fun deepen(color: Int, lightness: Float, maxSat: Float = 0.6f): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)
        hsl[1] = min(hsl[1], maxSat)
        hsl[2] = lightness
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }

    fun luminance(color: Int): Float =
        androidx.core.graphics.ColorUtils.calculateLuminance(color).toFloat()

    fun roundPx(v: Float): Int = v.roundToInt()
}
