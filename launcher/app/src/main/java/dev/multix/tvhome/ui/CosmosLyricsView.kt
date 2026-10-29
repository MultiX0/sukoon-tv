package dev.multix.tvhome.ui

import android.os.Build
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import dev.multix.tvhome.core.Bg
import dev.multix.tvhome.core.Fonts
import dev.multix.tvhome.core.Images
import dev.multix.tvhome.core.Lang
import dev.multix.tvhome.core.Prefs
import dev.multix.tvhome.core.W
import dev.multix.tvhome.core.dp
import dev.multix.tvhome.core.lerpColor
import dev.multix.tvhome.media.Lyrics
import dev.multix.tvhome.media.NowPlaying
import dev.multix.tvhome.media.Track
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Full-screen lyrics where every song gets its own picture.
 *
 * A song's signature is a hash of its title and artist. It picks one motif
 * (silk lines, breathing rings, a spectrum halo or a topographic map), all
 * of that motif's geometry and the type pairing, so a song always looks the
 * same and two songs never match. Under the motif is a colour field: the
 * cover shrunk to 8x5 pixels and stretched by the GPU, two copies slowly
 * turning and crossfading. The music (bass, vocals, highs, tempo) and the
 * lyrics (chorus, instrumental gaps) bend the motif; with no audio data it
 * keeps moving slowly on the seed alone.
 *
 * The lyrics sit on a turning barrel with a karaoke sweep on the current
 * line. Every line is pre-rendered off the main thread into an alpha-only
 * texture; a frame only draws bitmaps through matrices plus a few hundred
 * hairline segments. onDraw allocates nothing and runs only while started.
 */
class CosmosLyricsView(ctx: Context) : View(ctx) {

    /** An alpha-only rendering of one lyric line plus its word geometry. */
    private class LineTex(
        val mask: Bitmap,
        /** The same text blurred, drawn dark under the current line for legibility. */
        val shadow: Bitmap?,
        val shadowPad: Float,
        val rtl: Boolean,
        val wordStart: FloatArray,
        val wordEnd: FloatArray,
        val share: FloatArray,
    ) {
        val w get() = mask.width
        val h get() = mask.height
    }

    /** Everything that makes one song look like itself. */
    private class Sig(
        val seed: Int,
        val motif: Int,
        val field: Bitmap,
        val light: Int,
        val p: FloatArray,
        val latin: Typeface,
        val arabic: Typeface,
        val cover: Bitmap?,
        /** Darkening filters for the field: index 0 is the base, the last is 6 % brighter. */
        val filters: Array<LightingColorFilter>,
    )

    // Song state
    private var track: Track? = null
    private var lyr: Lyrics? = null
    private var chorus = BooleanArray(0)
    private var index = -1
    private var playing = false
    private var wallpaper: Bitmap? = null
    private var artTile: Bitmap? = null
    private var sig: Sig? = null
    private var oldSig: Sig? = null
    private var sigStart = 0L
    private var running = false
    private var demoStart = 0L
    private var demoLoop = 25_000L
    private var textPx = 0f
    private var lineRhythmBpm = 100f

    // Lines
    private val lines = HashMap<Int, LineTex>()
    private val pending = HashSet<Int>()
    private var titleTex: LineTex? = null
    private var introStart = 0L
    private var title = ""

    // Motion state
    private var lastFrame = 0L
    private var pos = 0f
    private var vel = 0f
    private var chorusMix = 0f
    private var gapMix = 0f
    private var vocalMix = 0f
    private var edgeAmp = 1f
    private var edgeBass = 0f
    private var edgeT = 0f
    /** Field brightness, raised gently by the bass with a slow release. */
    private var glow = 0f

    // Geometry buffer for the edge spectrum, made once.
    private val seg = FloatArray(Beat.BANDS * 4)

    // Drawing objects, made once.
    private val m = Matrix()
    private val fieldMatrix = Matrix()
    private val dst = RectF()
    private val fieldPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val coverPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.BLACK }
    private val sungPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sweepMatrix = Matrix()
    private val sweepLtr = LinearGradient(0f, 0f, 1f, 0f, 0xFFFFFFFF.toInt(), 0x73FFFFFF, Shader.TileMode.CLAMP)
    private val sweepRtl = LinearGradient(0f, 0f, 1f, 0f, 0x73FFFFFF, 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt() }
    private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt() }
    private val barTrack = Paint().apply { color = 0x33FFFFFF }
    private val barFill = Paint()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val vignettePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var cornerTitle = ""
    private var cornerArtist = ""
    private var cornerFor: Track? = null
    private var clockText = ""
    private var clockMinute = -1L
    private val clockFormat = SimpleDateFormat(if (Prefs.clock24(ctx)) "HH:mm" else "h:mm", Lang.locale())
    private val clockDate = Date()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        applyTextSize()
    }

    override fun hasOverlappingRendering() = false

    fun applyTextSize() {
        val sp = when (Prefs.lyricsSize) { 0 -> 40f; 2 -> 52f; else -> 46f }
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        if (px == textPx) return
        textPx = px
        lines.clear()
        prepareWindow()
    }

    fun setWallpaper(b: Bitmap?) {
        wallpaper = b
    }

    // Binding, the same surface the home screen and the screensaver use.

    fun bind(t: Track?) {
        if (t == null) return
        val fresh = track?.key != t.key || track?.title != t.title
        track = t
        if (fresh) {
            if (t.key == "demo") demoStart = SystemClock.uptimeMillis()
            title = t.title
            lines.clear()
            pending.clear()
            titleTex = null
            index = if (t.key == "demo") 0 else NowPlaying.line
            pos = index.toFloat()
            vel = 0f
            makeSignature(t)
        }
        bindArt(t)
        bindLyrics(t)
        playing = NowPlaying.playing || t.key == "demo"
    }

    fun bindArt(t: Track) {
        if (t !== track) return
        barFill.color = t.accent
        val art = t.art ?: return
        Bg.load({ Images.roundedCover(art, 64.dp, 64.dp, 10f.dp) }) { tile -> if (track === t) artTile = tile }
        // A cover that arrives late still deserves its own colours.
        val s = sig
        if (s != null && s.cover == null && !signing) makeSignature(t)
    }

    fun bindLyrics(t: Track) {
        if (t !== track) return
        val l = t.lyrics
        val next = if (Prefs.lyricsOn && l != null && l.synced) l else null
        if (next !== lyr) {
            lyr = next
            lines.clear()
            pending.clear()
            index = if (t.key == "demo") 0 else NowPlaying.line
            pos = index.toFloat()
            chorus = findChorus(next)
            lineRhythmBpm = rhythmOf(next)
            if (next != null && t.key == "demo") demoLoop = next.times.last() + 7000
        }
        prepareWindow()
    }

    fun onLine(i: Int, animate: Boolean) {
        if (track?.key == "demo") return
        setIndex(i, animate)
    }

    private fun setIndex(i: Int, animate: Boolean) {
        if (i == index) return
        index = i
        if (!animate) {
            pos = i.toFloat()
            vel = 0f
        }
        prepareWindow()
    }

    fun onPlayback(p: Boolean) {
        playing = p || track?.key == "demo"
    }

    fun start() {
        if (running) return
        running = true
        Beat.acquire(context)
        lastFrame = SystemClock.uptimeMillis()
        if (vignette == null) Bg.load({ makeVignette(160, 90) }) { v -> vignette = v; invalidate() }
        postInvalidateOnAnimation()
    }

    fun stop() {
        if (!running) return
        running = false
        Beat.release()
    }

    fun release() {
        stop()
        lines.clear()
        titleTex = null
    }

    // Signature

    private var signing = false

    private fun fnv(s: String): Int {
        var h = 0x811C9DC5.toInt()
        for (ch in s) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return h
    }

    /** Builds the song's look on the background thread; the previous one crossfades out over 2 s. */
    private fun makeSignature(t: Track) {
        val seed = fnv("${t.title.lowercase()}|${t.artist.lowercase()}")
        val art = if (Prefs.ambientBg == "wall") wallpaper ?: t.art else t.art
        val ctx = context
        val pairing = Math.floorMod(seed ushr 7, 3)
        val latin = when (pairing) {
            1 -> Fonts.of(ctx, Fonts.clock.first { it.id == "dm_serif" }, W.REGULAR)
            2 -> Fonts.of(ctx, Fonts.clock.first { it.id == "space" }, W.MEDIUM)
            else -> Fonts.of(ctx, Fonts.INTER, W.SEMIBOLD)
        }
        val arabic = when (pairing) {
            1 -> Fonts.of(ctx, Fonts.uiAr.first { it.id == "readex" }, W.MEDIUM)
            else -> Fonts.of(ctx, Fonts.PLEX, if (pairing == 2) W.MEDIUM else W.SEMIBOLD)
        }
        val vibrant = t.vibrant
        val cover = t.art
        signing = true
        Bg.load({
            val r = Random(seed)
            val field: Bitmap
            var lum = 0.3f
            if (art != null) {
                val mid = Bitmap.createScaledBitmap(art, 64, 40, true)
                field = Bitmap.createScaledBitmap(mid, 8, 5, true)
                if (mid !== art) mid.recycle()
                var sum = 0f
                for (y in 0 until 5) for (x in 0 until 8) sum += Images.luminance(field.getPixel(x, y))
                lum = sum / 40f
            } else {
                // No cover: a field from the seed itself.
                field = Bitmap.createBitmap(8, 5, Bitmap.Config.ARGB_8888)
                val h0 = r.nextFloat() * 360f
                for (y in 0 until 5) for (x in 0 until 8) {
                    val hue = (h0 + x * 9f + y * 14f) % 360f
                    field.setPixel(x, y, Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.32f + 0.08f * y)))
                }
            }
            // Darker covers lean to rings and the halo; brighter ones to silk and contours.
            val order = if (lum < 0.22f) intArrayOf(1, 2, 0, 3) else intArrayOf(0, 3, 1, 2)
            val motif = order[Math.floorMod(seed, 4)]
            val p = FloatArray(16) { r.nextFloat() }
            val light = Images.deepen(vibrant, 0.86f, 0.45f)
            val big = cover?.let { Images.roundedCover(it, 300.dp, 300.dp, 16f.dp) }
            // Scale the field so its brightest cell sits near 35 % luminance.
            var maxLum = 0.05f
            for (y in 0 until 5) for (x in 0 until 8) maxLum = max(maxLum, Images.luminance(field.getPixel(x, y)))
            val baseMul = min(1f, 0.35f / maxLum)
            val filters = Array(8) { k ->
                val v = (255 * min(1f, baseMul * (1f + 0.06f * k / 7f))).toInt().coerceIn(0, 255)
                LightingColorFilter((0xFF shl 24) or (v shl 16) or (v shl 8) or v, 0)
            }
            Sig(seed, motif, field, light, p, latin, arabic, big, filters)
        }) { s ->
            signing = false
            if (track !== t) return@load
            oldSig = sig
            sig = s
            sigStart = SystemClock.uptimeMillis()
            introStart = sigStart
            lines.clear()
            pending.clear()
            prepareTitle(s)
            prepareWindow()
        }
    }

    private fun findChorus(l: Lyrics?): BooleanArray {
        if (l == null) return BooleanArray(0)
        val norm = Array(l.size) { i -> l.lines[i].lowercase().filter { it.isLetterOrDigit() } }
        val counts = HashMap<String, Int>()
        for (s in norm) if (s.length >= 6) counts[s] = (counts[s] ?: 0) + 1
        return BooleanArray(l.size) { (counts[norm[it]] ?: 0) >= 2 }
    }

    /** Tempo from the lyric rhythm when there is no audio: about eight beats a line. */
    private fun rhythmOf(l: Lyrics?): Float {
        if (l == null || l.size < 3) return 100f
        val gaps = ArrayList<Long>()
        for (i in 1 until l.size) {
            val d = l.times[i] - l.times[i - 1]
            if (d in 1200..12_000) gaps.add(d)
        }
        if (gaps.isEmpty()) return 100f
        gaps.sort()
        val median = gaps[gaps.size / 2].toFloat()
        var bpm = 60_000f / (median / 8f)
        while (bpm > 160f) bpm /= 2f
        while (bpm < 70f) bpm *= 2f
        return bpm
    }

    // Texture preparation, off the main thread.

    private fun prepareWindow() {
        val l = lyr ?: return
        val s = sig ?: return
        val lo = max(0, index - 3)
        val hi = min(l.size - 1, index + 4)
        val it = lines.keys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k < lo - 1 || k > hi + 1) it.remove()
        }
        val px = textPx
        val maxW = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        for (i in lo..hi) {
            if (lines.containsKey(i) || pending.contains(i)) continue
            val text = l.line(i) ?: continue
            if (text.isEmpty()) continue
            pending.add(i)
            val words = l.words[i]
            Bg.load({ build(text, s, px, maxW, i >= index, words?.offsets) }) { tex ->
                pending.remove(i)
                if (lyr === l && sig === s) lines[i] = tex
            }
        }
    }

    private fun prepareTitle(s: Sig) {
        val t = title
        if (t.isEmpty()) return
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 52f, resources.displayMetrics)
        val maxW = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        Bg.load({ build(t, s, px, maxW, false, null) }) { tex -> if (sig === s) titleTex = tex }
    }

    private fun build(text: String, s: Sig, size: Float, maxW: Int, withShadow: Boolean, wordOffsets: IntArray?): LineTex {
        val rtl = isRtl(text)
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = if (rtl || hasArabic(text)) s.arabic else s.latin
        p.textSize = size
        var width = p.measureText(text)
        if (width > maxW) {
            p.textSize = size * maxW / width
            width = p.measureText(text)
        }
        val fm = p.fontMetricsInt
        val pad = (size * 0.25f).toInt()
        val bw = width.toInt() + pad * 2 + 2
        val bh = fm.descent - fm.ascent + pad * 2
        val mask = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
        Canvas(mask).drawText(text, pad.toFloat(), (pad - fm.ascent).toFloat(), p)
        var shadow: Bitmap? = null
        val spad = size * 0.4f
        if (withShadow) {
            val g = Bitmap.createBitmap((bw + spad * 2).toInt(), (bh + spad * 2).toInt(), Bitmap.Config.ALPHA_8)
            val gp = TextPaint(p)
            gp.maskFilter = BlurMaskFilter(size * 0.22f, BlurMaskFilter.Blur.NORMAL)
            Canvas(g).drawText(text, pad + spad, pad - fm.ascent + spad, gp)
            shadow = g
        }
        val starts = ArrayList<Int>()
        if (wordOffsets != null && wordOffsets.isNotEmpty()) {
            for (o in wordOffsets) starts.add(o.coerceIn(0, text.length))
        } else {
            var i = 0
            while (i < text.length) {
                while (i < text.length && text[i] == ' ') i++
                if (i < text.length) starts.add(i)
                while (i < text.length && text[i] != ' ') i++
            }
        }
        if (starts.isEmpty()) starts.add(0)
        val k = starts.size
        val ws = FloatArray(k)
        val we = FloatArray(k)
        val share = FloatArray(k)
        val total = text.length.coerceAtLeast(1).toFloat()
        for (j in 0 until k) {
            val a = starts[j]
            val b = if (j + 1 < k) starts[j + 1] else text.length
            val xa = p.measureText(text, 0, a)
            val xb = p.measureText(text, 0, b)
            if (rtl) {
                ws[j] = pad + width - xa
                we[j] = pad + width - xb
            } else {
                ws[j] = pad + xa
                we[j] = pad + xb
            }
            share[j] = b / total
        }
        return LineTex(mask, shadow, spad, rtl, ws, we, share)
    }

    private fun isRtl(s: String): Boolean {
        for (ch in s) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            }
        }
        return false
    }

    private fun hasArabic(s: String): Boolean = s.any { it.code in 0x0600..0x06FF }

    // Frame

    private fun position(now: Long): Long =
        if (track?.key == "demo") (now - demoStart) % demoLoop else NowPlaying.position()

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame).coerceIn(0, 50)) / 1000f
        lastFrame = now
        val t = now / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.47f
        val l = lyr
        val songPos = position(now)
        if (track?.key == "demo" && l != null) setIndex(l.indexAt(songPos + 150), true)

        // Sound, or the seed and the lyric rhythm when there is none.
        val live = Beat.live
        val bpm = if (live && Beat.bpm > 0f) Beat.bpm else lineRhythmBpm
        val beatPhase = (t * bpm / 60f) % 1f
        val pulse = exp(-beatPhase * 5f)
        val bass = if (live) Beat.bass else 0.18f + 0.22f * pulse
        val vocalTarget = if (live) Beat.vocal else 0.35f + 0.15f * sin(t * 0.9f)
        vocalMix += (vocalTarget - vocalMix) * min(1f, dt * 6f)
        // Bass lifts the field a little, with a slow release.
        val glowTarget = if (live) Beat.bass else 0.25f * pulse
        glow += (glowTarget - glow) * min(1f, dt * (if (glowTarget > glow) 6f else 0.8f))

        // Structure from the lyrics.
        val isChorus = l != null && index in chorus.indices && chorus[index]
        chorusMix += ((if (isChorus) 1f else 0f) - chorusMix) * min(1f, dt * 1.6f)
        val gapNow = l != null && (index < 0 || l.line(index).isNullOrEmpty()) && l.timeOf(index + 1) - songPos > 4000
        gapMix += ((if (gapNow || l == null) 1f else 0f) - gapMix) * min(1f, dt * 1.2f)

        // Barrel spring: about 700 ms to settle, a whisper of overshoot.
        val k = 140f
        val c = 2f * sqrt(k) * 0.82f
        vel += (k * (index - pos) - c * vel) * dt
        pos += vel * dt

        val s = sig
        val introT = (now - introStart) / 1000f
        val hasIntro = s?.cover != null && introT < 4.6f
        val fieldIn = if (hasIntro) 0.3f + 0.7f * ((introT - 1.4f) / 1.2f).coerceIn(0f, 1f) else 1f
        val change = ((now - sigStart) / 2000f).coerceIn(0f, 1f)
        val old = oldSig
        val crossing = old != null && change < 1f

        // The camera eases in a little on the chorus.
        val zoom = 1f + 0.03f * chorusMix
        canvas.save()
        canvas.scale(zoom, zoom, cx, cy)

        val node = bgNode
        if (LAYERED_BACKGROUND && node != null && Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated) {
            // The background is soft everywhere, so it renders into a small layer
            // that the GPU stretches over the screen: one full-screen fill instead of three.
            val bw = 160
            val bh = 90
            val rc = node.beginRecording(bw, bh)
            drawBackground(rc, bw.toFloat(), bh.toFloat(), t, s, old, crossing, change, fieldIn)
            node.endRecording()
            node.setPosition(0, 0, bw, bh)
            node.setUseCompositingLayer(true, null)
            node.setPivotX(0f)
            node.setPivotY(0f)
            node.setScaleX(w / bw)
            node.setScaleY(h / bh)
            canvas.drawRenderNode(node)
        } else {
            drawBackground(canvas, w, h, t, s, old, crossing, change, fieldIn)
        }

        // The song's signature lives only at the edges (see drawCorners), so
        // nothing ever moves behind the words.
        edgeAmp = (1f - 0.7f * gapMix) * (1f + 0.4f * chorusMix)
        edgeBass = bass
        edgeT = t

        // Intro: the cover, centred, melts into its own colour field; then the title; then the barrel.
        val rise: Float
        if (hasIntro) {
            drawIntroCover(canvas, s!!, cx, cy, introT)
            drawTitle(canvas, cx, cy, ((introT - 2.2f) / 0.6f).coerceIn(0f, 1f) * (1f - ((introT - 3.6f) / 0.6f).coerceIn(0f, 1f)), 0.95f)
            rise = ((introT - 3.8f) / 0.8f).coerceIn(0f, 1f)
        } else {
            rise = 1f
        }

        if (l != null && rise > 0f) {
            if (gapMix > 0.02f) drawTitle(canvas, cx, cy, gapMix * rise, 0.42f)
            if (gapMix < 0.98f) drawStack(canvas, l, cx, cy + (1f - rise) * 36f.dp, songPos, rise * (1f - gapMix))
        } else if (l == null && rise > 0f) {
            drawTitle(canvas, cx, cy, rise, 0.75f)
        }
        canvas.restore()

        drawCorners(canvas, w, h, songPos)
        if (running) postInvalidateOnAnimation()
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float, t: Float, s: Sig?, old: Sig?, crossing: Boolean, change: Float, fieldIn: Float) {
        if (s == null && old == null) canvas.drawColor(0xFF07080C.toInt())
        if (crossing) drawField(canvas, old!!, w, h, t, 1f)
        if (s != null) drawField(canvas, s, w, h, t, if (crossing) change else 1f)
        if (fieldIn < 1f) canvas.drawColor(((1f - fieldIn) * 255).toInt().coerceIn(0, 255) shl 24)
        vignette?.let {
            dst.set(0f, 0f, w, h)
            canvas.drawBitmap(it, null, dst, vignettePaint)
        }
    }

    // RenderNode is public from Android 10; creating it on 8 or 9 would crash the view.
    private val bgNode: android.graphics.RenderNode? =
        if (LAYERED_BACKGROUND && Build.VERSION.SDK_INT >= 29) android.graphics.RenderNode("lyrics-background") else null

    private fun drawField(canvas: Canvas, s: Sig, w: Float, h: Float, t: Float, alpha: Float) {
        val f = s.field
        val cover = max(w / f.width, h / f.height)
        val dir = if (s.p[0] > 0.5f) 1f else -1f
        for (copy in 0..1) {
            val period = 70f + 25f * copy + 20f * s.p[1]
            val a = (t / period) * 2f * PI.toFloat()
            fieldMatrix.reset()
            fieldMatrix.postTranslate(-f.width / 2f, -f.height / 2f)
            fieldMatrix.postRotate(dir * (if (copy == 0) 5f else -7f) * sin(a))
            val sc = cover * (1.35f + 0.12f * sin(a * 0.7f + copy))
            fieldMatrix.postScale(sc, sc)
            fieldMatrix.postTranslate(w / 2f + w * 0.05f * sin(a * 0.5f + s.p[2] * 6f), h / 2f + h * 0.05f * cos(a * 0.6f + s.p[3] * 6f))
            val blend = if (copy == 0) 1f else 0.5f + 0.5f * sin(t / (21f + 9f * s.p[4]))
            fieldPaint.colorFilter = s.filters[(glow * (s.filters.size - 1)).toInt().coerceIn(0, s.filters.size - 1)]
            fieldPaint.alpha = (alpha * blend * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(f, fieldMatrix, fieldPaint)
        }
    }

    // A cheap sine: the motifs evaluate thousands per frame.
    private fun sn(x: Float): Float = SIN[((x * TABLE_SCALE).toInt() and TABLE_MASK)]

    private fun drawIntroCover(canvas: Canvas, s: Sig, cx: Float, cy: Float, t: Float) {
        val cover = s.cover ?: return
        // In over 0.6 s, held, then from 1.4 s it grows and melts into the field.
        val inA = (t / 0.6f).coerceIn(0f, 1f)
        val out = ((t - 1.4f) / 1.2f).coerceIn(0f, 1f)
        val e = 1f - (1f - out).pow(2)
        val scale = (0.94f + 0.06f * inA) * (1f + 1.4f * e)
        val alpha = inA * (1f - e)
        if (alpha <= 0.01f) return
        m.reset()
        m.postTranslate(-cover.width / 2f, -cover.height / 2f)
        m.postScale(scale, scale)
        m.postTranslate(cx, cy)
        coverPaint.alpha = (alpha * 255).toInt()
        canvas.drawBitmap(cover, m, coverPaint)
    }

    private fun drawTitle(canvas: Canvas, cx: Float, cy: Float, a: Float, strength: Float) {
        if (a <= 0.01f) return
        val tex = titleTex ?: return
        m.reset()
        m.postTranslate(cx - tex.w / 2f, cy - tex.h / 2f - (1f - a) * 10f.dp)
        linePaint.shader = null
        linePaint.color = Color.WHITE
        linePaint.alpha = (a * strength * 255).toInt()
        canvas.drawBitmap(tex.mask, m, linePaint)
    }

    /**
     * The lyric stack: at most one line above and two below. Neighbours are
     * smaller and fainter but never squashed or tilted, so every word keeps
     * its true shape. A line change rolls the stack on a spring; positions,
     * sizes and fades are interpolated between fixed slots.
     */
    private fun drawStack(canvas: Canvas, l: Lyrics, cx: Float, cy: Float, songPos: Long, rise: Float) {
        val cur = index
        val lh = textPx * 1.3f
        for (i in max(0, cur - 2)..min(l.size - 1, cur + 3)) {
            val tex = lines[i] ?: continue
            val rel = i - pos
            if (rel < -2f || rel > 3f) continue
            val y = slot(rel, SLOT_Y) * lh
            val sc = slot(rel, SLOT_SCALE) * (if (i == cur) 1f + 0.015f * vocalMix else 1f)
            val a = slot(rel, SLOT_ALPHA) * rise
            if (a <= 0.004f) continue
            m.reset()
            m.postTranslate(-tex.w / 2f, -tex.h / 2f)
            m.postScale(sc, sc)
            m.postTranslate(cx, cy + y)
            canvas.save()
            canvas.concat(m)
            if (i == cur) {
                tex.shadow?.let {
                    shadowPaint.alpha = (0.35f * a * 255).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(it, -tex.shadowPad, -tex.shadowPad + 1.5f.dp, shadowPaint)
                }
                drawSung(canvas, tex, l, i, songPos, a)
            } else {
                linePaint.shader = null
                linePaint.color = Color.WHITE
                linePaint.alpha = (a * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(tex.mask, 0f, 0f, linePaint)
            }
            canvas.restore()
        }
    }

    /** Piecewise-linear lookup over the slots at rel = -2, -1, 0, 1, 2, 3. */
    private fun slot(rel: Float, table: FloatArray): Float {
        val x = (rel + 2f).coerceIn(0f, 5f)
        val i = min(4, x.toInt())
        val f = x - i
        return table[i] + (table[i + 1] - table[i]) * f
    }

    /** The current line: sung words white, the rest at 45 %, with a soft edge between. */
    private fun drawSung(canvas: Canvas, tex: LineTex, l: Lyrics, i: Int, songPos: Long, a: Float) {
        val start = l.times[i]
        val next = l.timeOf(i + 1)
        val span = (if (next == Long.MAX_VALUE) 5000L else next - start).coerceIn(800L, 10_000L) * 0.9f
        val wt = l.words[i]
        val k = tex.wordStart.size
        var x: Float
        if (wt != null && wt.times.size == k) {
            var j = 0
            while (j + 1 < k && wt.times[j + 1] <= songPos) j++
            val ws = wt.times[j]
            val we = if (j + 1 < k) wt.times[j + 1] else (start + span).toLong()
            val u = ((songPos - ws).toFloat() / max(1L, we - ws)).coerceIn(0f, 1f)
            x = tex.wordStart[j] + (tex.wordEnd[j] - tex.wordStart[j]) * u
            if (songPos < wt.times[0]) x = tex.wordStart[0]
        } else {
            val f = ((songPos - start) / span).coerceIn(0f, 1f)
            var j = 0
            while (j + 1 < k && tex.share[j] < f) j++
            val s0 = if (j == 0) 0f else tex.share[j - 1]
            val u = ((f - s0) / max(0.0001f, tex.share[j] - s0)).coerceIn(0f, 1f)
            x = tex.wordStart[j] + (tex.wordEnd[j] - tex.wordStart[j]) * u
        }
        val edge = textPx * 0.8f
        val shader = if (tex.rtl) sweepRtl else sweepLtr
        sweepMatrix.setScale(edge, 1f)
        sweepMatrix.postTranslate(x - edge / 2f, 0f)
        shader.setLocalMatrix(sweepMatrix)
        sungPaint.shader = shader
        // Vocals make the sung part breathe a little brighter.
        sungPaint.alpha = (a * (0.9f + 0.1f * vocalMix) * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(tex.mask, 0f, 0f, sungPaint)
    }

    private fun drawCorners(canvas: Canvas, w: Float, h: Float, songPos: Long) {
        val t = track ?: return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val margin = 48f.dp
        val tile = 64f.dp
        val left = if (rtl) w - margin - tile else margin
        val top = h - margin - tile
        sig?.let { s ->
            if (s.motif % 2 == 0) edgeRings(canvas, s, left + tile / 2f, top + tile / 2f, tile)
            else edgeSpectrum(canvas, s, w, h)
        }
        artTile?.let { canvas.drawBitmap(it, left, top, tilePaint) }
        if (cornerFor !== t) {
            cornerFor = t
            titlePaint.typeface = Fonts.get(context, W.SEMIBOLD)
            titlePaint.textSize = 16f.dp
            artistPaint.typeface = Fonts.get(context, W.REGULAR)
            artistPaint.textSize = 13f.dp
            cornerTitle = TextUtils.ellipsize(t.title, titlePaint, 300f.dp, TextUtils.TruncateAt.END).toString()
            cornerArtist = TextUtils.ellipsize(t.artist, artistPaint, 300f.dp, TextUtils.TruncateAt.END).toString()
            titlePaint.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
            artistPaint.textAlign = titlePaint.textAlign
        }
        val tx = if (rtl) left - 16f.dp else left + tile + 16f.dp
        canvas.drawText(cornerTitle, tx, top + 22f.dp, titlePaint)
        canvas.drawText(cornerArtist, tx, top + 42f.dp, artistPaint)
        val barW = 220f.dp
        val barY = top + 56f.dp
        val bx0 = if (rtl) tx - barW else tx
        canvas.drawRect(bx0, barY, bx0 + barW, barY + 2f.dp, barTrack)
        val d = t.durationMs
        if (d > 0) {
            val f = (songPos.toFloat() / d).coerceIn(0f, 1f)
            if (rtl) canvas.drawRect(bx0 + barW * (1f - f), barY, bx0 + barW, barY + 2f.dp, barFill)
            else canvas.drawRect(bx0, barY, bx0 + barW * f, barY + 2f.dp, barFill)
        }
        // Small clock in the top end corner; the string changes once a minute.
        val minute = System.currentTimeMillis() / 60_000
        if (minute != clockMinute) {
            clockMinute = minute
            clockDate.time = System.currentTimeMillis()
            clockText = clockFormat.format(clockDate)
            clockPaint.typeface = Fonts.get(context, W.LIGHT)
            clockPaint.textSize = 22f.dp
        }
        clockPaint.textAlign = if (rtl) Paint.Align.LEFT else Paint.Align.RIGHT
        canvas.drawText(clockText, if (rtl) margin else w - margin, margin + 18f.dp, clockPaint)
    }

    /** Rings breathing out of the small cover, at most 10 % alpha, fading out within the corner. */
    private fun edgeRings(canvas: Canvas, s: Sig, x: Float, y: Float, tile: Float) {
        val spacing = (16f + 10f * s.p[14]).dp
        val bpm = if (Beat.live && Beat.bpm > 0f) Beat.bpm else lineRhythmBpm
        val speed = spacing / (60f / bpm * 2f)
        val phase = (edgeT * speed) % spacing
        val r0 = tile * 0.72f
        val maxR = tile * 2.3f
        ringPaint.color = s.light
        var r = r0 + phase
        while (r < maxR) {
            val f = 1f - (r - r0) / (maxR - r0)
            ringPaint.alpha = (0.10f * 255 * f * f * edgeAmp * (0.7f + 0.3f * edgeBass)).toInt().coerceIn(0, 26)
            canvas.drawCircle(x, y, r + edgeBass * 2f.dp * f, ringPaint)
            r += spacing
        }
    }

    /** A thin live spectrum along the bottom edge, far below the words. */
    private fun edgeSpectrum(canvas: Canvas, s: Sig, w: Float, h: Float) {
        val n = Beat.BANDS
        val live = Beat.live
        val base = h - 10f.dp
        val gap = w / n
        var o = 0
        for (i in 0 until n) {
            val v = if (live) Beat.spectrum[i]
            else 0.2f + 0.15f * sn(edgeT * (0.5f + s.p[15]) + i * 0.33f) + 0.08f * sn(edgeT * 1.3f + i * 1.1f)
            val x = i * gap + gap / 2f
            seg[o++] = x; seg[o++] = base
            seg[o++] = x; seg[o++] = base - (2f.dp + 22f.dp * v.coerceIn(0f, 1f) * edgeAmp)
        }
        ringPaint.color = s.light
        ringPaint.alpha = 26
        canvas.drawLines(seg, 0, o, ringPaint)
    }

    companion object {
        /** Render the background into a small GPU layer (see onDraw). Off until measured on the box. */
        private const val LAYERED_BACKGROUND = false
        private const val TABLE = 2048
        private const val TABLE_MASK = TABLE - 1
        private val TABLE_SCALE = TABLE / (2f * PI.toFloat())
        private val SIN = FloatArray(TABLE) { sin(it * 2.0 * PI / TABLE).toFloat() }

        // Lyric stack slots for rel = -2, -1, 0, 1, 2, 3 (in line heights, scale, alpha).
        private val SLOT_Y = floatArrayOf(-2.0f, -1.12f, 0f, 1.12f, 2.0f, 2.8f)
        private val SLOT_SCALE = floatArrayOf(0.66f, 0.78f, 1f, 0.78f, 0.66f, 0.6f)
        private val SLOT_ALPHA = floatArrayOf(0f, 0.22f, 1f, 0.22f, 0.10f, 0f)

        private var vignette: Bitmap? = null

        /** The vignette, small and smooth; the GPU stretches it over the screen. */
        private fun makeVignette(w: Int, h: Int): Bitmap {
            val px = IntArray(w * h)
            val cx = w / 2f
            val cy = h / 2f
            val maxD = sqrt(cx * cx + cy * cy)
            for (y in 0 until h) for (x in 0 until w) {
                val dx = (x - cx) / maxD
                val dy = (y - cy) / maxD
                val d = sqrt(dx * dx + dy * dy)
                val v = ((d - 0.42f) / 0.58f).coerceIn(0f, 1f)
                px[y * w + x] = ((v * v * 0.7f * 255).toInt().coerceIn(0, 255)) shl 24
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
