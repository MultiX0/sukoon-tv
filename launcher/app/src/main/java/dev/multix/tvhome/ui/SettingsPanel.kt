package dev.multix.tvhome.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.multix.tvhome.BuildConfig
import dev.multix.tvhome.HomeActivity
import dev.multix.tvhome.R
import dev.multix.tvhome.apps.Apps
import dev.multix.tvhome.core.Bg
import dev.multix.tvhome.core.Colors
import dev.multix.tvhome.core.Lang
import dev.multix.tvhome.core.Look
import dev.multix.tvhome.core.Motion
import dev.multix.tvhome.core.Prefs
import dev.multix.tvhome.core.Presets
import dev.multix.tvhome.core.SText
import dev.multix.tvhome.core.SystemTweaks
import dev.multix.tvhome.core.W
import dev.multix.tvhome.core.dp
import dev.multix.tvhome.core.frame
import dev.multix.tvhome.core.margins
import dev.multix.tvhome.core.roundClip
import dev.multix.tvhome.core.rounded
import dev.multix.tvhome.media.NowPlaying
import dev.multix.tvhome.update.Updater
import dev.multix.tvhome.wall.WallSet
import dev.multix.tvhome.wall.Walls
import kotlin.math.roundToInt

/**
 * The side panel opened by the gear pill or the Menu key. A stack of pages
 * slides inside a floating glass card; the home screen stays visible on the
 * other side so wallpaper choices preview live.
 */
class SettingsPanel(host: HomeActivity) : OverlayView(host) {

    private class Item(val sel: Selectable, val rowView: View, val row: Int, val col: Int)

    /**
     * The scrolling area of a page. Its content is measured at full height (a plain FrameLayout caps it at the
     * visible height, and the rows past that got zero height), and rows fade out at the edges instead of being cut.
     */
    private class Viewport(ctx: Context) : FrameLayout(ctx) {
        lateinit var content: View
        private val fade = 22.dp
        private val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        private var topFade: LinearGradient? = null
        private var bottomFade: LinearGradient? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            content.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            setMeasuredDimension(w, h)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            topFade = LinearGradient(0f, 0f, 0f, fade.toFloat(), 0xFF000000.toInt(), 0, Shader.TileMode.CLAMP)
            bottomFade = LinearGradient(0f, (h - fade).toFloat(), 0f, h.toFloat(), 0, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        }

        override fun dispatchDraw(canvas: Canvas) {
            val top = content.translationY < -1f
            val bottom = content.translationY + content.height > height + 1f
            if (!top && !bottom) {
                super.dispatchDraw(canvas)
                return
            }
            val w = width.toFloat()
            val h = height.toFloat()
            val layer = canvas.saveLayer(0f, 0f, w, h, null)
            super.dispatchDraw(canvas)
            if (top) {
                paint.shader = topFade
                canvas.drawRect(0f, 0f, w, fade.toFloat(), paint)
            }
            if (bottom) {
                paint.shader = bottomFade
                canvas.drawRect(0f, h - fade, w, h, paint)
            }
            canvas.restoreToCount(layer)
        }
    }

    private inner class Page(val id: String, titleText: String) : FrameLayout(host) {
        val title = SText(host, W.MEDIUM, 22f, Colors.WHITE)
        val viewport = Viewport(host)
        val content = LinearLayout(host)
        val items = ArrayList<Item>()
        var index = 0
        var row = 0
        var scroll = 0f
        var onLeave: (() -> Unit)? = null

        init {
            title.text = titleText
            addView(title, frame(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).margins(start = 28.dp, top = 26.dp, end = 28.dp))
            content.orientation = LinearLayout.VERTICAL
            content.clipChildren = false
            // Room inside the clip for the focus ring and the 5% zoom of a selected tile.
            content.setPadding(14.dp, 10.dp, 14.dp, 18.dp)
            content.clipToPadding = false
            viewport.clipChildren = true
            viewport.content = content
            viewport.addView(content, frame(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(viewport, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).margins(top = 64.dp, bottom = 6.dp))
        }

        override fun hasOverlappingRendering() = true

        fun add(r: ListRow): ListRow {
            content.addView(r, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 48.dp).apply { bottomMargin = 4.dp })
            items.add(Item(r, r, row++, 0))
            return r
        }

        fun addView2(v: View, sel: Selectable) {
            content.addView(v)
            items.add(Item(sel, v, row++, 0))
        }

        fun label(text: String) {
            content.addView(SectionLabel(host, text))
        }

        fun note(text: String) {
            content.addView(SText(host, W.REGULAR, 13f, Colors.TEXT4).apply {
                this.text = text
                setPadding(16.dp, 10.dp, 16.dp, 6.dp)
            })
        }

        fun tiles(list: List<SetTile>) {
            var i = 0
            while (i < list.size) {
                val line = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                }
                for (c in 0 until 2) {
                    val t = list.getOrNull(i + c) ?: break
                    line.addView(t, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (c == 0) marginEnd = 6.dp else marginStart = 6.dp
                    })
                    items.add(Item(t, line, row, c))
                }
                if (line.childCount == 1) line.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = 6.dp })
                content.addView(line, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp })
                row++
                i += 2
            }
        }

        /** A grid of photo tiles, [cols] per line. */
        fun photos(list: List<PhotoTile>, cols: Int) {
            for (start in list.indices step cols) {
                val line = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                }
                for (c in 0 until cols) {
                    val t = list.getOrNull(start + c)
                    val lp = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (c > 0) marginStart = 10.dp
                    }
                    if (t == null) {
                        line.addView(View(host), lp)
                        continue
                    }
                    line.addView(t, lp)
                    items.add(Item(t, line, row, c))
                }
                content.addView(line, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp })
                row++
            }
        }

        fun current(): Item? = items.getOrNull(index)

        fun select(i: Int, animate: Boolean) {
            if (items.isEmpty()) return
            items.getOrNull(index)?.sel?.select(false, animate)
            index = i.coerceIn(0, items.size - 1)
            items[index].sel.select(true, animate)
            ensureVisible(animate)
        }

        fun ensureVisible(animate: Boolean) {
            val it = current() ?: return
            val h = viewport.height
            if (h == 0) { post { ensureVisible(false) }; return }
            val top = it.rowView.top.toFloat()
            val bottom = it.rowView.bottom.toFloat()
            var next = scroll
            val pad = 40.dp
            if (top + next < pad) next = pad - top
            if (bottom + next > h - pad) next = h - pad - bottom
            // Never past the end of the content, never above its start.
            next = next.coerceIn(minOf(0f, (h - content.height).toFloat()), 0f)
            if (it.row == 0) next = 0f
            if (next == scroll) return
            scroll = next
            if (animate) content.animate().translationY(next).setDuration(Motion.SLOW).setInterpolator(Motion.out).start()
            else content.translationY = next
        }
    }

    private val shade = View(host)
    private val panel = FrameLayout(host)
    private val frost = ImageView(host)
    private val edge = View(host)
    private val frostMatrix = Matrix()
    private val pages = ArrayList<Page>()
    private val panelW = 400.dp
    private val margin = 20.dp
    private var editing: CityRow? = null

    init {
        addView(shade, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.background = rounded(0xFF15171B.toInt(), 26f.dp)
        panel.roundClip(26f.dp)
        // No elevation: this GPU draws the shadow as a hard slab under the panel. A hairline edge reads as glass.
        frost.scaleType = ImageView.ScaleType.MATRIX
        frost.colorFilter = android.graphics.LightingColorFilter(0xFF5A5A5A.toInt(), 0x00070809)
        panel.addView(frost, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val sheen = View(host)
        sheen.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x14FFFFFF, 0x00FFFFFF))
        panel.addView(sheen, frame(LayoutParams.MATCH_PARENT, 120.dp))
        edge.background = GradientDrawable().apply {
            cornerRadius = 26f.dp
            setStroke(1.dp, 0x24FFFFFF)
        }
        addView(panel, frame(panelW, LayoutParams.MATCH_PARENT, Gravity.END).margins(top = margin, end = margin, bottom = margin))
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignFrost() }
    }

    fun setFrost(b: Bitmap?) {
        frost.setImageBitmap(b)
        alignFrost()
    }

    /** Lines the blurred wallpaper up with the screen so the panel reads as frosted glass. */
    private fun alignFrost() {
        val d = frost.drawable ?: return
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val sh = resources.displayMetrics.heightPixels.toFloat()
        frostMatrix.setScale(sw / d.intrinsicWidth, sh / d.intrinsicHeight)
        frostMatrix.postTranslate(-panel.left.toFloat(), -panel.top.toFloat())
        frost.imageMatrix = frostMatrix
    }

    fun open(pageId: String? = null, animate: Boolean = true) {
        pages.forEach { panel.removeView(it) }
        pages.clear()
        val root = buildRoot()
        push(root, false)
        if (pageId != null) {
            val i = root.items.indexOfFirst { (it.sel as? ListRow)?.tag == pageId }
            if (i >= 0) root.select(i, false)
            build(pageId)?.let { push(it, false) }
        }
        isOpen = true
        visibility = VISIBLE
        requestFocus()
        shade.background = GradientDrawable(
            if (rtl) GradientDrawable.Orientation.RIGHT_LEFT else GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00000000, 0x33000000, 0x9E000000.toInt())
        )
        if (!animate) {
            shade.alpha = 1f
            panel.translationX = 0f
            return
        }
        shade.alpha = 0f
        shade.animate().alpha(1f).setDuration(Motion.SLOW).start()
        panel.translationX = (panelW + margin).toFloat() * if (rtl) -1 else 1
        panel.animate().translationX(0f).setDuration(Motion.SLOW + 60).setInterpolator(Motion.out).withLayer().start()
    }

    override fun dismiss(animate: Boolean) {
        if (!isOpen) return
        endEdit(true)
        pages.forEach { it.onLeave?.invoke() }
        isOpen = false
        val done = Runnable {
            visibility = GONE
            pages.forEach { panel.removeView(it) }
            pages.clear()
        }
        if (!animate) {
            done.run()
        } else {
            shade.animate().alpha(0f).setDuration(Motion.MED).start()
            panel.animate().translationX((panelW + margin).toFloat() * if (rtl) -1 else 1)
                .setDuration(Motion.MED).setInterpolator(Motion.accel).withLayer().withEndAction(done).start()
        }
        host.overlayClosed(this)
    }

    fun currentPageId(): String? = pages.lastOrNull()?.id

    private fun push(p: Page, animate: Boolean) {
        val prev = pages.lastOrNull()
        pages.add(p)
        panel.addView(p, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // The glass edge stays above every page.
        panel.removeView(edge)
        panel.addView(edge, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        p.select(p.index, false)
        if (prev == null || !animate) {
            prev?.visibility = INVISIBLE
            return
        }
        val dir = if (rtl) -1 else 1
        prev.animate().translationX(-40f.dp * dir).alpha(0f).setDuration(Motion.MED).setInterpolator(Motion.out)
            .withLayer().withEndAction { prev.visibility = INVISIBLE }.start()
        p.translationX = 48f.dp * dir
        p.alpha = 0f
        p.animate().translationX(0f).alpha(1f).setDuration(Motion.SLOW).setInterpolator(Motion.out).withLayer().start()
    }

    private fun pop() {
        if (pages.size <= 1) {
            dismiss()
            return
        }
        val top = pages.removeAt(pages.size - 1)
        top.onLeave?.invoke()
        val prev = pages.last()
        refreshRoot(prev)
        val dir = if (rtl) -1 else 1
        top.animate().translationX(48f.dp * dir).alpha(0f).setDuration(Motion.MED).setInterpolator(Motion.out)
            .withLayer().withEndAction { panel.removeView(top) }.start()
        prev.visibility = VISIBLE
        prev.translationX = -40f.dp * dir
        prev.alpha = 0f
        prev.animate().translationX(0f).alpha(1f).setDuration(Motion.SLOW).setInterpolator(Motion.out).withLayer().start()
    }

    override fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        editing?.let {
            if (e.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_BACK ||
                    code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                endEdit(true)
                return true
            }
            return false
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val page = pages.lastOrNull() ?: return true
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> pop()
            KeyEvent.KEYCODE_MENU -> dismiss()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val cur = page.current() ?: return true
                val d = if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                val targetRow = cur.row + d
                val candidates = page.items.withIndex().filter { it.value.row == targetRow }
                if (candidates.isEmpty()) {
                    bump(cur.sel.view, d)
                } else {
                    val best = candidates.minByOrNull { Math.abs(it.value.col - cur.col) }!!
                    page.select(best.index, true)
                    host.navSound(if (d > 0) View.FOCUS_DOWN else View.FOCUS_UP)
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val cur = page.current() ?: return true
                val step = readingStep(code)
                if (!cur.sel.step(step)) {
                    val target = page.items.indexOfFirst { it.row == cur.row && it.col == cur.col + step }
                    if (target >= 0) {
                        page.select(target, true)
                        host.navSound(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) View.FOCUS_RIGHT else View.FOCUS_LEFT)
                    } else if (step > 0 && cur.sel is ListRow && (cur.sel.tag as? String) != null && page.id == "root") {
                        cur.sel.click()
                    }
                }
            }
            else -> if (isConfirm(code) && e.repeatCount == 0) page.current()?.sel?.click()
        }
        return true
    }

    private fun bump(v: View, d: Int) {
        v.animate().translationY(5f.dp * d).setDuration(100).setInterpolator(Motion.out).withEndAction {
            v.animate().translationY(0f).setDuration(300).setInterpolator(Motion.spring).start()
        }.start()
    }

    // Pages

    private fun build(id: String): Page? = when (id) {
        "wallpaper" -> buildWallpaper()
        "clock" -> buildClock()
        "lyrics" -> buildLyrics()
        "dock" -> buildDock()
        "hidden" -> buildHidden()
        "language" -> buildLanguage()
        "weather" -> buildWeather()
        "about" -> buildAbout()
        "system" -> buildSystem()
        "looks" -> buildLooks()
        else -> null
    }

    private fun s(id: Int): String = host.getString(id)

    private fun nav(page: Page, id: String, icon: Int, title: Int): ListRow =
        page.add(ListRow(host, icon, ListRow.Trailing.CHEVRON).apply {
            tag = id
            setTitle(s(title))
            onClick = { build(id)?.let { push(it, true) } }
        })

    private fun buildRoot(): Page {
        val p = Page("root", s(R.string.settings))
        p.add(ListRow(host, R.drawable.ic_customize, ListRow.Trailing.CHEVRON).apply {
            setTitle(s(R.string.s_customize))
            onClick = { host.openCustomize(0, 0) }
        })
        nav(p, "looks", R.drawable.ic_bookmark, R.string.s_looks)
        nav(p, "wallpaper", R.drawable.ic_image, R.string.s_wallpaper)
        nav(p, "clock", R.drawable.ic_clock, R.string.s_clock)
        nav(p, "lyrics", R.drawable.ic_music, R.string.s_lyrics)
        nav(p, "dock", R.drawable.ic_dock, R.string.s_dock)
        nav(p, "language", R.drawable.ic_globe, R.string.s_language)
        nav(p, "weather", R.drawable.ic_weather, R.string.s_weather)
        if (SystemTweaks.available(host)) nav(p, "system", R.drawable.ic_screensaver, R.string.s_system_tweaks)
        nav(p, "about", R.drawable.ic_info, R.string.s_about)
        p.add(ListRow(host, R.drawable.ic_tv).apply {
            setTitle(s(R.string.s_system))
            onClick = { host.openSystemSettings() }
        })
        refreshRoot(p)
        return p
    }

    private fun refreshRoot(p: Page) {
        if (p.id != "root") return
        for (it in p.items) {
            val r = it.sel as? ListRow ?: continue
            when (r.tag) {
                "wallpaper" -> r.setValue(Walls.find(Prefs.wallSet)?.name ?: "")
                "clock" -> r.setValue(s(if (Prefs.clock24(host)) R.string.c_24h_short else R.string.c_12h_short))
                "lyrics" -> r.setValue(onOff(Prefs.lyricsOn))
                "dock" -> r.setValue(host.resources.getQuantityString(R.plurals.apps_count, Apps.dock().size, Apps.dock().size))
                "language" -> r.setValue(s(when {
                    Prefs.lang == "system" -> R.string.v_system
                    Lang.isArabic -> R.string.lang_ar
                    else -> R.string.lang_en
                }))
                "system" -> r.setValue(onOff(SystemTweaks.screensaverIsOurs(host)))
                "looks" -> r.setValue(Presets.list(host).size.let { if (it == 0) "" else it.toString() })
                "weather" -> r.setValue(if (Prefs.weatherOn && Prefs.weatherPlace.isNotEmpty()) Prefs.weatherPlace.substringBefore(',') else onOff(false))
                "about" -> r.setValue(if (Updater.state is Updater.State.Available) s(R.string.u_badge) else BuildConfig.VERSION_NAME)
            }
        }
    }

    private fun onOff(b: Boolean) = s(if (b) R.string.on else R.string.off)

    private fun choice(page: Page, title: Int, values: List<Int>, labels: (Int) -> String, get: () -> Int, set: (Int) -> Unit): ListRow {
        val r = ListRow(host, null, ListRow.Trailing.VALUE_ARROWS)
        r.setTitle(s(title))
        fun show() { r.setValue(labels(get())) }
        fun step(d: Int) {
            val i = values.indexOf(get()).let { if (it < 0) 0 else it }
            val n = (i + d + values.size) % values.size
            set(values[n])
            show()
            host.applySettings()
        }
        r.onStep = { step(it) }
        r.onClick = { step(1) }
        show()
        return page.add(r)
    }

    private fun toggle(page: Page, title: Int, get: () -> Boolean, set: (Boolean) -> Unit): ListRow =
        choice(page, title, listOf(1, 0), { onOff(it == 1) }, { if (get()) 1 else 0 }, { set(it == 1) })

    private fun minutes(n: Int) = if (n == 0) s(R.string.off) else host.resources.getQuantityString(R.plurals.minutes, n, n)
    private fun seconds(n: Int) = when {
        n == 0 -> s(R.string.off)
        n >= 60 -> host.resources.getQuantityString(R.plurals.minutes, n / 60, n / 60)
        else -> host.resources.getQuantityString(R.plurals.seconds, n, n)
    }

    private fun buildWallpaper(): Page {
        val p = Page("wallpaper", s(R.string.s_wallpaper))
        choice(p, R.string.w_rotation, listOf(0, 1, 5, 10, 30), ::minutes, { Prefs.wallInterval }, { Prefs.wallInterval = it })
        toggle(p, R.string.w_motion, { Prefs.wallMotion }, { Prefs.wallMotion = it })
        p.label(s(R.string.w_sets))
        val committed = Walls.find(Prefs.wallSet)?.id
        val tiles = Walls.sets.map { set ->
            SetTile(host, set).apply {
                checked = set.id == committed
                onFocus = { host.previewWallpaper(set.id) }
                // OK opens the set: use all of it, or pick single photos from it.
                onClick = { push(buildPhotos(set), true) }
            }
        }
        p.tiles(tiles)
        if (!Walls.sets.any { it.user }) {
            if (host.hasPhotoAccess()) {
                p.note(s(R.string.w_user_hint))
            } else {
                p.add(ListRow(host, R.drawable.ic_folder).apply {
                    setTitle(s(R.string.w_allow))
                    onClick = { host.requestPhotoAccess() }
                })
                p.note(s(R.string.w_user_hint))
            }
        }
        p.onLeave = { host.endPreview() }
        // Start on the selected set rather than the first row.
        val sel = p.items.indexOfFirst { (it.sel as? SetTile)?.checked == true }
        if (sel >= 0) p.index = sel
        // Thumbnails decode in the background and fade in.
        val tw = ((panelW - 24.dp - 12.dp) / 2f - 12.dp).roundToInt()
        val th = (tw * 9f / 16f).roundToInt()
        for (t in tiles) {
            Bg.load({ Walls.thumb(host, t.set, tw, th, 10f.dp) }) { b -> if (b != null) t.setThumb(b) }
        }
        return p
    }

    /**
     * One set's photos. The top row makes the whole set the wallpaper; OK on a photo adds it to (or takes it out
     * of) My picks, which then becomes the wallpaper and rotates only the picked photos.
     */
    private fun buildPhotos(set: WallSet): Page {
        val p = Page("photos", set.name)
        val useAll = ListRow(host, null, ListRow.Trailing.CHECK).apply {
            setTitle(s(if (set.picks) R.string.w_use_picks else R.string.w_use_set))
            checked = Prefs.wallSet == set.id
        }
        useAll.onClick = {
            host.commitWallpaper(set.id)
            useAll.checked = true
        }
        p.add(useAll)
        val count = SText(host, W.REGULAR, 13f, Colors.TEXT4).apply { setPadding(4.dp, 12.dp, 4.dp, 8.dp) }
        fun showCount() {
            val n = Walls.pickCount(host)
            count.text = if (n == 0) s(R.string.w_pick_hint) else host.resources.getQuantityString(R.plurals.w_picks, n, n)
        }
        showCount()
        p.content.addView(count)
        val tiles = set.files.mapIndexed { i, file ->
            // In My picks every photo is picked; elsewhere it shows whether it is in My picks.
            val (srcId, srcFile) = set.refs?.get(i) ?: (set.id to file)
            PhotoTile(host).apply {
                checked = Walls.isPicked(host, srcId, srcFile)
                onFocus = { host.previewPhoto(set.id, i) }
                onClick = {
                    checked = Walls.togglePick(host, srcId, srcFile)
                    val picks = Walls.sets.firstOrNull { it.picks }
                    when {
                        picks != null && checked -> host.commitPhoto(picks.id, picks.refs!!.indexOf(srcId to srcFile).coerceAtLeast(0))
                        picks != null -> if (Prefs.wallSet == Walls.PICKS_ID) host.commitPhoto(picks.id, 0)
                        Prefs.wallSet == Walls.PICKS_ID -> host.commitWallpaper(Walls.defaultId)
                    }
                    useAll.checked = Prefs.wallSet == set.id
                    showCount()
                }
            }
        }
        p.photos(tiles, 3)
        p.onLeave = {
            host.endPreview()
            // The set list may have gained or lost My picks.
            post { refreshWallpaperPage() }
        }
        val tw = ((panelW - 28.dp - 20.dp) / 3f).roundToInt()
        val th = (tw * 9f / 16f).roundToInt()
        tiles.forEachIndexed { i, t ->
            Bg.load({ Walls.photoThumb(host, set, i, tw, th, 8f.dp) }) { b -> if (b != null) t.setThumb(b) }
        }
        return p
    }

    /** Called after the user grants photo access or a rescan finds new sets. */
    fun refreshWallpaperPage() {
        if (pages.lastOrNull()?.id != "wallpaper") return
        val old = pages.removeAt(pages.size - 1)
        panel.removeView(old)
        val p = buildWallpaper()
        pages.add(p)
        panel.addView(p, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        p.select(p.index, false)
    }

    private fun buildClock(): Page {
        val p = Page("clock", s(R.string.s_clock))
        toggle(p, R.string.c_24h, { Prefs.clock24(host) }, { Prefs.setClock24(it) })
        toggle(p, R.string.c_seconds, { Prefs.clockSeconds }, { Prefs.clockSeconds = it })
        return p
    }

    private fun buildLyrics(): Page {
        val p = Page("lyrics", s(R.string.s_lyrics))
        toggle(p, R.string.l_show, { Prefs.lyricsOn }, { Prefs.lyricsOn = it })
        choice(p, R.string.l_size, listOf(0, 1, 2), { s(when (it) { 0 -> R.string.l_small; 2 -> R.string.l_large; else -> R.string.l_medium }) },
            { Prefs.lyricsSize }, { Prefs.lyricsSize = it })
        choice(p, R.string.l_delay, listOf(0, 10, 20, 30, 60), ::seconds, { Prefs.ambientDelay }, { Prefs.ambientDelay = it })
        choice(p, R.string.l_bg, listOf(0, 1), { s(if (it == 0) R.string.l_bg_art else R.string.l_bg_wall) },
            { if (Prefs.ambientBg == "wall") 1 else 0 }, { Prefs.ambientBg = if (it == 1) "wall" else "art" })
        p.add(ListRow(host, null).apply {
            setTitle(s(R.string.l_access))
            setValue(s(when (NowPlaying.sourceName()) {
                "session" -> R.string.l_source_session
                "dump" -> R.string.l_source_dump
                else -> R.string.l_source_broadcast
            }))
            onClick = { if (!NowPlaying.hasAccess()) host.openListenerSettings() }
        })
        p.add(ListRow(host, null).apply {
            setTitle(s(R.string.l_try))
            onClick = { if (!host.startAmbient(force = true)) setValue(s(R.string.l_nothing)) }
        })
        p.note(s(R.string.l_credit))
        return p
    }

    private fun buildDock(): Page {
        val p = Page("dock", s(R.string.s_dock))
        p.add(ListRow(host, R.drawable.ic_apps, ListRow.Trailing.CHEVRON).apply {
            setTitle(s(R.string.d_edit))
            onClick = { host.openDockEditor() }
        })
        val sorts = if (Apps.hasUsageAccess(host)) listOf("custom", "recent", "az") else listOf("custom", "az")
        choice(p, R.string.d_sort, sorts.indices.toList(),
            { s(when (sorts[it]) { "recent" -> R.string.v_recent; "az" -> R.string.v_az; else -> R.string.v_custom }) },
            { sorts.indexOf(Look.sortApps).coerceAtLeast(0) }, { Look.sortApps = sorts[it] })
        val hidden = Prefs.hidden.count { Apps.find(it) != null }
        p.add(ListRow(host, R.drawable.ic_eye_off, ListRow.Trailing.CHEVRON).apply {
            tag = "hidden"
            setTitle(s(R.string.d_hidden))
            setValue(hidden.toString())
            onClick = { push(buildHidden(), true) }
        })
        p.add(ListRow(host, R.drawable.ic_reset).apply {
            setTitle(s(R.string.d_reset))
            onClick = {
                Prefs.resetDock()
                host.refreshDock()
                setValue(s(R.string.d_done))
            }
        })
        p.note(s(R.string.d_hint))
        return p
    }

    private fun buildHidden(): Page {
        val p = Page("hidden", s(R.string.d_hidden))
        val apps = Prefs.hidden.mapNotNull { Apps.find(it) }.sortedBy { it.label.lowercase() }
        if (apps.isEmpty()) {
            p.note(s(R.string.d_none_hidden))
        }
        for (e in apps) {
            p.add(ListRow(host, null).apply {
                setTitle(e.label)
                setValue(s(R.string.menu_unhide))
                onClick = {
                    Apps.setHidden(e.pkg, false)
                    setValue(s(R.string.d_shown))
                    host.refreshDock()
                }
            })
        }
        return p
    }

    private fun buildLanguage(): Page {
        val p = Page("language", s(R.string.s_language))
        val codes = listOf("system" to R.string.v_system, "en" to R.string.lang_en, "ar" to R.string.lang_ar)
        for ((code, label) in codes) {
            p.add(ListRow(host, null, ListRow.Trailing.CHECK).apply {
                setTitle(s(label))
                checked = Prefs.lang == code
                onClick = {
                    if (Prefs.lang != code) {
                        val before = Prefs.langResolved
                        Prefs.lang = code
                        p.items.forEach { (it.sel as? ListRow)?.checked = it.sel === this }
                        // Only a change of the language in use needs the restart.
                        if (Prefs.langResolved != before) host.restartForLanguage()
                    }
                }
            })
        }
        p.index = codes.indexOfFirst { it.first == Prefs.lang }.coerceAtLeast(0)
        return p
    }

    private fun buildWeather(): Page {
        val p = Page("weather", s(R.string.s_weather))
        toggle(p, R.string.we_show, { Prefs.weatherOn }, { Prefs.weatherOn = it })
        val city = CityRow(host)
        city.onDone = { text -> host.setCity(text) { ok -> city.showValue(if (ok) Prefs.weatherPlace else s(R.string.we_not_found)) } }
        city.onEdit = { editing = city }
        city.showValue(Prefs.weatherPlace.ifEmpty { s(R.string.we_city_hint) })
        p.addView2(city, city)
        (city.layoutParams as LinearLayout.LayoutParams).apply {
            height = 48.dp
            width = LayoutParams.MATCH_PARENT
            bottomMargin = 4.dp
        }
        choice(p, R.string.we_units, listOf(0, 1), { s(if (it == 1) R.string.we_f else R.string.we_c) },
            { if (Prefs.fahrenheit) 1 else 0 }, { Prefs.fahrenheit = it == 1 })
        p.note(s(R.string.we_credit))
        return p
    }

    private fun endEdit(commit: Boolean) {
        val c = editing ?: return
        editing = null
        c.finish(commit)
        requestFocus()
    }

    fun editFinished(c: CityRow) {
        if (editing === c) {
            editing = null
            requestFocus()
        }
    }

    /** Saved looks: every setting, the wallpaper and My picks included. */
    private fun buildLooks(): Page {
        val p = Page("looks", s(R.string.s_looks))
        p.add(ListRow(host, R.drawable.ic_add).apply {
            setTitle(s(R.string.pr_save))
            onClick = {
                val saved = Presets.saveCurrent(host)
                rebuildTop { buildLooks() }
                Bg.main.post { (pages.lastOrNull()?.items?.firstOrNull()?.sel as? ListRow)?.setValue(saved.name) }
            }
        })
        if (Presets.previous(host) != null) {
            p.add(ListRow(host, R.drawable.ic_reset).apply {
                setTitle(s(R.string.pr_undo))
                onClick = {
                    Presets.previous(host)?.let { prev ->
                        // Undo is undoable too: the current setup takes the place of the one brought back.
                        val now = Presets.snapshot(host)
                        Presets.restore(host, prev)
                        host.getSharedPreferences("presets", Context.MODE_PRIVATE).edit().putString("previous", now.toString()).commit()
                        host.applySavedLook("looks")
                    }
                }
            })
        }
        val list = Presets.list(host)
        p.label(s(R.string.pr_saved))
        if (list.isEmpty()) p.note(s(R.string.pr_none))
        for (preset in list) {
            p.add(ListRow(host, null, ListRow.Trailing.CHEVRON).apply {
                setTitle(preset.name)
                setValue(Presets.summary(host, preset))
                onClick = { push(buildLook(preset.id), true) }
            })
        }
        p.note(s(R.string.pr_hint))
        return p
    }

    private fun buildLook(id: String): Page {
        val preset = Presets.find(host, id) ?: return buildLooks()
        val p = Page("look", preset.name)
        p.add(ListRow(host, R.drawable.ic_check).apply {
            setTitle(s(R.string.pr_apply))
            onClick = {
                Presets.rememberPrevious(host)
                Presets.find(host, id)?.let { Presets.restore(host, it.data) }
                host.applySavedLook("looks")
            }
        })
        p.add(ListRow(host, R.drawable.ic_bookmark).apply {
            setTitle(s(R.string.pr_replace))
            onClick = {
                Presets.replace(host, id)
                setValue(s(R.string.pr_replaced))
            }
        })
        val name = CityRow(host, R.string.pr_rename, R.string.pr_name_hint) { Presets.find(host, id)?.name ?: "" }
        name.onDone = { text ->
            Presets.rename(host, id, text)
            name.showValue(Presets.find(host, id)?.name ?: "")
            pages.lastOrNull()?.title?.text = Presets.find(host, id)?.name ?: ""
        }
        name.onEdit = { editing = name }
        name.showValue(preset.name)
        p.addView2(name, name)
        (name.layoutParams as LinearLayout.LayoutParams).apply {
            height = 48.dp
            width = LayoutParams.MATCH_PARENT
            bottomMargin = 4.dp
        }
        p.add(ListRow(host, R.drawable.ic_remove).apply {
            setTitle(s(R.string.pr_delete))
            onClick = {
                Presets.delete(host, id)
                pop()
                Bg.main.post { refreshTopLooks() }
            }
        })
        p.onLeave = { post { refreshTopLooks() } }
        return p
    }

    /** Rebuilds the looks page under the current one (a look was renamed, replaced or deleted). */
    private fun refreshTopLooks() {
        if (pages.lastOrNull()?.id == "looks") rebuildTop { buildLooks() }
    }

    /** Replaces the page on top with a fresh build, keeping the selected row. */
    private fun rebuildTop(build: () -> Page) {
        val old = pages.removeAt(pages.size - 1)
        panel.removeView(old)
        val p = build()
        pages.add(p)
        panel.addView(p, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.removeView(edge)
        panel.addView(edge, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        p.select(old.index.coerceAtMost(p.items.size - 1), false)
    }

    private fun buildSystem(): Page {
        val p = Page("system", s(R.string.s_system_tweaks))
        toggle(p, R.string.sy_screensaver, { SystemTweaks.screensaverIsOurs(host) }, { SystemTweaks.setScreensaver(host, it) })
        choice(p, R.string.sy_delay, listOf(1, 5, 15, 30), ::minutes, { SystemTweaks.delayMinutes(host) }, { SystemTweaks.setDelayMinutes(host, it) })
        val scales = listOf(1f, 0.5f, 0f)
        choice(p, R.string.sy_anim, listOf(0, 1, 2), { s(when (it) { 0 -> R.string.v_normal; 1 -> R.string.v_fast; else -> R.string.off }) },
            { SystemTweaks.animationScale(host).let { f -> if (f >= 0.75f) 0 else if (f > 0f) 1 else 2 } },
            { SystemTweaks.setAnimationScale(host, scales[it]) })
        p.note(s(R.string.sy_note))
        return p
    }

    /**
     * Settings > About > Check for updates. One row follows the updater: check, then "Update to X" with the size
     * and the release notes, a progress bar while downloading (OK cancels), then Android's install screen.
     */
    private fun addUpdater(p: Page) {
        val brand = Lang.brand(host)
        val row = ListRow(host, R.drawable.ic_update)
        val bar = DownloadBar(host)
        val info = SText(host, W.REGULAR, 13f, Colors.TEXT4).apply { setPadding(16.dp, 8.dp, 16.dp, 12.dp) }
        fun mb(bytes: Long) = if (bytes > 0) String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576f) else ""
        fun reason(r: Updater.Reason, minutes: Int): Pair<String, String> = when (r) {
            Updater.Reason.OFFLINE -> s(R.string.r_offline) to s(R.string.r_offline_l)
            Updater.Reason.NO_RELEASES -> s(R.string.r_no_releases) to host.getString(R.string.r_no_releases_l, Updater.REPO)
            Updater.Reason.RATE_LIMIT -> s(R.string.r_rate) to host.resources.getQuantityString(R.plurals.r_rate_l, minutes, minutes)
            Updater.Reason.BAD_RESPONSE -> s(R.string.r_bad) to s(R.string.r_bad_l)
            Updater.Reason.NO_APK -> s(R.string.r_no_apk) to s(R.string.r_no_apk_l)
            Updater.Reason.NO_SPACE -> s(R.string.r_space) to s(R.string.r_space_l)
            Updater.Reason.DOWNLOAD -> s(R.string.r_download) to s(R.string.r_download_l)
            Updater.Reason.CORRUPT -> s(R.string.r_corrupt) to s(R.string.r_corrupt_l)
            Updater.Reason.WRONG_APP -> s(R.string.r_wrong_app) to host.getString(R.string.r_wrong_app_l, brand)
            Updater.Reason.NOT_NEWER -> s(R.string.r_not_newer) to s(R.string.r_not_newer_l)
            Updater.Reason.SIGNATURE -> s(R.string.r_signature) to s(R.string.r_signature_l)
            Updater.Reason.BLOCKED -> s(R.string.r_blocked) to host.getString(R.string.r_blocked_l, brand)
            Updater.Reason.INSTALL -> s(R.string.r_install) to s(R.string.r_install_l)
            Updater.Reason.CANCELLED -> s(R.string.r_cancelled) to s(R.string.r_cancelled_l)
        }
        fun render(st: Updater.State) {
            bar.visibility = if (st is Updater.State.Downloading || st is Updater.State.Verifying) VISIBLE else GONE
            when (st) {
                is Updater.State.Idle -> {
                    row.setTitle(s(R.string.u_check)); row.setValue("")
                    info.text = host.getString(R.string.u_hint, Updater.REPO)
                }
                is Updater.State.Checking -> {
                    row.setTitle(s(R.string.u_checking)); row.setValue("")
                }
                is Updater.State.UpToDate -> {
                    row.setTitle(s(R.string.u_check)); row.setValue(s(R.string.u_up_to_date))
                    info.text = host.getString(R.string.u_latest, st.latest.toString())
                }
                is Updater.State.Available -> {
                    row.setTitle(host.getString(R.string.u_install, st.release.version.toString()))
                    row.setValue(mb(st.release.apkSize))
                    info.text = st.release.notes.ifEmpty { s(R.string.u_no_notes) }
                }
                is Updater.State.Downloading -> {
                    row.setTitle(host.getString(R.string.u_downloading, st.release.version.toString()))
                    val pct = if (st.total > 0) (st.done * 100 / st.total).toInt().coerceIn(0, 100) else 0
                    row.setValue("$pct%")
                    bar.progress = if (st.total > 0) st.done.toFloat() / st.total else 0f
                    info.text = s(R.string.u_cancel_hint)
                }
                is Updater.State.Verifying -> {
                    row.setTitle(s(R.string.u_verifying)); row.setValue("")
                    bar.progress = 1f
                }
                is Updater.State.Installing -> {
                    row.setTitle(host.getString(R.string.u_installing, st.release.version.toString())); row.setValue("")
                    info.text = host.getString(R.string.u_installing_hint, brand)
                }
                is Updater.State.Failed -> {
                    val (short, long) = reason(st.reason, st.minutes)
                    row.setTitle(st.release?.let { host.getString(R.string.u_retry, it.version.toString()) } ?: s(R.string.u_check))
                    row.setValue(short)
                    info.text = long
                }
            }
        }
        row.onClick = {
            when (val st = Updater.state) {
                is Updater.State.Available -> Updater.download(host)
                is Updater.State.Downloading -> Updater.cancel()
                // After any failure OK checks GitHub again, so a fixed or newer release is always picked up.
                is Updater.State.Failed ->
                    if (st.reason != Updater.Reason.BLOCKED || !host.openInstallPermission()) Updater.check(host)
                is Updater.State.Idle, is Updater.State.UpToDate -> Updater.check(host)
                else -> Unit
            }
        }
        p.add(row)
        p.content.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 3.dp).apply {
            marginStart = 16.dp
            marginEnd = 16.dp
            topMargin = 2.dp
        })
        p.content.addView(info)
        render(Updater.state)
        val listener: (Updater.State) -> Unit = { render(it) }
        Updater.addListener(listener)
        p.onLeave = { Updater.removeListener(listener) }
    }

    private fun buildAbout(): Page {
        val p = Page("about", s(R.string.s_about))
        addUpdater(p)
        p.label(s(R.string.u_this_box))
        val am = host.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        fun mb(b: Long) = "${b / (1024 * 1024)} MB"
        val up = SystemClock.elapsedRealtime() / 60_000
        val upText = host.getString(R.string.a_uptime_fmt, up / (60 * 24), (up / 60) % 24, up % 60)
        val rows = listOf(
            R.string.a_version to "${Lang.brand(host)} ${BuildConfig.VERSION_NAME}",
            R.string.a_ram to host.getString(R.string.a_ram_fmt, mb(mi.availMem), mb(mi.totalMem)),
            R.string.a_uptime to upText,
            R.string.a_device to "${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            R.string.a_lyrics to "LRCLIB",
            R.string.a_weather to "Open-Meteo",
            R.string.a_fonts to "Inter, IBM Plex Sans Arabic",
        )
        for ((t, v) in rows) {
            p.add(ListRow(host, null).apply {
                setTitle(s(t))
                setValue(v)
                subtle = true
            })
        }
        return p
    }
}

/** A wallpaper set: thumbnail, name, and a check when it is the active set. */
class SetTile(ctx: Context, val set: WallSet) : LinearLayout(ctx), Selectable {
    override val view: View get() = this
    private val frameV = FrameLayout(ctx)
    private val thumb = ImageView(ctx)
    private val ring = View(ctx)
    private val check = ImageView(ctx)
    private val name = SText(ctx, W.MEDIUM, 13f, Colors.TEXT2)
    var onFocus: (() -> Unit)? = null
    var onClick: (() -> Unit)? = null
    var checked = false
        set(v) {
            field = v
            check.visibility = if (v) VISIBLE else GONE
        }

    init {
        orientation = VERTICAL
        clipChildren = false
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = rounded(0x1FFFFFFF, 10f.dp)
        thumb.roundClip(10f.dp)
        frameV.clipChildren = false
        frameV.addView(thumb, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        ring.background = GradientDrawable().apply {
            cornerRadius = 13f.dp
            setStroke(2.dp, Colors.WHITE)
        }
        ring.alpha = 0f
        frameV.addView(ring, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            setMargins(-4.dp, -4.dp, -4.dp, -4.dp)
        })
        check.setImageResource(R.drawable.ic_badge_check)
        check.visibility = GONE
        frameV.addView(check, FrameLayout.LayoutParams(22.dp, 22.dp, Gravity.TOP or Gravity.END).apply {
            topMargin = 7.dp
            marginEnd = 7.dp
        })
        addView(frameV, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        name.text = if (set.user) "${set.name}  ·  /sdcard" else set.name
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        name.setPadding(2.dp, 8.dp, 2.dp, 0)
        addView(name)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        (frameV.layoutParams as LayoutParams).height = (w * 9f / 16f).roundToInt()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setThumb(b: Bitmap) {
        thumb.setImageBitmap(b)
        thumb.alpha = 0f
        thumb.animate().alpha(1f).setDuration(Motion.MED).start()
    }

    override fun select(on: Boolean, animate: Boolean) {
        val d = if (animate) Motion.MED else 0L
        frameV.animate().scaleX(if (on) 1.05f else 1f).scaleY(if (on) 1.05f else 1f).setDuration(d)
            .setInterpolator(if (on) Motion.spring else Motion.out).start()
        ring.animate().alpha(if (on) 1f else 0f).setDuration(d).start()
        name.setTextColor(if (on) Colors.WHITE else Colors.TEXT3)
        if (on) onFocus?.invoke()
    }

    override fun click() { onClick?.invoke() }
}

/** A thin rounded progress line for the update download. */
class DownloadBar(ctx: Context) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.WHITE }
    private val rect = android.graphics.RectF()
    var progress = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c != field) {
                field = c
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, track)
        val w = width * progress
        if (w <= 0f) return
        // Fills from the reading start: the right edge in Arabic.
        if (layoutDirection == LAYOUT_DIRECTION_RTL) rect.set(width - w, 0f, width.toFloat(), h) else rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, fill)
    }
}

/** One photo in the photo picker: a 16:9 thumbnail with a check when it is in My picks. */
class PhotoTile(ctx: Context) : FrameLayout(ctx), Selectable {
    override val view: View get() = this
    private val thumb = ImageView(ctx)
    private val ring = View(ctx)
    private val check = ImageView(ctx)
    var onFocus: (() -> Unit)? = null
    var onClick: (() -> Unit)? = null
    var checked = false
        set(v) {
            if (field == v && check.visibility == (if (v) VISIBLE else GONE)) return
            field = v
            check.visibility = if (v) VISIBLE else GONE
            if (v) {
                check.scaleX = 0.4f
                check.scaleY = 0.4f
                check.animate().scaleX(1f).scaleY(1f).setDuration(Motion.MED).setInterpolator(Motion.spring).start()
            }
            thumb.alpha = if (v) 1f else 0.82f
        }

    init {
        clipChildren = false
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = rounded(0x1FFFFFFF, 8f.dp)
        thumb.roundClip(8f.dp)
        addView(thumb, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        ring.background = GradientDrawable().apply {
            cornerRadius = 11f.dp
            setStroke(2.dp, Colors.WHITE)
        }
        ring.alpha = 0f
        addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            setMargins(-4.dp, -4.dp, -4.dp, -4.dp)
        })
        check.setImageResource(R.drawable.ic_badge_check)
        check.visibility = GONE
        addView(check, LayoutParams(18.dp, 18.dp, Gravity.TOP or Gravity.END).apply {
            topMargin = 5.dp
            marginEnd = 5.dp
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((w * 9f / 16f).roundToInt(), MeasureSpec.EXACTLY))
    }

    fun setThumb(b: Bitmap) {
        thumb.setImageBitmap(b)
        val target = thumb.alpha
        thumb.alpha = 0f
        thumb.animate().alpha(target).setDuration(Motion.MED).start()
    }

    override fun select(on: Boolean, animate: Boolean) {
        val d = if (animate) Motion.MED else 0L
        animate().scaleX(if (on) 1.07f else 1f).scaleY(if (on) 1.07f else 1f).setDuration(d)
            .setInterpolator(if (on) Motion.spring else Motion.out).start()
        ring.animate().alpha(if (on) 1f else 0f).setDuration(d).start()
        if (on) onFocus?.invoke()
    }

    override fun click() { onClick?.invoke() }
}

/** The weather city row. OK opens the on-screen keyboard right inside the row. */
class CityRow(
    private val host: HomeActivity,
    title: Int = R.string.we_city,
    private val hint: Int = R.string.we_city_hint,
    private val initial: () -> String = { Prefs.weatherCity },
) : FrameLayout(host), Selectable {
    override val view: View get() = this
    private val title = title
    private val row = ListRow(host, null)
    private val input = EditText(host)
    var onDone: ((String) -> Unit)? = null
    var onEdit: (() -> Unit)? = null

    init {
        row.setTitle(host.getString(title))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        input.visibility = GONE
        input.setSingleLine()
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        input.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.typeface = dev.multix.tvhome.core.Fonts.get(host, W.MEDIUM)
        input.textSize = 15f
        input.setTextColor(Colors.INK)
        input.setHintTextColor(Colors.INK2)
        input.hint = host.getString(hint)
        input.background = rounded(0xFFFFFFFF.toInt(), 12f.dp)
        input.setPadding(16.dp, 0, 16.dp, 0)
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                finish(true)
                true
            } else false
        }
        addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun showValue(v: String) { row.setValue(v) }

    override fun select(on: Boolean, animate: Boolean) = row.select(on, animate)

    override fun click() {
        input.setText(initial())
        input.setSelection(input.text.length)
        input.visibility = VISIBLE
        input.requestFocus()
        onEdit?.invoke()
        val imm = host.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        input.post { imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun finish(commit: Boolean) {
        if (input.visibility != VISIBLE) return
        val imm = host.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        input.visibility = GONE
        val text = input.text.toString().trim()
        if (commit && text.isNotEmpty() && text != initial()) {
            if (title == R.string.we_city) showValue(host.getString(R.string.we_looking))
            onDone?.invoke(text)
        }
        host.settingsEditFinished(this)
    }
}
