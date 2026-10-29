package dev.multix.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import dev.multix.tvhome.core.Bg
import dev.multix.tvhome.core.Lang
import dev.multix.tvhome.core.Motion
import dev.multix.tvhome.core.Prefs
import dev.multix.tvhome.core.dp
import dev.multix.tvhome.core.frame
import dev.multix.tvhome.core.margins
import dev.multix.tvhome.media.NowPlaying
import dev.multix.tvhome.media.Track
import dev.multix.tvhome.ui.CosmosLyricsView
import dev.multix.tvhome.ui.ClockView
import dev.multix.tvhome.wall.WallpaperView
import dev.multix.tvhome.wall.Walls

/**
 * System screensaver: full screen lyrics while music plays, otherwise the
 * wallpaper set drifting slowly with a small clock.
 */
class LyricsDream : DreamService(), NowPlaying.Listener {
    private lateinit var wall: WallpaperView
    private lateinit var ambient: CosmosLyricsView
    private lateinit var clock: ClockView
    private var index = 0
    private var running = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        dev.multix.tvhome.core.Ui.init(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        wall = WallpaperView(this)
        wall.motion = true
        root.addView(wall, frame(-1, -1))
        clock = ClockView(this, big = false)
        root.addView(clock, frame(-2, -2, Gravity.BOTTOM or Gravity.END).margins(end = 56.dp, bottom = 44.dp))
        ambient = CosmosLyricsView(this)
        ambient.visibility = View.GONE
        root.addView(ambient, frame(-1, -1))
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        running = true
        wall.setRunning(true)
        clock.start()
        Bg.load({ Walls.scan(this); Walls.find(Prefs.wallSet) }) { set ->
            if (set != null && running) {
                index = Prefs.wallIndex
                next(set.id, 1200)
            }
        }
        NowPlaying.addListener(this)
        NowPlaying.track?.let { onTrack(it) }
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        running = false
        Bg.main.removeCallbacks(rotate)
        NowPlaying.removeListener(this)
        wall.setRunning(false)
        clock.stop()
        ambient.release()
    }

    private val rotate = Runnable {
        val set = Walls.find(Prefs.wallSet) ?: return@Runnable
        index++
        next(set.id, 2200)
    }

    private fun next(id: String, fade: Long) {
        val set = Walls.find(id) ?: return
        val w = minOf(resources.displayMetrics.widthPixels, 1280)
        val h = minOf(resources.displayMetrics.heightPixels, 720)
        Bg.load({ Walls.decode(this, set, index, w, h) }) { b: Bitmap? ->
            if (b != null && running) {
                wall.show(b, fade)
                ambient.setWallpaper(b)
            }
            if (running) Bg.main.postDelayed(rotate, 90_000)
        }
    }

    override fun onTrack(t: Track?) {
        if (t == null) {
            if (ambient.visibility == View.VISIBLE) {
                ambient.animate().alpha(0f).setDuration(Motion.SLOW).withEndAction {
                    ambient.visibility = View.GONE
                    ambient.stop()
                }.start()
            }
            clock.visibility = View.VISIBLE
            return
        }
        ambient.bind(t)
        ambient.onLine(NowPlaying.line, false)
        if (ambient.visibility != View.VISIBLE) {
            ambient.visibility = View.VISIBLE
            ambient.alpha = 0f
            ambient.start()
            ambient.animate().alpha(1f).setDuration(800).withLayer().start()
        }
        clock.visibility = View.GONE
    }

    override fun onArt(t: Track) = ambient.bindArt(t)
    override fun onLyrics(t: Track) = ambient.bindLyrics(t)
    override fun onPlayback(playing: Boolean) = ambient.onPlayback(playing)
    override fun onLine(index: Int, animate: Boolean) = ambient.onLine(index, animate)
}
