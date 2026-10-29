package dev.multix.tvhome.ui

import android.os.Build
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.LightingColorFilter
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
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
import dev.multix.tvhome.core.margins
import dev.multix.tvhome.core.roundClip
import dev.multix.tvhome.core.withAlpha
import dev.multix.tvhome.media.NowPlaying
import dev.multix.tvhome.media.Track

/**
 * Full screen lyrics. The background is the album art, blurred once on the
 * CPU from a 40 px copy and stretched by the GPU, drifting slowly. Used as an
 * overlay on the home screen and inside the screensaver.
 */
class AmbientView(ctx: Context) : FrameLayout(ctx) {
    private val bg = ImageView(ctx)
    // Darkening is a colour filter on the image itself, so the whole
    // background costs a single full screen fill on the GPU.
    private val dim = LightingColorFilter(0xFF8E8E8E.toInt(), 0)

    private val headArt = ImageView(ctx)
    private val headTitle = SText(ctx, W.SEMIBOLD, 17f, Colors.WHITE)
    private val headArtist = SText(ctx, W.REGULAR, 14f, Colors.TEXT3)
    private val header = LinearLayout(ctx)
    val clock = ClockView(ctx, big = false)

    private val lyrics = LyricsView(ctx, before = 2, after = 3, centered = true, dimScale = 0.9f,
        alphas = floatArrayOf(1f, 0.42f, 0.26f, 0.16f))

    private val solo = LinearLayout(ctx)
    private val soloArt = ImageView(ctx)
    private val soloTitle = SText(ctx, W.SEMIBOLD, 30f, Colors.WHITE)
    private val soloArtist = SText(ctx, W.REGULAR, 19f, Colors.TEXT2)

    private val progress = ProgressLine(ctx, 3.dp)
    private var drift: ObjectAnimator? = null
    private var track: Track? = null
    private var wallpaper: Bitmap? = null

    init {
        setBackgroundColor(0xFF07080A.toInt())
        isFocusable = false

        bg.scaleType = ImageView.ScaleType.CENTER_CROP
        bg.colorFilter = dim
        addView(bg, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        headArt.scaleType = ImageView.ScaleType.CENTER_CROP
        headArt.roundClip(9f.dp)
        header.addView(headArt, LinearLayout.LayoutParams(56.dp, 56.dp))
        val names = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        headTitle.maxLines = 1
        headTitle.ellipsize = TextUtils.TruncateAt.END
        headArtist.maxLines = 1
        headArtist.ellipsize = TextUtils.TruncateAt.END
        names.addView(headTitle)
        names.addView(headArtist)
        header.addView(names, LinearLayout.LayoutParams(420.dp, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 16.dp })
        addView(header, frame(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).margins(start = 56.dp, top = 40.dp))

        addView(clock, frame(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).margins(end = 56.dp, top = 46.dp))

        lyrics.gap = 14.dp
        addView(lyrics, frame(780.dp, 330.dp, Gravity.CENTER).margins(top = 24.dp))

        solo.orientation = LinearLayout.VERTICAL
        solo.gravity = Gravity.CENTER_HORIZONTAL
        soloArt.scaleType = ImageView.ScaleType.CENTER_CROP
        soloArt.roundClip(18f.dp)
        soloArt.elevation = 24f.dp
        solo.addView(soloArt, LinearLayout.LayoutParams(220.dp, 220.dp).apply { bottomMargin = 28.dp })
        soloTitle.textAlignment = View.TEXT_ALIGNMENT_CENTER
        soloTitle.maxLines = 2
        soloTitle.ellipsize = TextUtils.TruncateAt.END
        soloArtist.textAlignment = View.TEXT_ALIGNMENT_CENTER
        solo.addView(soloTitle, LinearLayout.LayoutParams(700.dp, LinearLayout.LayoutParams.WRAP_CONTENT))
        solo.addView(soloArtist, LinearLayout.LayoutParams(700.dp, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp })
        solo.visibility = GONE
        addView(solo, frame(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        addView(progress, frame(360.dp, 3.dp, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).margins(bottom = 40.dp))
        applyTextSize()
    }

    override fun hasOverlappingRendering() = true

    fun applyTextSize() {
        lyrics.setTextSize(when (Prefs.lyricsSize) { 0 -> 34f; 2 -> 46f; else -> 40f })
    }

    fun setWallpaper(b: Bitmap?) {
        wallpaper = b
        applyBackground()
    }

    fun bind(t: Track?) {
        track = t
        if (t == null) return
        headTitle.text = t.title
        headArtist.text = t.artist
        soloTitle.text = t.title
        soloArtist.text = t.artist
        bindArt(t)
        bindLyrics(t)
        onPlayback(NowPlaying.playing)
    }

    fun bindArt(t: Track) {
        if (t !== track) return
        val art = t.art
        if (art != null) {
            headArt.setImageBitmap(art)
            soloArt.setImageBitmap(art)
            headArt.visibility = VISIBLE
        } else {
            headArt.setImageDrawable(null)
            headArt.visibility = GONE
            soloArt.setImageResource(R.drawable.ic_music)
            soloArt.background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = 18f.dp }
            soloArt.scaleType = ImageView.ScaleType.CENTER
        }
        // Coloured shadows exist from Android 9.
        if (Build.VERSION.SDK_INT >= 28) soloArt.outlineSpotShadowColor = t.dark
        progress.setColor(withAlpha(Colors.WHITE, 0.9f))
        applyBackground()
    }

    private fun applyBackground() {
        val useWall = Prefs.ambientBg == "wall" || track?.blur == null
        val b = if (useWall) wallpaper else track?.blur
        bg.setImageBitmap(b)
        // The plain colour only shows while there is no image at all.
        if (b != null) background = null else setBackgroundColor(0xFF07080A.toInt())
    }

    fun bindLyrics(t: Track) {
        if (t !== track) return
        val lyr = t.lyrics
        val synced = Prefs.lyricsOn && lyr != null && lyr.synced
        lyrics.visibility = if (synced) VISIBLE else GONE
        solo.visibility = if (synced) GONE else VISIBLE
        header.visibility = if (synced) VISIBLE else INVISIBLE
        lyrics.setLyrics(if (synced) lyr else null, NowPlaying.line)
    }

    fun onLine(i: Int, animate: Boolean) {
        lyrics.setIndex(i, animate)
    }

    fun onPlayback(playing: Boolean) {
        val t = track ?: return
        progress.sync(NowPlaying.position(), t.durationMs, playing, NowPlaying.speed())
        lyrics.animate().alpha(if (playing) 1f else 0.5f).setDuration(Motion.MED).start()
    }

    /** Starts the background drift and the clock; call when the view becomes visible. */
    fun start() {
        clock.start()
        if (drift == null) {
            bg.pivotX = resources.displayMetrics.widthPixels / 2f
            bg.pivotY = resources.displayMetrics.heightPixels / 2f
            drift = ObjectAnimator.ofPropertyValuesHolder(
                bg,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1.25f, 1.5f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.25f, 1.5f),
                PropertyValuesHolder.ofFloat(View.ROTATION, -4f, 5f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -30f.dp, 30f.dp),
            ).apply {
                duration = 28_000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } else {
            drift?.resume()
        }
    }

    fun stop() {
        clock.stop()
        drift?.pause()
        progress.stop()
    }

    fun release() {
        drift?.cancel()
        drift = null
        clock.stop()
        progress.stop()
    }
}
