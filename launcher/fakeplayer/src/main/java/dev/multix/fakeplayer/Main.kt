package dev.multix.fakeplayer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView

/**
 * Publishes a silent media session so the launcher's now playing card can be
 * tested without playing any sound. Nothing here touches audio.
 *
 *   am start -n dev.multix.fakeplayer/.Main --es title "..." --es artist "..."
 *       --es album "..." --el duration 200000 --el pos 30000 [--ei hue 210]
 *   am start -n dev.multix.fakeplayer/.Main --es cmd pause|play|stop
 */
class Main : Activity() {
    companion object {
        private var session: MediaSession? = null
        private var position = 0L
        private var duration = 0L
        private var playing = false
        private var updated = 0L
    }

    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this).apply {
            textSize = 20f
            setPadding(48, 48, 48, 48)
        }
        setContentView(label)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        when (i.getStringExtra("cmd")) {
            "stop" -> {
                setState(PlaybackState.STATE_STOPPED)
                session?.release()
                session = null
                label.text = "Session released"
                finish()
                return
            }
            "pause" -> { setState(PlaybackState.STATE_PAUSED); label.text = "Paused"; return }
            "play" -> { setState(PlaybackState.STATE_PLAYING); label.text = "Playing"; return }
        }
        val title = i.getStringExtra("title") ?: "Test track"
        val artist = i.getStringExtra("artist") ?: "Test artist"
        val album = i.getStringExtra("album") ?: ""
        duration = i.getLongExtra("duration", 200_000L)
        position = i.getLongExtra("pos", 0L)
        val hue = i.getIntExtra("hue", 210)

        val s = session ?: MediaSession(this, "fakeplayer").also { session = it }
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = setState(PlaybackState.STATE_PLAYING)
            override fun onPause() = setState(PlaybackState.STATE_PAUSED)
        })
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art(hue))
                .build()
        )
        s.isActive = true
        updated = SystemClock.elapsedRealtime()
        playing = false
        setState(PlaybackState.STATE_PLAYING)
        label.text = "Silent session: $title by $artist"
        // Get out of the way; the process and its session stay alive.
        moveTaskToBack(true)
    }

    private fun setState(state: Int) {
        val now = SystemClock.elapsedRealtime()
        if (playing) position += now - updated
        updated = now
        playing = state == PlaybackState.STATE_PLAYING
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(state, position, 1f, now)
                .build()
        )
    }

    /** Abstract generated artwork, so no real album art is involved. */
    private fun art(hue: Int): Bitmap {
        val size = 512
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val a = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.75f, 0.55f))
        val z = android.graphics.Color.HSVToColor(floatArrayOf((hue + 50) % 360f, 0.85f, 0.95f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), a, 0xFF0B0B14.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = RadialGradient(size * 0.68f, size * 0.36f, size * 0.42f, z, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        return b
    }
}
