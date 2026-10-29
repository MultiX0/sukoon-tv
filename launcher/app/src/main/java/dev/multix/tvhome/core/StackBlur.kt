package dev.multix.tvhome.core

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stack blur (Mario Klingemann's algorithm). RenderEffect only exists from
 * API 31, so backgrounds are blurred once on the CPU from a tiny bitmap and
 * stretched by the GPU. On a 64x64 input this takes well under a millisecond.
 */
object StackBlur {
    fun blur(src: Bitmap, radius: Int): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return bmp
        val w = bmp.width
        val h = bmp.height
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val div = radius + radius + 1
        val r = IntArray(w * h)
        val g = IntArray(w * h)
        val b = IntArray(w * h)
        val vmin = IntArray(max(w, h))
        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum) { it / divsum }
        val stack = Array(div) { IntArray(3) }
        val r1 = radius + 1

        var yw = 0
        var yi = 0
        for (y in 0 until h) {
            var rin = 0; var gin = 0; var bin = 0
            var rout = 0; var gout = 0; var bout = 0
            var rsum = 0; var gsum = 0; var bsum = 0
            for (i in -radius..radius) {
                val p = pix[yi + min(wm, max(i, 0))]
                val sir = stack[i + radius]
                sir[0] = (p shr 16) and 0xff
                sir[1] = (p shr 8) and 0xff
                sir[2] = p and 0xff
                val rbs = r1 - abs(i)
                rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs
                if (i > 0) { rin += sir[0]; gin += sir[1]; bin += sir[2] }
                else { rout += sir[0]; gout += sir[1]; bout += sir[2] }
            }
            var sp = radius
            for (x in 0 until w) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum]
                rsum -= rout; gsum -= gout; bsum -= bout
                var sir = stack[(sp - radius + div) % div]
                rout -= sir[0]; gout -= sir[1]; bout -= sir[2]
                if (y == 0) vmin[x] = min(x + radius + 1, wm)
                val p = pix[yw + vmin[x]]
                sir[0] = (p shr 16) and 0xff
                sir[1] = (p shr 8) and 0xff
                sir[2] = p and 0xff
                rin += sir[0]; gin += sir[1]; bin += sir[2]
                rsum += rin; gsum += gin; bsum += bin
                sp = (sp + 1) % div
                sir = stack[sp]
                rout += sir[0]; gout += sir[1]; bout += sir[2]
                rin -= sir[0]; gin -= sir[1]; bin -= sir[2]
                yi++
            }
            yw += w
        }

        for (x in 0 until w) {
            var rin = 0; var gin = 0; var bin = 0
            var rout = 0; var gout = 0; var bout = 0
            var rsum = 0; var gsum = 0; var bsum = 0
            var yp = -radius * w
            for (i in -radius..radius) {
                yi = max(0, yp) + x
                val sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                val rbs = r1 - abs(i)
                rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs
                if (i > 0) { rin += sir[0]; gin += sir[1]; bin += sir[2] }
                else { rout += sir[0]; gout += sir[1]; bout += sir[2] }
                if (i < hm) yp += w
            }
            yi = x
            var sp = radius
            for (y in 0 until h) {
                pix[yi] = (0xff shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= rout; gsum -= gout; bsum -= bout
                var sir = stack[(sp - radius + div) % div]
                rout -= sir[0]; gout -= sir[1]; bout -= sir[2]
                if (x == 0) vmin[y] = min(y + r1, hm) * w
                val p = x + vmin[y]
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
                rin += sir[0]; gin += sir[1]; bin += sir[2]
                rsum += rin; gsum += gin; bsum += bin
                sp = (sp + 1) % div
                sir = stack[sp]
                rout += sir[0]; gout += sir[1]; bout += sir[2]
                rin -= sir[0]; gin -= sir[1]; bin -= sir[2]
                yi += w
            }
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h)
        return bmp
    }
}
