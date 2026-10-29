package dev.multix.tvhome.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import dev.multix.tvhome.core.Images
import dev.multix.tvhome.media.Lyrics
import dev.multix.tvhome.media.Track

/**
 * Sample songs for previewing and recording the full screen lyrics without
 * playing anything. The words are written for this launcher and the covers
 * are generated, so nothing here belongs to anyone else. Each has a chorus
 * (repeated lines) and ends on an instrumental gap.
 */
object Samples {
    private class Song(val title: String, val artist: String, val a: Int, val b: Int, val lines: Array<String>)

    private val songs = arrayOf(
        Song("Glass Harbor", "Mira Vale", 0xFF0F6E74.toInt(), 0xFFE9C891.toInt(), arrayOf(
            "Lights on the water, slow and wide",
            "I keep your name on the evening tide",
            "Hold on, hold on to the glass harbor",
            "Hold on, hold on, we are almost home",
            "Salt in the wind and a quiet sky",
            "Every wave says the night will be kind",
            "Hold on, hold on to the glass harbor",
            "Hold on, hold on, we are almost home",
        )),
        Song("Low Orbit", "Kaito North", 0xFF10204A.toInt(), 0xFFF08A3C.toInt(), arrayOf(
            "Counting satellites up on the roof",
            "Every signal sounds a little like you",
            "We go round and round in a low orbit",
            "We go round and round and never fall",
            "Radio static and a borrowed coat",
            "All of the city is an open road",
            "We go round and round in a low orbit",
            "We go round and round and never fall",
        )),
        Song("نجمة الشمال", "ليان", 0xFF5B1A5E.toInt(), 0xFFE86FA6.toInt(), arrayOf(
            "على باب الليل وقفت أستنى",
            "والقمر يحكي لي عنك شوي",
            "يا نجمة الشمال دلّيني",
            "يا نجمة الشمال خذيني إليه",
            "والريح تمر وتسأل عنك",
            "وأنا أقول لها بكرة يجي",
            "يا نجمة الشمال دلّيني",
            "يا نجمة الشمال خذيني إليه",
        )),
        Song("Paper Lanterns", "Odette Rue", 0xFF7A2E0E.toInt(), 0xFFF4C25B.toInt(), arrayOf(
            "Paper lanterns over Mercer Street",
            "Your coat is warm and the air is sweet",
            "Let them rise, let them rise",
            "Little fires in the winter skies",
            "Somebody laughing on a fire escape",
            "We are the music that the evening makes",
            "Let them rise, let them rise",
            "Little fires in the winter skies",
        )),
    )

    val count: Int get() = songs.size

    /** Line spacing of the samples; the last line is followed by a 7 s gap. */
    private const val STEP = 3600L

    fun make(n: Int): Track {
        val s = songs[Math.floorMod(n, songs.size)]
        val lines = s.lines + ""
        val times = LongArray(lines.size) { it * STEP }
        val t = Track("demo", "demo", s.title, s.artist, "", lines.size * STEP + 7000)
        t.lyrics = Lyrics(times, lines, true)
        t.lyricsDone = true
        t.art = cover(s.a, s.b)
        val sw = Images.swatches(t.art!!)
        t.accent = sw.accent
        t.dark = sw.dark
        t.vibrant = sw.vibrant
        t.artDone = true
        return t
    }

    /** A soft two-colour cover with a light spot, different for every sample. */
    private fun cover(a: Int, b: Int): Bitmap {
        val size = 384
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), a, b, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = RadialGradient(size * 0.7f, size * 0.32f, size * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        return bmp
    }
}
