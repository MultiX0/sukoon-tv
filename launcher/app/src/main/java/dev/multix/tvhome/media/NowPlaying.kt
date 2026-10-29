package dev.multix.tvhome.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import dev.multix.tvhome.core.Bg
import dev.multix.tvhome.core.Colors
import dev.multix.tvhome.core.Images
import java.net.URL

/** Exists only so the system lets us read media sessions. */
class NowPlayingListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Bg.ui { NowPlaying.retry() }
    }
}

class Track(
    val key: String,
    val pkg: String,
    val title: String,
    val artist: String,
    var album: String,
    var durationMs: Long,
) {
    var art: Bitmap? = null
    var blur: Bitmap? = null
    var accent: Int = Colors.DEFAULT_ACCENT
    var dark: Int = 0xFF14161C.toInt()
    var vibrant: Int = Colors.DEFAULT_ACCENT
    var artDone = false
    var artFrom: Any? = null
    var enriched = false
    var lyrics: Lyrics? = null
    var lyricsDone = false
}

/**
 * Follows whatever is playing and publishes the track, its artwork colours,
 * the lyrics and the current lyric line.
 *
 * Sources, best first: the media session API (needs notification listener
 * access, which low-RAM TV builds refuse), Spotify's own broadcasts, and a
 * `dumpsys media_session` reading when the DUMP permission was granted over
 * adb. With none of them the card simply never appears.
 *
 * Lyric line changes are scheduled for the exact moment the next line
 * starts; the position is extrapolated from the last reading in between.
 */
object NowPlaying {
    interface Listener {
        fun onTrack(t: Track?) {}
        fun onArt(t: Track) {}
        fun onLyrics(t: Track) {}
        fun onPlayback(playing: Boolean) {}
        fun onLine(index: Int, animate: Boolean) {}
    }

    /** Lines are shown slightly early so the slide finishes as the singer starts. */
    private const val LEAD_MS = 180L

    private lateinit var app: Context
    private val listeners = ArrayList<Listener>()
    private var msm: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()
    private var chosen: MediaController? = null
    private var started = false
    private var polling = false

    var track: Track? = null
        private set
    var playing = false
        private set
    var line = -1
        private set

    private var posBase = 0L
    private var posAt = 0L
    private var speed = 1f

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun component(): ComponentName = ComponentName(app, NowPlayingListener::class.java)

    fun hasAccess(): Boolean {
        val flat = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it) == component() }
    }

    /** Which source is in use, for the settings page. */
    fun sourceName(): String = when {
        msm != null -> "session"
        polling -> "dump"
        else -> "broadcast"
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
        if (!started) start()
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
        if (listeners.isEmpty()) stop()
    }

    fun retry() {
        if (listeners.isNotEmpty() && msm == null) {
            stop()
            start()
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        setControllers(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(s: PlaybackState?) { fromControllers() }
        override fun onMetadataChanged(m: MediaMetadata?) { fromControllers() }
        override fun onSessionDestroyed() { fromControllers() }
    }

    private fun start() {
        started = true
        SpotifySource.onChange = { Bg.ui { if (msm == null && !polling) apply(SpotifySource.snap()) } }
        SpotifySource.register(app)
        val m = if (hasAccess()) app.getSystemService(MediaSessionManager::class.java) else null
        if (m != null) {
            try {
                m.addOnActiveSessionsChangedListener(sessionsChanged, component(), Bg.main)
                msm = m
                setControllers(m.getActiveSessions(component()))
                return
            } catch (e: SecurityException) {
                msm = null
            }
        }
        if (DumpSource.available(app)) {
            polling = true
            Bg.main.post(poll)
            // Any audio starting or stopping triggers an immediate reading, so
            // the card follows within a moment instead of at the next poll.
            app.getSystemService(android.media.AudioManager::class.java)
                ?.registerAudioPlaybackCallback(audioChanged, Bg.main)
        } else {
            apply(SpotifySource.snap())
        }
    }

    private fun stop() {
        started = false
        if (polling) app.getSystemService(android.media.AudioManager::class.java)?.unregisterAudioPlaybackCallback(audioChanged)
        polling = false
        Bg.main.removeCallbacks(poll)
        SpotifySource.unregister()
        SpotifySource.onChange = null
        msm?.removeOnActiveSessionsChangedListener(sessionsChanged)
        msm = null
        for (c in controllers) c.unregisterCallback(callback)
        controllers = emptyList()
        chosen = null
        Bg.main.removeCallbacks(lineTick)
        Bg.main.removeCallbacks(hideLater)
    }

    // Source: dumpsys media_session, polled every 2 s while playing, 5 s otherwise.

    private val audioChanged = object : android.media.AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
            if (!polling) return
            Bg.main.removeCallbacks(poll)
            Bg.main.postDelayed(poll, 400)
        }
    }

    private var pollBusy = false

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (!polling) return
            if (!pollBusy) {
                pollBusy = true
                Bg.load({ DumpSource.read() }) { snap ->
                    pollBusy = false
                    if (polling) apply(mergeSpotify(snap))
                }
            }
            Bg.main.postDelayed(this, if (playing) 2000L else 5000L)
        }
    }

    /** Spotify's broadcast, when it matches, adds the length the dump lacks. */
    private fun mergeSpotify(s: Snap?): Snap? {
        if (s == null) return null
        if (!s.pkg.startsWith("com.spotify") || !SpotifySource.matches(s.title, s.artist)) return s
        return Snap(s.pkg, s.title, s.artist, s.album.ifEmpty { SpotifySource.album }, SpotifySource.lengthMs,
            s.state, s.position, s.updated, s.speed)
    }

    // Source: media session API.

    private fun setControllers(list: List<MediaController>) {
        for (c in controllers) c.unregisterCallback(callback)
        controllers = list.filter { it.packageName != app.packageName }
        for (c in controllers) c.registerCallback(callback, Bg.main)
        fromControllers()
    }

    private fun fromControllers() {
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { isActive(it.playbackState?.state ?: 0) && it.metadata != null }
        chosen = c
        val md = c?.metadata
        val st = c?.playbackState
        if (c == null || md == null || st == null) {
            apply(null)
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        apply(Snap(c.packageName, title, artist, md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0), st.state, st.position,
            if (st.lastPositionUpdateTime > 0) st.lastPositionUpdateTime else SystemClock.elapsedRealtime(),
            st.playbackSpeed, art, uri))
    }

    private fun isActive(state: Int): Boolean = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_CONNECTING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    // Common path for every source.

    private fun apply(s: Snap?) {
        if (s == null || s.title.isBlank() || !isActive(s.state)) {
            // Players briefly report STOPPED between tracks; wait before hiding.
            if (track != null) {
                Bg.main.removeCallbacks(hideLater)
                Bg.main.postDelayed(hideLater, 1500)
            }
            setPlaying(false, null)
            return
        }
        Bg.main.removeCallbacks(hideLater)
        val key = "${s.pkg}|${s.title}|${s.artist}"
        var t = track
        if (t == null || t.key != key) {
            t = Track(key, s.pkg, s.title, s.artist, s.album, s.durationMs)
            track = t
            line = -1
            for (l in listeners.toTypedArray()) l.onTrack(t)
            prepare(t, s)
        } else {
            if (s.durationMs > 0 && t.durationMs == 0L) t.durationMs = s.durationMs
            if (s.album.isNotEmpty() && t.album.isEmpty()) t.album = s.album
            if ((s.art != null || s.artUri != null) && t.art == null) loadArt(t, s.art, s.artUri)
        }
        setPlaying(s.state == PlaybackState.STATE_PLAYING, s)
    }

    private val hideLater = Runnable {
        track = null
        line = -1
        playing = false
        Bg.main.removeCallbacks(lineTick)
        for (l in listeners.toTypedArray()) l.onTrack(null)
    }

    private fun setPlaying(p: Boolean, s: Snap?) {
        if (s != null) {
            val old = position()
            posBase = s.position
            posAt = s.updated
            speed = if (s.speed > 0f) s.speed else 1f
            // The dump repeats the same reading between real updates; only a
            // jump of more than a second counts as a seek worth resyncing.
            val jumped = Math.abs(position() - old) > 1000
            if (p != playing || jumped) {
                playing = p
                for (l in listeners.toTypedArray()) l.onPlayback(p)
            }
        } else if (playing) {
            posBase = position()
            posAt = SystemClock.elapsedRealtime()
            playing = false
            for (l in listeners.toTypedArray()) l.onPlayback(false)
        }
        tickLine(true)
    }

    fun position(): Long {
        var pos = posBase
        if (playing && posAt > 0) pos += ((SystemClock.elapsedRealtime() - posAt) * speed).toLong()
        val d = track?.durationMs ?: 0
        return if (d > 0) pos.coerceIn(0, d) else pos.coerceAtLeast(0)
    }

    fun speed(): Float = speed

    private val lineTick = Runnable { tickLine(true) }

    private fun tickLine(animate: Boolean) {
        Bg.main.removeCallbacks(lineTick)
        val t = track ?: return
        val lyr = t.lyrics ?: return
        val pos = position() + LEAD_MS
        val idx = lyr.indexAt(pos)
        if (idx != line) {
            line = idx
            for (l in listeners.toTypedArray()) l.onLine(idx, animate)
        }
        if (playing) {
            val next = lyr.timeOf(idx + 1)
            if (next != Long.MAX_VALUE) {
                val delay = ((next - pos) / speed).toLong().coerceAtLeast(16)
                Bg.main.postDelayed(lineTick, delay)
            }
        }
    }

    /** Artwork, missing length and lyrics, in that order, on the background thread. */
    private fun prepare(t: Track, s: Snap) {
        if (s.art != null || s.artUri != null) loadArt(t, s.art, s.artUri)
        if (t.durationMs > 0 && (s.art != null || s.artUri != null)) loadLyrics(t) else enrich(t)
    }

    private fun enrich(t: Track) {
        Bg.load({ Enrich.lookup(app, t.title, t.artist) }) { info ->
            if (track !== t) return@load
            t.enriched = true
            if (info != null) {
                if (t.durationMs == 0L && info.durationMs > 0) t.durationMs = info.durationMs
                if (t.album.isEmpty()) t.album = info.album
                if (t.art == null && info.art != null) loadArt(t, info.art, null)
                for (l in listeners.toTypedArray()) l.onPlayback(playing)
            }
            if (t.art == null && t.artFrom == null) {
                t.artDone = true
                for (l in listeners.toTypedArray()) l.onArt(t)
            }
            loadLyrics(t)
        }
    }

    private fun loadLyrics(t: Track) {
        if (t.title.isBlank() || t.lyricsDone) return
        Bg.load({ LyricsRepo.get(app, t.title, t.artist, t.album, t.durationMs) }) { lyr ->
            if (track !== t) return@load
            t.lyrics = lyr
            t.lyricsDone = true
            line = -1
            for (l in listeners.toTypedArray()) l.onLyrics(t)
            tickLine(false)
        }
    }

    private var artLoading: Track? = null

    private fun loadArt(t: Track, bmp: Bitmap?, uri: String?) {
        val from: Any = bmp ?: uri ?: return
        if (artLoading === t || from == t.artFrom) return
        t.artFrom = from
        artLoading = t
        Bg.load({
            val src = bmp ?: loadUri(uri!!)
            if (src == null) null else {
                val art = Images.square(src, minOf(384, maxOf(src.width, src.height)))
                val sw = Images.swatches(art)
                val blur = Images.blurred(art, 40, 40, 6)
                Triple(art, sw, blur)
            }
        }) { res ->
            if (artLoading === t) artLoading = null
            if (track !== t) return@load
            if (res != null) {
                t.art = res.first
                t.accent = res.second.accent
                t.dark = res.second.dark
                t.vibrant = res.second.vibrant
                t.blur = res.third
            }
            t.artDone = true
            for (l in listeners.toTypedArray()) l.onArt(t)
        }
    }

    private fun loadUri(uri: String): Bitmap? {
        val u = Uri.parse(uri)
        return Images.decodeCover({
            when (u.scheme) {
                "http", "https" -> URL(uri).openStream()
                else -> app.contentResolver.openInputStream(u)
            }
        }, 384, 384)
    }

    fun togglePlay() {
        val c = chosen ?: return
        if (playing) c.transportControls.pause() else c.transportControls.play()
    }
}
