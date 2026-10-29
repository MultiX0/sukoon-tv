package dev.multix.tvhome

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.multix.tvhome.apps.AppEntry
import dev.multix.tvhome.apps.Apps
import dev.multix.tvhome.core.Bg
import dev.multix.tvhome.core.Fonts
import dev.multix.tvhome.core.Images
import dev.multix.tvhome.core.Lang
import dev.multix.tvhome.core.Look
import dev.multix.tvhome.core.Motion
import dev.multix.tvhome.core.Prefs
import dev.multix.tvhome.core.dp
import dev.multix.tvhome.core.frame
import dev.multix.tvhome.core.margins
import dev.multix.tvhome.core.refreshFonts
import dev.multix.tvhome.core.roundClip
import dev.multix.tvhome.media.NowPlaying
import dev.multix.tvhome.media.Track
import dev.multix.tvhome.ui.CosmosLyricsView
import dev.multix.tvhome.ui.AppCard
import dev.multix.tvhome.ui.AppsOverlay
import dev.multix.tvhome.ui.Area
import dev.multix.tvhome.ui.CardSpec
import dev.multix.tvhome.ui.CityRow
import dev.multix.tvhome.ui.ClockView
import dev.multix.tvhome.ui.CustomizeOverlay
import dev.multix.tvhome.ui.DemoTrack
import dev.multix.tvhome.ui.DockItem
import dev.multix.tvhome.ui.DockView
import dev.multix.tvhome.ui.MenuOverlay
import dev.multix.tvhome.ui.NowPlayingCard
import dev.multix.tvhome.ui.NowPlayingPill
import dev.multix.tvhome.ui.OverlayView
import dev.multix.tvhome.ui.PillButton
import dev.multix.tvhome.ui.Samples
import dev.multix.tvhome.ui.SettingsPanel
import dev.multix.tvhome.ui.isConfirm
import dev.multix.tvhome.wall.WallSet
import dev.multix.tvhome.wall.WallpaperView
import dev.multix.tvhome.wall.Walls
import dev.multix.tvhome.weather.Weather
import kotlin.math.abs

class HomeActivity : Activity(), NowPlaying.Listener {

    private lateinit var root: FrameLayout
    /** Everything that makes up the home screen; Customize shrinks it into a framed preview. */
    private lateinit var stage: FrameLayout
    private lateinit var wall: WallpaperView
    private lateinit var home: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var clock: ClockView
    private lateinit var npCard: NowPlayingCard
    private lateinit var npPill: NowPlayingPill
    private lateinit var gear: PillButton
    private lateinit var dock: DockView

    private lateinit var appsOverlay: AppsOverlay
    private lateinit var settings: SettingsPanel
    private lateinit var menu: MenuOverlay
    private lateinit var customize: CustomizeOverlay
    private lateinit var ambient: CosmosLyricsView
    private var ambientShowing = false

    private val stack = ArrayList<OverlayView>()
    private var focusBefore: View? = null
    private var started = false
    private var closeGridOnStop = false
    private var longFired = false
    private var demo: Track? = null
    private var takeoverKey: String? = null
    private var langAtStart = ""

    private var sharpWall: Bitmap? = null
    private var wallBlur: Bitmap? = null
    private var wallAccent = 0xFFFFFFFF.toInt()
    private var previewId: String? = null
    private var wallShownId: String? = null
    private var lastWallChange = 0L
    private var screenW = 1280
    private var screenH = 720

    private var weatherNow: Weather.Now? = null
    private var weatherUnitF = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.multix.tvhome.core.Ui.init(this)
        langAtStart = Prefs.langResolved
        val dm = resources.displayMetrics
        screenW = minOf(dm.widthPixels, 1280)
        screenH = minOf(dm.heightPixels, 720)
        CardSpec.init()
        buildUi()
        setContentView(root)

        lastWall?.let { b ->
            sharpWall = b
            wall.show(lastShown ?: b, 0)
            wallBlur = lastBlur
            wallShownId = lastWallId
            wallAccent = lastAccent
            onWallpaperShown()
        }
        loadWallpapers()
        if (Apps.all.isNotEmpty()) bindDock()
        reloadApps()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packages, filter)

        reopenSettings?.let { page ->
            reopenSettings = null
            root.post { openSettings(page, animate = false) }
        }
        reopenCustomize?.let { pos ->
            reopenCustomize = null
            root.post { openCustomize(pos.first, pos.second, animate = false) }
        }
        handleIntent(intent)
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.clipChildren = false

        stage = FrameLayout(this)
        stage.clipChildren = false
        root.addView(stage, frame(-1, -1))

        wall = WallpaperView(this)
        wall.motion = Prefs.wallMotion
        wall.setDim(Look.wallDim / 10f)
        stage.addView(wall, frame(-1, -1))

        // Soft scrims behind the clock and the dock keep text readable on any photo.
        val corner = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(0x99000000.toInt(), 0x47000000, 0x00000000)
            gradientRadius = 760f.dp
            setGradientCenter(if (Lang.isArabic) 1f else 0f, 0.08f)
        }
        val bottom = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xA6000000.toInt(), 0x40000000, 0x00000000))
        val scrim = View(this)
        scrim.background = LayerDrawable(arrayOf(corner, bottom)).apply {
            setLayerGravity(1, Gravity.BOTTOM)
            setLayerHeight(1, 230.dp)
        }
        stage.addView(scrim, frame(-1, -1))

        home = FrameLayout(this)
        home.clipChildren = false
        home.clipToPadding = false
        stage.addView(home, frame(-1, -1))

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        clock = ClockView(this)
        clock.setOnClickListener { openCustomize(0, 0) }
        npCard = NowPlayingCard(this)
        npCard.button.setOnClickListener { startAmbient(force = true) }
        npPill = NowPlayingPill(this)
        npPill.setOnClickListener { startAmbient(force = true) }
        home.addView(column)
        home.addView(npPill, frame(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).margins(top = 30.dp))

        gear = PillButton(this, R.drawable.ic_gear, getString(R.string.settings))
        home.addView(gear, frame(-2, 36.dp, Gravity.TOP or Gravity.END).margins(end = 48.dp, top = 40.dp))
        gear.setOnClickListener { openSettings(null) }

        dock = DockView(this)
        dock.allAppsText = getString(R.string.all_apps)
        dock.onItemClick = { item ->
            val e = item.entry
            if (e == null) openApps(false) else launch(e, item.tileView())
        }
        dock.onItemFocus = { item -> item.entry?.let { Prefs.lastFocus = it.pkg } }
        home.addView(dock)
        layoutHome()

        appsOverlay = AppsOverlay(this)
        root.addView(appsOverlay, frame(-1, -1))
        settings = SettingsPanel(this)
        root.addView(settings, frame(-1, -1))
        customize = CustomizeOverlay(this)
        root.addView(customize, frame(-1, -1))
        menu = MenuOverlay(this)
        root.addView(menu, frame(-1, -1))
        ambient = CosmosLyricsView(this)
        ambient.visibility = View.GONE
        root.addView(ambient, frame(-1, -1))
    }

    /** Places the clock, the now playing card and the dock for the current Look. */
    private fun layoutHome() {
        dock.configure()
        val vertical = Look.dockVertical
        val list = Look.dockStyle == "list"
        val sideDock = vertical && !list
        val labels = Look.dockLabels
        val dockW = when (Look.dockStyle) {
            "cards" -> CardSpec.w
            "pill" -> 54.dp + 28.dp
            "icons" -> 76.dp
            else -> 0
        }
        // The side dock is drawn at DockView.SIDE_SCALE, so the clock column starts that much closer.
        val start = if (sideDock) 40.dp + ((dockW + (if (labels) 190.dp else 40.dp)) * DockView.SIDE_SCALE).toInt() else 56.dp
        val bottomSpace = if (vertical) 40.dp else when (Look.dockStyle) {
            "pill" -> 26.dp + 82.dp + 30.dp
            "icons" -> 34.dp + 76.dp + (if (labels) 30.dp else 0) + 24.dp
            else -> 34.dp + CardSpec.h + (if (labels) 32.dp else 0) + 24.dp
        }
        val pad = ClockView.PAD.dp

        // Dock. On the side it is drawn at SIDE_SCALE about its own middle, so it is laid out 1/SIDE_SCALE as tall
        // as the space it should fill on screen; then whether the apps fit is judged on the real space.
        fun side(start: Int, top: Int, bottom: Int): FrameLayout.LayoutParams {
            val visible = resources.displayMetrics.heightPixels - top - bottom
            val h = (visible / DockView.SIDE_SCALE).toInt()
            return frame(-2, h, Gravity.TOP or Gravity.START).margins(start = start, top = top + visible / 2 - h / 2)
        }
        dock.layoutParams = when {
            list -> side(56.dp - 2.dp, 250.dp, 40.dp)
            vertical -> side(40.dp, 30.dp, 30.dp)
            Look.dockStyle == "pill" -> frame(-1, -2, Gravity.BOTTOM).margins(bottom = 26.dp)
            else -> frame(-1, -2, Gravity.BOTTOM).margins(bottom = 34.dp)
        }

        // Now playing card: under the clock, above it when the clock sits low,
        // or on the right when the text list takes the left side.
        (npCard.parent as? android.view.ViewGroup)?.removeView(npCard)
        val cardW = if (sideDock) minOf(600.dp, resources.displayMetrics.widthPixels - start - 48.dp) else 600.dp
        val pos = Look.clockPos
        column.removeView(clock)
        if (list) {
            // Beside the clock rather than under it, so keep clear of the digits.
            home.addView(npCard, frame(minOf(cardW, 470.dp), 172.dp, Gravity.TOP or Gravity.END).margins(end = 48.dp, top = 96.dp))
            column.addView(clock)
        } else if (pos == "bl") {
            column.addView(npCard, LinearLayout.LayoutParams(cardW, 172.dp).apply { bottomMargin = 18.dp; marginStart = pad })
            column.addView(clock)
        } else {
            column.addView(clock)
            column.addView(npCard, LinearLayout.LayoutParams(cardW, 172.dp).apply {
                topMargin = 22.dp - pad
                marginStart = pad
                gravity = if (pos == "center") Gravity.CENTER_HORIZONTAL else Gravity.START
            })
        }
        column.gravity = if (pos == "center") Gravity.CENTER_HORIZONTAL else Gravity.START
        column.layoutParams = when (pos) {
            "center" -> frame(-2, -2, Gravity.CENTER).margins(
                start = if (sideDock) start - 56.dp else 0,
                bottom = if (vertical) 0 else bottomSpace - 60.dp)
            "bl" -> frame(-2, -2, Gravity.BOTTOM or Gravity.START).margins(start = start - pad, bottom = bottomSpace - pad)
            else -> frame(-2, -2, Gravity.TOP or Gravity.START).margins(start = start - pad, top = 34.dp - pad)
        }
        home.requestLayout()
    }

    override fun onStart() {
        super.onStart()
        started = true
        clock.start()
        wall.setRunning(!ambientShowing && home.visibility == View.VISIBLE)
        NowPlaying.addListener(this)
        NowPlaying.track?.let { onTrack(it); if (it.artDone) onArt(it); if (it.lyricsDone) onLyrics(it) }
        scheduleWall()
        refreshWeather(false)
        resetIdle()
    }

    override fun onResume() {
        super.onResume()
        // Newly installed apps show up without waiting for a broadcast.
        reloadApps()
    }

    override fun onStop() {
        super.onStop()
        started = false
        clock.stop()
        wall.setRunning(false)
        NowPlaying.removeListener(this)
        Bg.main.removeCallbacks(wallTick)
        Bg.main.removeCallbacks(idle)
        Bg.main.removeCallbacks(weatherTick)
        npCard.setPaused()
        if (ambientShowing) hideAmbient(false)
        if (menu.isOpen) menu.dismiss(false)
        if (closeGridOnStop && appsOverlay.isOpen) appsOverlay.dismiss(false)
        closeGridOnStop = false
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packages)
        ambient.release()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (handleIntent(intent)) return
        // Home pressed while already home: settle back to the plain home screen.
        if (ambientShowing) hideAmbient(true)
        for (o in stack.toList().reversed()) o.dismiss(true)
        if (!dock.contains(currentFocus)) focusDock()
    }

    /** `--ez customize true` opens the editor directly, which makes recordings easy to script. */
    private fun handleIntent(i: Intent?): Boolean {
        // `--es update_api http://127.0.0.1:8099` points update checks at a local mock of the GitHub API (tests).
        i?.getStringExtra("update_api")?.let {
            i.removeExtra("update_api")
            dev.multix.tvhome.update.Updater.setTestApi(this, it)
        }
        // `--es settings about` opens a settings page directly (wallpaper, clock, about...).
        i?.getStringExtra("settings")?.let { page ->
            i.removeExtra("settings")
            if (ambientShowing) hideAmbient(false)
            root.post {
                for (o in stack.toList().reversed()) o.dismiss(false)
                openSettings(page.ifEmpty { null }, animate = false)
            }
            return true
        }
        // `--ez nextwall true` crossfades to the next photo now; used to measure the crossfade.
        if (i?.getBooleanExtra("nextwall", false) == true) {
            i.removeExtra("nextwall")
            root.post { wallTick.run() }
            return true
        }
        // `--ez lyrics true [--ei sample N]` opens the full screen lyrics, with one of
        // the sample songs when nothing plays (or always, when a sample is named).
        if (i?.getBooleanExtra("lyrics", false) == true) {
            i.removeExtra("lyrics")
            val n = if (i.hasExtra("sample")) i.getIntExtra("sample", 0) else -1
            i.removeExtra("sample")
            root.post {
                if (ambientShowing && n >= 0) hideAmbient(false)
                startAmbient(force = true, sample = n)
            }
            return true
        }
        if (i?.getBooleanExtra("customize", false) != true) return false
        i.removeExtra("customize")
        if (ambientShowing) hideAmbient(false)
        for (o in stack.toList().reversed()) if (o !== customize) o.dismiss(false)
        root.post { openCustomize(0, 0) }
        return true
    }

    // Keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        resetIdle()
        val code = e.keyCode
        if (ambientShowing) {
            if (isMediaKey(code)) return super.dispatchKeyEvent(e)
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) hideAmbient(true)
            return true
        }
        // Long press comes from the key repeat itself, so it works with a remote
        // held down and with "input keyevent --longpress" alike.
        if (isConfirm(code)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) longFired = false
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                if (!longFired) {
                    longFired = true
                    val f = currentFocus
                    f?.isPressed = false
                    onLongPress(f)
                }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP && longFired) {
                longFired = false
                currentFocus?.isPressed = false
                return true
            }
        }
        val top = stack.lastOrNull()
        if (top != null) {
            if (code == KeyEvent.KEYCODE_MENU && top === appsOverlay) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) openSettings(null)
                return true
            }
            if (top.onKey(e)) return true
            return super.dispatchKeyEvent(e)
        }
        if (e.action == KeyEvent.ACTION_DOWN && handleHomeKey(e)) return true
        if (code == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(e)
    }

    private fun onLongPress(v: View?) {
        when {
            v === clock -> openCustomize(0, 0)
            v is DockItem && v.entry != null -> showAppMenu(v, fromDock = dock.contains(v))
            v is AppCard && v.entry != null -> showAppMenu(v, fromDock = false)
        }
    }

    // KeyEvent.isMediaSessionKey() only exists from Android 11; on Android 10 it crashed every key press over the
    // lyrics takeover. The same keys, listed by hand.
    private fun isMediaKey(code: Int) = when (code) {
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_RECORD,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> false
    }

    private fun handleHomeKey(e: KeyEvent): Boolean {
        val f = currentFocus
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_MENU) {
            if (e.repeatCount == 0) openSettings(null)
            return true
        }
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> {
                if (f == null && code != KeyEvent.KEYCODE_BACK && !isMediaKey(code)) focusDock()
                return false
            }
        }
        if (f == null) {
            focusDock()
            return true
        }
        if (dock.contains(f)) {
            val along = if (dock.vertical) dir == View.FOCUS_UP || dir == View.FOCUS_DOWN
            else dir == View.FOCUS_LEFT || dir == View.FOCUS_RIGHT
            if (along) {
                val step = when (dir) {
                    View.FOCUS_DOWN -> 1
                    View.FOCUS_UP -> -1
                    View.FOCUS_RIGHT -> if (rtl) -1 else 1
                    else -> if (rtl) 1 else -1
                }
                if (dock.move(f, step)) navSound(dir)
                return true
            }
            // Down from a bottom dock opens every app.
            if (!dock.vertical && dir == View.FOCUS_DOWN) {
                openApps(false)
                navSound(dir)
                return true
            }
        }
        val target = nearest(f, dir)
        if (target == null) {
            if (dock.contains(f)) dock.itemOf(f)?.nudge(if (dir == View.FOCUS_LEFT || dir == View.FOCUS_UP) -1 else 1,
                dir == View.FOCUS_UP || dir == View.FOCUS_DOWN)
            return true
        }
        if (target === dock) focusDock() else target.requestFocus()
        navSound(dir)
        return true
    }

    /**
     * Picks the closest focus target in a direction by on-screen geometry, so
     * every clock, dock and card arrangement navigates the same way.
     */
    private fun nearest(from: View, dir: Int): View? {
        val src = rectOf(from)
        val candidates = ArrayList<Pair<View, RectF>>()
        for (v in listOf<View>(gear, clock, npCard.button, npPill)) {
            if (v === from || !v.isShown || (v === npCard.button && !npCard.showing) || (v === npPill && !npPill.showing)) continue
            if (demo != null && (v === npCard.button || v === npPill)) continue
            candidates.add(v to rectOf(v))
        }
        if (!dock.contains(from) && dock.tiles().isNotEmpty()) candidates.add(dock to rectOf(dock.outlineView()))
        var best: View? = null
        var bestScore = Float.MAX_VALUE
        for ((v, r) in candidates) {
            val along: Float
            val across: Float
            when (dir) {
                View.FOCUS_LEFT -> { along = src.left - r.right; across = abs(r.centerY() - src.centerY()) }
                View.FOCUS_RIGHT -> { along = r.left - src.right; across = abs(r.centerY() - src.centerY()) }
                View.FOCUS_UP -> { along = src.top - r.bottom; across = abs(r.centerX() - src.centerX()) }
                else -> { along = r.top - src.bottom; across = abs(r.centerX() - src.centerX()) }
            }
            if (along < -8f.dp) continue
            val score = maxOf(along, 0f) + across * 0.6f
            if (score < bestScore) {
                bestScore = score
                best = v
            }
        }
        return best
    }

    private fun rectOf(v: View): RectF {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        // Every scale between the view and the window: the Customize preview and the 0.8 side dock.
        var sx = 1f
        var sy = 1f
        var p: View? = v
        while (p != null) {
            sx *= p.scaleX
            sy *= p.scaleY
            p = p.parent as? View
        }
        return RectF(loc[0].toFloat(), loc[1].toFloat(), loc[0] + v.width * sx, loc[1] + v.height * sy)
    }

    fun navSound(direction: Int) {
        root.playSoundEffect(SoundEffectConstants.getContantForFocusDirection(direction))
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetIdle()
    }

    // Dock

    private fun focusDock() {
        if (!dock.focus(Prefs.lastFocus)) gear.requestFocus()
    }

    fun refreshDock() {
        bindDock()
        if (appsOverlay.isOpen) appsOverlay.sync()
    }

    private fun dockEntries(): List<AppEntry> = Apps.sorted(Apps.dock().mapNotNull { Apps.find(it) }, forDock = true)

    private fun bindDock() {
        val focused = currentFocus
        val first = dock.bind(dockEntries())
        if (first) {
            dock.alpha = 0f
            dock.translationY = 16f.dp
            dock.animate().alpha(1f).translationY(0f).setDuration(Motion.SLOW + 100).setInterpolator(Motion.out).start()
            // The window hands initial focus to the first focusable view; the dock
            // is where a launcher should start.
            if (stack.isEmpty()) dock.post { if (stack.isEmpty() && !dock.contains(currentFocus)) focusDock() }
        }
        if (dock.contains(focused)) {
            focused?.requestFocus()
            dock.post { focused?.let { dock.scrollTo(it) } }
        } else if ((focused == null || !focused.isAttachedToWindow) && stack.isEmpty()) {
            focusDock()
        }
        renderDockIcons()
    }

    /** Rebuilds the dock after a style change, keeping the remembered app in focus. */
    private fun rebuildDock() {
        val hadFocus = dock.contains(currentFocus)
        layoutHome()
        bindDock()
        if (hadFocus) dock.post { focusDock() }
    }

    private fun renderDockIcons() {
        val size = dock.iconSize
        if (size == 0) return
        val shape = dock.iconShape
        val entries = dockEntries()
        val px = (size * 1.25f).toInt()
        Bg.run {
            for (e in entries) {
                if (e.iconKey == "$shape:$size" && e.icon != null) continue
                if (e.card == null) Apps.render(this, e)
                Apps.renderIcon(this, e, size, shape, px)
                Bg.ui { dock.cardReady(e) }
            }
        }
    }

    private fun reloadApps() {
        Bg.load({ val changed = Apps.scan(this); Apps.loadUsage(this); changed }) { changed ->
            if (isDestroyed) return@load
            if (changed || dock.items.isEmpty() || Look.sortApps == "recent") {
                bindDock()
                if (appsOverlay.isOpen) appsOverlay.sync()
            }
            renderCards()
        }
    }

    /** Re-renders every app tile, for the banner or icon choice. */
    fun redrawCards() {
        Bg.run { Apps.forgetCards() }
        renderCards()
        renderDockIcons()
    }

    private fun renderCards() {
        Bg.run {
            Apps.renderAll(this, Apps.dock()) { e ->
                Bg.ui {
                    dock.cardReady(e)
                    appsOverlay.cardReady(e.pkg)
                }
            }
        }
    }

    fun launch(e: AppEntry, from: View) {
        val intent = Apps.launchIntent(this, e) ?: return
        val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        try {
            startActivity(intent, opts)
            closeGridOnStop = appsOverlay.isOpen
        } catch (ex: Exception) {
            Bg.warn("launch failed for ${e.pkg}", ex)
        }
    }

    fun showAppMenu(item: DockItem, fromDock: Boolean) {
        val e = item.entry ?: return
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val items = ArrayList<MenuOverlay.Item>()
        if (fromDock) {
            val vertical = dock.vertical
            val back = if (vertical) R.string.menu_up else R.string.menu_left
            val fwd = if (vertical) R.string.menu_down else R.string.menu_right
            items.add(MenuOverlay.Item(getString(back), if (vertical) R.drawable.ic_arrow_up else R.drawable.ic_arrow_left, keepOpen = true) {
                moveInDock(item, if (rtl && !vertical) 1 else -1)
            })
            items.add(MenuOverlay.Item(getString(fwd), if (vertical) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right, keepOpen = true) {
                moveInDock(item, if (rtl && !vertical) -1 else 1)
            })
            items.add(MenuOverlay.Item(getString(R.string.menu_remove), R.drawable.ic_remove) {
                Apps.removeFromDock(e.pkg)
                refreshDock()
                focusDock()
            })
        } else if (Apps.inDock(e.pkg)) {
            items.add(MenuOverlay.Item(getString(R.string.menu_remove), R.drawable.ic_remove) {
                Apps.removeFromDock(e.pkg)
                refreshDock()
            })
        } else {
            items.add(MenuOverlay.Item(getString(R.string.menu_add), R.drawable.ic_add) {
                Apps.addToDock(e.pkg)
                refreshDock()
            })
        }
        if (Apps.isHidden(e.pkg)) {
            items.add(MenuOverlay.Item(getString(R.string.menu_unhide), R.drawable.ic_eye) {
                Apps.setHidden(e.pkg, false)
                refreshDock()
            })
        } else {
            items.add(MenuOverlay.Item(getString(R.string.menu_hide), R.drawable.ic_eye_off) {
                Apps.setHidden(e.pkg, true)
                refreshDock()
                if (fromDock) focusDock()
            })
        }
        items.add(MenuOverlay.Item(getString(R.string.menu_info), R.drawable.ic_info) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", e.pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ex: Exception) {
                Bg.warn("no app info screen", ex)
            }
        })
        pushOverlay(menu)
        menu.show(item.tileView(), e.label, items)
    }

    private fun moveInDock(item: DockItem, delta: Int) {
        val e = item.entry ?: return
        if (Look.sortApps != "custom") Look.sortApps = "custom"
        if (!Apps.moveInDock(e.pkg, delta)) {
            item.nudge(delta, dock.vertical)
            return
        }
        bindDock()
        dock.post { dock.scrollTo(item.view) }
        menu.follow(item.tileView())
    }

    // Overlays

    private fun pushOverlay(o: OverlayView) {
        if (stack.isEmpty()) focusBefore = currentFocus
        stack.remove(o)
        stack.add(o)
    }

    fun overlayClosed(o: OverlayView, wasEdit: Boolean = false) {
        stack.remove(o)
        if (o === appsOverlay) showHome(true)
        if (wasEdit) {
            openSettings("dock")
            return
        }
        val top = stack.lastOrNull()
        if (top != null) {
            if (top === appsOverlay) appsOverlay.focusedCard()?.requestFocus() else top.requestFocus()
            return
        }
        val back = focusBefore
        focusBefore = null
        if (back != null && back.isAttachedToWindow && back.isShown &&
            (dock.contains(back) || back === gear || back === clock || (back === npCard.button && npCard.showing) || (back === npPill && npPill.showing))) {
            back.requestFocus()
        } else {
            focusDock()
        }
        resetIdle()
    }

    fun openApps(edit: Boolean) {
        if (appsOverlay.isOpen) return
        appsOverlay.setFrost(wallBlur)
        pushOverlay(appsOverlay)
        appsOverlay.open(edit)
        // The grid is opaque; stop drawing the home screen under it once it has faded in.
        root.postDelayed({ if (appsOverlay.isOpen) showHome(false) }, Motion.SLOW + 40)
    }

    private fun showHome(show: Boolean) {
        val v = if (show) View.VISIBLE else View.INVISIBLE
        stage.visibility = v
        wall.setRunning(show && started && !ambientShowing)
    }

    fun openSettings(page: String?, animate: Boolean = true) {
        if (settings.isOpen) return
        settings.setFrost(wallBlur)
        pushOverlay(settings)
        settings.open(page, animate)
    }

    /** Android's "install unknown apps" switch for this app, for the updater. False when the TV has no such screen. */
    fun openInstallPermission(): Boolean = try {
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
        true
    } catch (e: Exception) {
        false
    }

    fun openCustomize(part: Int, opt: Int, animate: Boolean = true) {
        if (customize.isOpen) return
        if (ambientShowing) hideAmbient(false)
        for (o in stack.toList().reversed()) o.dismiss(false)
        pushOverlay(customize)
        customize.open(part, opt, animate)
    }

    /** Shrinks the home screen into a framed preview while Customize is open. */
    fun setEditing(on: Boolean, animate: Boolean) {
        val d = if (animate) 450L else 0L
        if (on) {
            root.setBackgroundColor(0xFF050608.toInt())
            stage.roundClip(22f.dp)
            stage.elevation = 20f.dp
            stage.animate().scaleX(0.8f).scaleY(0.8f).translationY(-8f.dp).setDuration(d).setInterpolator(Motion.out).start()
        } else {
            previewPart(null)
            stage.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(d).setInterpolator(Motion.out).withEndAction {
                stage.clipToOutline = false
                stage.outlineProvider = null
                stage.elevation = 0f
                root.background = null
            }.start()
        }
    }

    /** Screen rectangle of a home screen part, for the Customize frame. */
    fun areaRect(a: Area): RectF = when (a) {
        Area.CLOCK -> listOf(clock.greeting, clock.timeView(), clock.date).filter { it.isShown }
            .map { rectOf(it) }.reduce { r, n -> r.union(n); r }
        Area.DATE -> {
            val views = listOf(clock.greeting, clock.date).filter { it.isShown }
            if (views.isEmpty()) rectOf(clock.timeView()) else views.map { rectOf(it) }.reduce { r, n -> r.union(n); r }
        }
        Area.DOCK -> rectOf(dock.outlineView())
        Area.NOW_PLAYING -> when {
            npPill.showing -> rectOf(npPill)
            npCard.showing -> rectOf(npCard)
            else -> rectOf(column)
        }
        else -> rectOf(stage).apply { inset(14f.dp, 14f.dp) }
    }

    /** While the now playing part is edited with nothing playing, show a sample. */
    fun previewPart(a: Area?) {
        val want = a == Area.NOW_PLAYING && NowPlaying.track == null
        if (want && demo == null) {
            demo = DemoTrack.make(this)
            showTrack(demo!!)
            npCard.onLine(1, false)
            npPill.onLine(1, false)
        } else if (!want && demo != null) {
            demo = null
            if (NowPlaying.track == null) {
                npCard.hide()
                npPill.hide()
                fitColumnSoon()
            }
        }
    }

    /** Re-applies Look options for one area, with a short crossfade. */
    fun applyLook(area: Area) {
        when (area) {
            Area.CLOCK, Area.DATE -> {
                clock.autoColor = wallAccent
                dip(clock) { clock.applyLook() }
            }
            Area.DOCK -> dip(dock) { rebuildDock() }
            Area.NOW_PLAYING -> {
                npCard.applyTextSize()
                ambient.applyTextSize()
            }
            Area.WALLPAPER -> {
                wall.motion = Prefs.wallMotion
                wall.setDim(Look.wallDim / 10f)
                refreshWallDisplay()
                scheduleWall()
            }
            Area.TEXT -> {
                if (Prefs.langResolved != langAtStart) {
                    reopenCustomize = customize.position()
                    restart()
                    return
                }
                Fonts.bump()
                dip(home) {
                    refreshFonts(root)
                    clock.applyLook()
                }
            }
            Area.ALL -> {
                dip(home) {
                    layoutHome()
                    clock.autoColor = wallAccent
                    clock.applyLook()
                    bindDock()
                    val t = demo ?: NowPlaying.track
                    if (t != null) showTrack(t)
                }
            }
        }
        refreshWeather(false)
        resetIdle()
        fitColumnSoon()
    }

    private fun dip(v: View, apply: () -> Unit) {
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(110).setInterpolator(Motion.accel).withEndAction {
            apply()
            v.animate().alpha(1f).setDuration(170).setInterpolator(Motion.out).start()
        }.start()
    }

    fun applyPreset(id: String) {
        val folder = Look.applyPreset(id)
        if (folder != null) Walls.sets.firstOrNull { it.folder == folder && !it.user }?.let { commitWallpaper(it.id) }
        if (Prefs.langResolved != langAtStart) {
            reopenCustomize = customize.position()
            restart()
            return
        }
        Fonts.bump()
        wall.motion = Prefs.wallMotion
        wall.setDim(Look.wallDim / 10f)
        refreshWallDisplay()
        dip(home) {
            refreshFonts(root)
            layoutHome()
            clock.autoColor = wallAccent
            clock.applyLook()
            bindDock()
            npCard.applyTextSize()
            val t = demo ?: NowPlaying.track
            if (t != null) showTrack(t)
        }
    }

    private fun restart() {
        lastWall = sharpWall
        recreate()
    }

    /**
     * Rebuilds the home screen after Presets.restore() wrote a saved look back. The screen comes back where the
     * user was (Customize or a settings page), and the restored wallpaper crossfades in over the old one.
     */
    fun applySavedLook(settingsPage: String? = null) {
        if (customize.isOpen) reopenCustomize = customize.position()
        else if (settingsPage != null) reopenSettings = settingsPage
        restart()
        lastWallId = null
    }

    fun openDockEditor() {
        settings.dismiss(true)
        root.postDelayed({ openApps(true) }, Motion.MED)
    }

    fun settingsEditFinished(c: CityRow) = settings.editFinished(c)

    fun restartForLanguage() {
        reopenSettings = "language"
        restart()
    }

    fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ex: Exception) {
            Bg.warn("no settings app", ex)
        }
    }

    fun openListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ex: Exception) {
            Bg.warn("no listener settings screen", ex)
        }
    }

    /** Re-reads every setting that changes the home screen. */
    fun applySettings() {
        wall.motion = Prefs.wallMotion
        scheduleWall()
        clock.stop()
        clock.applyLook()
        if (started) clock.start()
        npCard.applyTextSize()
        ambient.applyTextSize()
        NowPlaying.track?.let { npCard.bindLyrics(it); ambient.bindLyrics(it) }
        if (dock.configure()) rebuildDock() else bindDock()
        refreshWeather(false)
        resetIdle()
    }

    // Wallpaper

    private fun loadWallpapers() {
        Bg.load({ Walls.scan(this) }) { _ ->
            if (isDestroyed) return@load
            val set = Walls.find(Prefs.wallSet) ?: return@load
            if (wallShownId != set.id || wall.current == null) {
                showWallpaper(set, Prefs.wallIndex, if (wall.current == null) 900 else 1200)
            }
            scheduleWall()
        }
    }

    private class Shown(val sharp: Bitmap, val display: Bitmap, val blur: Bitmap, val accent: Int, val level: Int)

    private fun prepare(sharp: Bitmap): Shown {
        val level = Look.wallBlur
        val small = Images.blurred(sharp, 128, 72, 8)
        val sw = Images.swatches(small)
        val accent = Images.deepen(sw.vibrant, 0.84f, 0.55f)
        return Shown(sharp, blurredFor(sharp, level), small, accent, level)
    }

    /** The wallpaper softened for the Blur option: a small copy blurred once, stretched by the GPU. */
    private fun blurredFor(sharp: Bitmap, level: Int): Bitmap {
        if (level <= 0) return sharp
        val div = intArrayOf(1, 4, 6, 8, 11, 15)[level.coerceIn(0, 5)]
        val radius = intArrayOf(0, 2, 3, 4, 5, 6)[level.coerceIn(0, 5)]
        return Images.blurred(sharp, sharp.width / div, sharp.height / div, radius)
    }

    private fun showWallpaper(set: WallSet, index: Int, fadeMs: Long) {
        Bg.load({ Walls.decode(this, set, index, screenW, screenH)?.let { prepare(it) } }) { r ->
            if (r == null || isDestroyed) return@load
            show(r, set.id, fadeMs)
        }
    }

    private fun show(r: Shown, id: String, fadeMs: Long) {
        wall.show(r.display, fadeMs)
        shownBlur = r.level
        sharpWall = r.sharp
        wallBlur = r.blur
        wallShownId = id
        val accentChanged = wallAccent != r.accent
        wallAccent = r.accent
        lastWall = r.sharp
        lastShown = r.display
        lastBlur = r.blur
        lastWallId = id
        lastAccent = r.accent
        onWallpaperShown()
        if (accentChanged && Look.clockColor == "auto") {
            clock.autoColor = wallAccent
            clock.applyLook()
        }
    }

    /** Re-renders the current photo when the blur level changed; dim is a filter and needs nothing. */
    private fun refreshWallDisplay() {
        if (Look.wallBlur == shownBlur) return
        val sharp = sharpWall ?: return
        val id = wallShownId ?: return
        Bg.load({ prepare(sharp) }) { r -> if (!isDestroyed && wallShownId == id) show(r, id, 300) }
    }

    private var shownBlur = -1

    private fun onWallpaperShown() {
        // The wallpaper now covers the window, so the window background is pure overdraw.
        window.setBackgroundDrawable(null)
        settings.setFrost(wallBlur)
        appsOverlay.setFrost(wallBlur)
        dock.setFrost(wallBlur)
        ambient.setWallpaper(wall.current)
        clock.autoColor = wallAccent
    }

    private val wallTick = Runnable {
        val set = Walls.find(Prefs.wallSet)
        if (set != null && previewId == null && started) {
            Prefs.wallIndex = (Prefs.wallIndex + 1) % set.files.size
            lastWallChange = SystemClock.elapsedRealtime()
            showWallpaper(set, Prefs.wallIndex, 2200)
        }
        scheduleWall()
    }

    private fun scheduleWall() {
        Bg.main.removeCallbacks(wallTick)
        val minutes = Prefs.wallInterval
        if (minutes <= 0 || !started) return
        val period = minutes * 60_000L
        if (lastWallChange == 0L) lastWallChange = SystemClock.elapsedRealtime()
        val due = lastWallChange + period - SystemClock.elapsedRealtime()
        Bg.main.postDelayed(wallTick, due.coerceAtLeast(1500))
    }

    private val previewRun = Runnable {
        val id = previewId ?: return@Runnable
        val set = Walls.find(id) ?: return@Runnable
        if (wallShownId != set.id) showWallpaper(set, 0, 700)
    }

    /** Shows a set behind the settings panel while its tile is highlighted. */
    fun previewWallpaper(id: String) {
        previewId = id
        Bg.main.removeCallbacks(previewRun)
        Bg.main.postDelayed(previewRun, 260)
    }

    fun commitWallpaper(id: String) {
        val changed = Prefs.wallSet != id
        Prefs.wallSet = id
        if (changed) Prefs.wallIndex = 0
        previewId = null
        lastWallChange = SystemClock.elapsedRealtime()
        Bg.main.removeCallbacks(previewRun)
        val set = Walls.find(id) ?: return
        if (wallShownId != set.id) showWallpaper(set, Prefs.wallIndex, 700)
        scheduleWall()
    }

    fun endPreview() {
        Bg.main.removeCallbacks(previewRun)
        previewId = null
        val set = Walls.find(Prefs.wallSet) ?: return
        if (wallShownId != set.id || photoPreview) showWallpaper(set, Prefs.wallIndex, 700)
        photoPreview = false
        scheduleWall()
    }

    /** A single photo previewed by the photo picker; the committed wallpaper comes back on endPreview(). */
    private var photoPreview = false

    /** Shows one photo of a set behind the settings panel while it is highlighted in the photo picker. */
    fun previewPhoto(id: String, index: Int) {
        Bg.main.removeCallbacks(previewRun)
        previewId = id
        photoPreview = true
        val set = Walls.find(id) ?: return
        showWallpaper(set, index, 450)
    }

    /** Makes a set the wallpaper rotation, starting at one of its photos. */
    fun commitPhoto(id: String, index: Int) {
        Prefs.wallSet = id
        Prefs.wallIndex = index
        previewId = null
        photoPreview = false
        lastWallChange = SystemClock.elapsedRealtime()
        Bg.main.removeCallbacks(previewRun)
        val set = Walls.find(id) ?: return
        showWallpaper(set, index, 600)
        scheduleWall()
    }

    private val photoPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPhotoAccess(): Boolean = checkSelfPermission(photoPermission) == PackageManager.PERMISSION_GRANTED

    fun requestPhotoAccess() {
        requestPermissions(arrayOf(photoPermission), REQ_PHOTOS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHOTOS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Bg.load({ Walls.scan(this) }) { _ -> settings.refreshWallpaperPage() }
        }
    }

    // Weather

    private val weatherTick = Runnable { refreshWeather(true) }

    private fun refreshWeather(force: Boolean) {
        Bg.main.removeCallbacks(weatherTick)
        val lat = Prefs.weatherLat
        val lon = Prefs.weatherLon
        if (!Prefs.weatherOn || lat.isNaN() || lon.isNaN()) {
            if (clock.extra.isNotEmpty()) clock.extra = ""
            return
        }
        val now = weatherNow
        val fresh = now != null && System.currentTimeMillis() - now.at < 30 * 60_000L && weatherUnitF == Prefs.fahrenheit
        if (fresh && !force) {
            showWeather(now!!)
        } else {
            val f = Prefs.fahrenheit
            Bg.load({ Weather.current(lat.toDouble(), lon.toDouble(), f) }) { n ->
                if (n != null) {
                    weatherNow = n
                    weatherUnitF = f
                    if (Prefs.weatherOn) showWeather(n)
                }
            }
        }
        if (started) Bg.main.postDelayed(weatherTick, 30 * 60_000L)
    }

    private fun showWeather(n: Weather.Now) {
        val t = "${n.temp}°  ${getString(Weather.label(n.code))}"
        if (clock.extra != t) clock.extra = t
    }

    fun setCity(text: String, done: (Boolean) -> Unit) {
        val lang = if (Lang.isArabic) "ar" else "en"
        Bg.load({ Weather.geocode(text, lang) }) { place ->
            if (place == null) {
                done(false)
                return@load
            }
            Prefs.weatherCity = text
            Prefs.weatherPlace = place.name
            Prefs.weatherLat = place.lat.toFloat()
            Prefs.weatherLon = place.lon.toFloat()
            Prefs.weatherOn = true
            weatherNow = null
            refreshWeather(true)
            done(true)
        }
    }

    // Now playing

    private fun showTrack(t: Track) {
        val line = if (t === demo) 1 else NowPlaying.line
        if (Look.npStyle == "pill") {
            npCard.hide()
            npPill.bind(t)
            npPill.onLine(line, false)
        } else {
            npPill.hide()
            npCard.bind(t)
            npCard.onLine(line, false)
        }
        fitColumnSoon()
    }

    private fun fitColumnSoon() {
        // After any crossfade has swapped content and the next layout pass ran.
        column.postDelayed({ fitColumn(true) }, 180)
    }

    /**
     * When the now playing card shares the column with a large clock it can
     * run into the dock. The clock then eases down in scale and the card slides
     * up with it; both are RenderNode properties, so nothing re-lays out.
     */
    private fun fitColumn(animate: Boolean) {
        val inColumn = npCard.parent === column
        var s = 1f
        if (inColumn && npCard.showing && clock.height > 0) {
            val limit = if (Look.dockVertical) home.height - 24.dp
            else dock.top + dock.outlineView().top - 18.dp
            val bottom = column.top + column.height
            val over = bottom - limit
            if (over > 0) s = ((clock.height - over).toFloat() / clock.height).coerceIn(0.55f, 1f)
        }
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val bl = Look.clockPos == "bl"
        clock.pivotX = when {
            Look.clockPos == "center" -> clock.width / 2f
            rtl -> clock.width.toFloat()
            else -> 0f
        }
        clock.pivotY = if (bl) clock.height.toFloat() else 0f
        val shift = clock.height * (1f - s) * (if (bl) 1f else -1f)
        val d = if (animate) Motion.SLOW + 100 else 0L
        clock.animate().scaleX(s).scaleY(s).setDuration(d).setInterpolator(Motion.out).start()
        if (inColumn) npCard.animate().translationY(shift).setDuration(d).setInterpolator(Motion.out).start()
    }

    override fun onTrack(t: Track?) {
        if (t == null) {
            if (currentFocus === npCard.button || currentFocus === npPill) focusDock()
            if (demo == null) {
                npCard.hide()
                npPill.hide()
                fitColumnSoon()
            }
            if (ambientShowing) hideAmbient(true)
            takeoverKey = null
            return
        }
        demo = null
        showTrack(t)
        ambient.bind(t)
        maybeTakeover()
        resetIdle()
    }

    override fun onArt(t: Track) {
        npCard.bindArt(t)
        npPill.bindArt(t)
        ambient.bindArt(t)
    }

    override fun onLyrics(t: Track) {
        npCard.bindLyrics(t)
        npPill.onLine(NowPlaying.line, false)
        ambient.bindLyrics(t)
    }

    override fun onPlayback(playing: Boolean) {
        npCard.onPlayback(playing)
        ambient.onPlayback(playing)
        maybeTakeover()
        resetIdle()
    }

    override fun onLine(index: Int, animate: Boolean) {
        npCard.onLine(index, animate)
        npPill.onLine(index, animate)
        ambient.onLine(index, animate && ambientShowing)
    }

    /** The full screen style takes over once per track, as soon as it plays. */
    private fun maybeTakeover() {
        val t = NowPlaying.track ?: return
        if (Look.npStyle != "takeover" || !NowPlaying.playing || !started || stack.isNotEmpty()) return
        if (takeoverKey == t.key) return
        takeoverKey = t.key
        startAmbient(false)
    }

    // Ambient lyrics

    private val idle = Runnable {
        if (started && stack.isEmpty() && NowPlaying.playing && NowPlaying.track != null) startAmbient(false)
    }

    private fun resetIdle() {
        Bg.main.removeCallbacks(idle)
        val secs = Prefs.ambientDelay
        if (secs > 0 && started && !ambientShowing) Bg.main.postDelayed(idle, secs * 1000L)
    }

    /**
     * Returns false when nothing is playing. [sample] picks a sample song: -1 only
     * when nothing plays, 0 or more always, -2 never.
     */
    fun startAmbient(force: Boolean, sample: Int = -2): Boolean {
        val t = (if (sample >= 0) Samples.make(sample) else null)
            ?: NowPlaying.track
            ?: (if (sample == -1) Samples.make(0) else null)
            ?: return false
        if (ambientShowing) return true
        if (!force && !NowPlaying.playing) return false
        for (o in stack.toList().reversed()) o.dismiss(false)
        ambientShowing = true
        ambient.setWallpaper(wall.current)
        ambient.bind(t)
        ambient.onLine(NowPlaying.line, false)
        ambient.visibility = View.VISIBLE
        ambient.alpha = 0f
        ambient.start()
        ambient.animate().alpha(1f).setDuration(700).setInterpolator(Motion.inOut).withLayer()
            .withEndAction { if (ambientShowing) { stage.visibility = View.INVISIBLE; wall.setRunning(false) } }
            .start()
        return true
    }

    private fun hideAmbient(animate: Boolean) {
        if (!ambientShowing) return
        ambientShowing = false
        stage.visibility = View.VISIBLE
        wall.setRunning(started)
        val done = Runnable {
            ambient.visibility = View.GONE
            ambient.stop()
        }
        if (animate) {
            ambient.animate().alpha(0f).setDuration(Motion.SLOW).setInterpolator(Motion.inOut).withLayer().withEndAction(done).start()
        } else {
            ambient.animate().cancel()
            done.run()
        }
        resetIdle()
    }

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reloadApps()
        }
    }

    companion object {
        private const val REQ_PHOTOS = 7

        // Survive a language switch (activity recreate) without a black frame.
        private var lastWall: Bitmap? = null
        private var lastShown: Bitmap? = null
        private var lastBlur: Bitmap? = null
        private var lastWallId: String? = null
        private var lastAccent = 0xFFFFFFFF.toInt()
        private var reopenSettings: String? = null
        private var reopenCustomize: Pair<Int, Int>? = null
    }
}
