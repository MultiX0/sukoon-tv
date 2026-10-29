package dev.multix.tvhome.ui

import android.os.Build
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.multix.tvhome.R
import dev.multix.tvhome.core.Colors
import dev.multix.tvhome.core.Motion
import dev.multix.tvhome.core.Prefs
import dev.multix.tvhome.core.SText
import dev.multix.tvhome.core.W
import dev.multix.tvhome.core.dp
import dev.multix.tvhome.core.frame
import dev.multix.tvhome.core.lerpColor
import dev.multix.tvhome.core.margins
import dev.multix.tvhome.core.roundClip
import dev.multix.tvhome.core.rounded
import dev.multix.tvhome.core.withAlpha
import dev.multix.tvhome.media.NowPlaying
import dev.multix.tvhome.media.Track

/** Thin progress line. Its fill is a scaled view, so progress costs no redraws. */
class ProgressLine(ctx: Context, height: Int) : FrameLayout(ctx) {
    private val fill = View(ctx)
    private var anim: ObjectAnimator? = null

    init {
        background = rounded(0x33FFFFFF, height / 2f)
        fill.background = rounded(Colors.WHITE, height / 2f)
        fill.pivotX = 0f
        fill.scaleX = 0f
        addView(fill, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        roundClip(height / 2f)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        fill.pivotX = if (layoutDirection == LAYOUT_DIRECTION_RTL) (r - l).toFloat() else 0f
    }

    fun setColor(c: Int) {
        fill.background = rounded(c, height / 2f)
    }

    /** Runs the fill linearly to the end of the track; resynced on every state change. */
    fun sync(pos: Long, dur: Long, playing: Boolean, speed: Float) {
        anim?.cancel()
        if (dur <= 0) {
            fill.scaleX = 0f
            return
        }
        val f = (pos.toFloat() / dur).coerceIn(0f, 1f)
        fill.scaleX = f
        if (!playing || f >= 1f) return
        anim = ObjectAnimator.ofFloat(fill, View.SCALE_X, f, 1f).apply {
            duration = ((dur - pos) / speed).toLong().coerceAtLeast(1)
            interpolator = LinearInterpolator()
            start()
        }
    }

    fun stop() { anim?.cancel(); anim = null }
}

/**
 * Home screen card for the track that is playing: artwork, title, artist, a
 * thin progress line and three lyric lines. The card tints itself with the
 * album colour.
 */
class NowPlayingCard(ctx: Context) : FrameLayout(ctx) {
    private val base = GradientDrawable().apply { cornerRadius = 22f.dp; setColor(0x99101114.toInt()) }
    private val tint = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0, 0)).apply { cornerRadius = 22f.dp }
    private val tintColors = IntArray(2)
    private var accent = Colors.DEFAULT_ACCENT
    private var tintAnim: ValueAnimator? = null

    private val art = ImageView(ctx)
    private val title = SText(ctx, W.SEMIBOLD, 18f, Colors.WHITE)
    private val artist = SText(ctx, W.REGULAR, 14f, Colors.TEXT3)
    private val progress = ProgressLine(ctx, 3.dp)
    val lyrics = LyricsView(ctx, before = 0, after = 2, centered = false, dimScale = 0.78f,
        alphas = floatArrayOf(1f, 0.5f, 0.26f))
    val button = PillButton(ctx, R.drawable.ic_expand, ctx.getString(R.string.np_fullscreen))

    private var track: Track? = null
    var showing = false
        private set

    init {
        background = LayerDrawable(arrayOf(base, tint))
        clipChildren = false
        visibility = GONE
        alpha = 0f

        val artSize = 132.dp
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        art.background = rounded(0x33FFFFFF, 14f.dp)
        art.roundClip(14f.dp)
        art.elevation = 10f.dp
        addView(art, frame(artSize, artSize, Gravity.START or Gravity.CENTER_VERTICAL).margins(start = 20.dp))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val names = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        artist.maxLines = 1
        artist.ellipsize = TextUtils.TruncateAt.END
        names.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        names.addView(artist, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 2.dp })
        header.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // The pill sits on the title row, at the end of the card.
        header.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 32.dp).apply { marginStart = 14.dp })
        col.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        lyrics.gap = 6.dp
        col.addView(lyrics, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 12.dp })
        col.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 3.dp).apply { topMargin = 10.dp })
        val colStart = 20.dp + artSize + 22.dp
        addView(col, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).margins(start = colStart, top = 18.dp, end = 20.dp, bottom = 22.dp))
        applyTextSize()
    }

    fun applyTextSize() {
        lyrics.setTextSize(when (Prefs.lyricsSize) { 0 -> 19f; 2 -> 26f; else -> 22f })
    }

    fun bind(t: Track?) {
        if (t == null) {
            hide()
            return
        }
        val changed = track != null && track !== t
        track = t
        if (changed && showing) {
            // Quick crossfade of the text; artwork follows when it is ready.
            for (v in arrayOf(title, artist)) v.animate().alpha(0f).setDuration(120).withEndAction {
                setText(t)
                v.animate().alpha(1f).setDuration(Motion.MED).setInterpolator(Motion.out).start()
            }.start()
            art.animate().alpha(0.25f).setDuration(Motion.FAST).start()
        } else {
            setText(t)
        }
        lyrics.setLyrics(if (Prefs.lyricsOn) t.lyrics else null, NowPlaying.line)
        if (t.artDone) bindArt(t)
        show()
    }

    private fun setText(t: Track) {
        title.text = t.title
        artist.text = if (t.album.isNotEmpty() && t.artist.isNotEmpty()) t.artist else t.artist.ifEmpty { t.album }
    }

    fun bindArt(t: Track) {
        if (t !== track) return
        val bmp = t.art
        if (bmp != null) {
            art.setImageBitmap(bmp)
        } else {
            art.setImageResource(R.drawable.ic_music)
            art.scaleType = ImageView.ScaleType.CENTER
        }
        if (bmp != null) art.scaleType = ImageView.ScaleType.CENTER_CROP
        art.animate().alpha(1f).setDuration(Motion.SLOW).setInterpolator(Motion.out).start()
        // Coloured shadows exist from Android 9.
        if (Build.VERSION.SDK_INT >= 28) {
            art.outlineSpotShadowColor = t.dark
            art.outlineAmbientShadowColor = t.dark
        }
        setAccent(t.accent)
    }

    fun bindLyrics(t: Track) {
        if (t !== track) return
        lyrics.setLyrics(if (Prefs.lyricsOn) t.lyrics else null, NowPlaying.line)
    }

    fun onLine(i: Int, animate: Boolean) {
        lyrics.setIndex(i, animate)
    }

    fun onPlayback(playing: Boolean) {
        val t = track ?: return
        progress.sync(NowPlaying.position(), t.durationMs, playing, NowPlaying.speed())
        lyrics.animate().alpha(if (playing) 1f else 0.55f).setDuration(Motion.MED).start()
    }

    private fun setAccent(c: Int) {
        val from = accent
        accent = c
        tintAnim?.cancel()
        tintAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = Motion.inOut
            addUpdateListener {
                val col = lerpColor(from, c, it.animatedFraction)
                tintColors[0] = withAlpha(col, 0.42f)
                tintColors[1] = withAlpha(col, 0.06f)
                tint.colors = tintColors
            }
            start()
        }
        progress.setColor(lerpColor(c, Colors.WHITE, 0.55f))
    }

    fun show() {
        if (showing) return
        showing = true
        visibility = VISIBLE
        translationY = 18f.dp
        animate().alpha(1f).translationY(0f).setDuration(Motion.SLOW + 100).setInterpolator(Motion.out)
            .withLayer().withEndAction(null).start()
    }

    fun hide() {
        if (!showing) return
        showing = false
        track = null
        progress.stop()
        animate().alpha(0f).translationY(12f.dp).setDuration(Motion.MED).setInterpolator(Motion.accel)
            .withLayer().withEndAction { if (!showing) visibility = GONE }.start()
    }

    fun setPaused() { progress.stop() }
}

/** Glass pill with an icon and a label. White with dark ink when focused. */
class PillButton(ctx: Context, icon: Int, text: String) : LinearLayout(ctx) {
    private val iv = ImageView(ctx)
    private val tv = SText(ctx, W.MEDIUM, 13f, Colors.WHITE)
    private val bgIdle = rounded(Colors.GLASS, 16f.dp)
    private val bgFocus = rounded(0xF2FFFFFF.toInt(), 16f.dp)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(12.dp, 0, 14.dp, 0)
        background = bgIdle
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        iv.setImageResource(icon)
        iv.setColorFilter(Colors.WHITE)
        addView(iv, LayoutParams(16.dp, 16.dp))
        tv.text = text
        tv.maxLines = 1
        addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = 7.dp })
        contentDescription = text
    }

    fun setLabel(text: String) {
        tv.text = text
        contentDescription = text
    }

    fun iconOnly() {
        removeView(tv)
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        background = if (gainFocus) bgFocus else bgIdle
        val ink = if (gainFocus) Colors.INK else Colors.WHITE
        iv.setColorFilter(ink)
        tv.setTextColor(ink)
        animate().scaleX(if (gainFocus) 1.08f else 1f).scaleY(if (gainFocus) 1.08f else 1f)
            .setDuration(if (gainFocus) Motion.SLOW else Motion.MED)
            .setInterpolator(if (gainFocus) Motion.spring else Motion.out).start()
    }
}

/**
 * The compact now playing style: a small glass pill with the artwork, the
 * track and the current lyric line, which swaps with a short slide.
 */
class NowPlayingPill(ctx: Context) : LinearLayout(ctx) {
    private val art = ImageView(ctx)
    private val meta = SText(ctx, W.REGULAR, 12f, Colors.TEXT3)
    private val line = SText(ctx, W.MEDIUM, 15f, Colors.WHITE)
    private val bgIdle = rounded(0xB3141518.toInt(), 30f.dp)
    private val bgFocus = rounded(0xF2FFFFFF.toInt(), 30f.dp)
    private var track: Track? = null
    var showing = false
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = bgIdle
        setPadding(10.dp, 8.dp, 22.dp, 8.dp)
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        visibility = GONE
        alpha = 0f
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        art.roundClip(10f.dp)
        art.background = rounded(0x33FFFFFF, 10f.dp)
        addView(art, LayoutParams(44.dp, 44.dp))
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        line.maxLines = 1
        line.ellipsize = TextUtils.TruncateAt.END
        col.addView(meta)
        col.addView(line)
        addView(col, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = 12.dp })
        meta.maxWidth = 420.dp
        line.maxWidth = 420.dp
    }

    fun bind(t: Track?) {
        if (t == null) { hide(); return }
        track = t
        meta.text = if (t.artist.isNotEmpty()) "${t.title}  ·  ${t.artist}" else t.title
        line.text = t.title
        bindArt(t)
        show()
    }

    fun bindArt(t: Track) {
        if (t !== track) return
        t.art?.let { art.setImageBitmap(it) } ?: art.setImageDrawable(null)
    }

    fun onLine(i: Int, animate: Boolean) {
        val t = track ?: return
        val lyr = t.lyrics
        val text = if (Prefs.lyricsOn && lyr != null && lyr.synced) lyr.line(i)?.ifEmpty { "• • •" } ?: t.title else t.title
        if (text == line.text.toString()) return
        if (!animate) { line.text = text; return }
        line.animate().alpha(0f).translationY(-6f.dp).setDuration(140).setInterpolator(Motion.accel).withEndAction {
            line.text = text
            line.translationY = 8f.dp
            line.animate().alpha(1f).translationY(0f).setDuration(Motion.MED).setInterpolator(Motion.out).start()
        }.start()
    }

    fun show() {
        if (showing) return
        showing = true
        visibility = VISIBLE
        translationY = -10f.dp
        animate().alpha(1f).translationY(0f).setDuration(Motion.SLOW).setInterpolator(Motion.out).withLayer().start()
    }

    fun hide() {
        if (!showing) return
        showing = false
        track = null
        animate().alpha(0f).translationY(-8f.dp).setDuration(Motion.MED).setInterpolator(Motion.accel)
            .withLayer().withEndAction { if (!showing) visibility = GONE }.start()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        background = if (gainFocus) bgFocus else bgIdle
        line.setTextColor(if (gainFocus) Colors.INK else Colors.WHITE)
        meta.setTextColor(if (gainFocus) Colors.INK2 else Colors.TEXT3)
        animate().scaleX(if (gainFocus) 1.05f else 1f).scaleY(if (gainFocus) 1.05f else 1f)
            .setDuration(Motion.MED).setInterpolator(if (gainFocus) Motion.spring else Motion.out).start()
    }
}

/** A sample track so the Customize preview has something to show when nothing plays. */
object DemoTrack {
    fun make(ctx: Context): Track {
        val t = Track("demo", "demo", ctx.getString(R.string.np_demo_title), ctx.getString(R.string.np_demo_artist), "", 180_000)
        val lines = arrayOf(ctx.getString(R.string.np_demo_l1), ctx.getString(R.string.np_demo_l2), ctx.getString(R.string.np_demo_l3),
            ctx.getString(R.string.np_demo_l4), ctx.getString(R.string.np_demo_l5), "")
        // A 22 s loop: five lines, then an instrumental gap long enough to show the title.
        t.lyrics = dev.multix.tvhome.media.Lyrics(longArrayOf(0, 3500, 7000, 10500, 14000, 17500), lines, true)
        t.lyricsDone = true
        val size = 256
        val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
            0xFF3B2F7A.toInt(), 0xFFE0759A.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        t.art = b
        t.accent = 0xFFB98CFF.toInt()
        t.dark = 0xFF2A1F55.toInt()
        t.vibrant = 0xFFE0759A.toInt()
        t.artDone = true
        return t
    }
}
