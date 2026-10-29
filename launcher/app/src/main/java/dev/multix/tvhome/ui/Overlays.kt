package dev.multix.tvhome.ui

import android.graphics.Bitmap
import android.graphics.LightingColorFilter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.multix.tvhome.HomeActivity
import dev.multix.tvhome.R
import dev.multix.tvhome.apps.Apps
import dev.multix.tvhome.core.Colors
import dev.multix.tvhome.core.Motion
import dev.multix.tvhome.core.SText
import dev.multix.tvhome.core.W
import dev.multix.tvhome.core.dp
import dev.multix.tvhome.core.frame
import dev.multix.tvhome.core.margins
import dev.multix.tvhome.core.rounded

/** A full screen layer above the home screen that takes the remote while open. */
abstract class OverlayView(val host: HomeActivity) : FrameLayout(host) {
    var isOpen = false
        protected set

    init {
        isFocusable = true
        defaultFocusHighlightEnabled = false
        visibility = GONE
        clipChildren = false
    }

    abstract fun onKey(e: KeyEvent): Boolean
    abstract fun dismiss(animate: Boolean = true)

    protected val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL

    /** Converts a visual left/right key into a step along the reading direction. */
    protected fun readingStep(code: Int): Int {
        val d = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
        return if (rtl) -d else d
    }
}

/**
 * The long-press menu for an app. Floats next to the card it belongs to and
 * follows it when the card is moved along the dock.
 */
class MenuOverlay(host: HomeActivity) : OverlayView(host) {
    class Item(val label: String, val icon: Int?, val keepOpen: Boolean = false, val action: () -> Unit)

    private val scrim = View(host)
    private val panel = LinearLayout(host)
    private val header = SText(host, W.MEDIUM, 13f, Colors.TEXT3)
    private val list = LinearLayout(host)
    private var rows: List<ListRow> = emptyList()
    private var items: List<Item> = emptyList()
    private var index = 0
    private var anchor: View? = null

    init {
        scrim.setBackgroundColor(0x59000000)
        addView(scrim, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.orientation = LinearLayout.VERTICAL
        panel.background = rounded(0xF21C1E23.toInt(), 18f.dp)
        panel.setPadding(8.dp, 6.dp, 8.dp, 8.dp)
        panel.elevation = 18f.dp
        header.setPadding(14.dp, 10.dp, 14.dp, 8.dp)
        header.maxLines = 1
        panel.addView(header)
        list.orientation = LinearLayout.VERTICAL
        panel.addView(list)
        addView(panel, frame(250.dp, LayoutParams.WRAP_CONTENT))
    }

    fun show(anchor: View, title: String, items: List<Item>) {
        this.anchor = anchor
        this.items = items
        header.text = title
        list.removeAllViews()
        rows = items.map { item ->
            ListRow(host, item.icon).apply {
                setTitle(item.label)
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 44.dp)
            }
        }
        rows.forEach { list.addView(it) }
        index = 0
        rows.forEachIndexed { i, r -> r.select(i == index, false) }
        isOpen = true
        visibility = VISIBLE
        requestFocus()
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(Motion.MED).start()
        panel.alpha = 0f
        position(false)
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.MED)
            .setInterpolator(Motion.out).withLayer().start()
    }

    /** Places the panel above the anchor (or below when there is no room), clamped to the screen. */
    private fun position(animate: Boolean) {
        val a = anchor ?: return
        panel.measure(MeasureSpec.makeMeasureSpec(250.dp, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val pw = panel.measuredWidth
        val ph = panel.measuredHeight
        val loc = IntArray(2)
        val me = IntArray(2)
        a.getLocationInWindow(loc)
        getLocationInWindow(me)
        val ax = (loc[0] - me[0]).toFloat()
        val ay = (loc[1] - me[1]).toFloat()
        val aw = a.width * a.scaleX
        val ah = a.height * a.scaleY
        val sw = resources.displayMetrics.widthPixels
        val margin = 24.dp
        val x = (ax + aw / 2f - pw / 2f).coerceIn(margin.toFloat(), (sw - margin - pw).toFloat())
        val above = ay - ph - 18.dp
        val y = if (above >= margin) above else ay + ah + 18.dp
        panel.pivotX = (ax + aw / 2f - x).coerceIn(0f, pw.toFloat())
        panel.pivotY = if (above >= margin) ph.toFloat() else 0f
        if (animate) {
            panel.animate().translationX(x).translationY(y).setDuration(Motion.SLOW).setInterpolator(Motion.out).start()
        } else {
            panel.translationX = x
            panel.translationY = y
        }
    }

    /** Re-anchors after the card moved; waits for the card's own slide to finish. */
    fun follow(newAnchor: View) {
        anchor = newAnchor
        postDelayed({ if (isOpen) position(true) }, Motion.SLOW + 40)
    }

    override fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ESCAPE -> dismiss()
            else -> if (isConfirm(e.keyCode) && e.repeatCount == 0) {
                val item = items.getOrNull(index) ?: return true
                if (!item.keepOpen) dismiss()
                item.action()
            }
        }
        return true
    }

    private fun move(d: Int) {
        val n = (index + d).coerceIn(0, rows.size - 1)
        if (n == index) return
        rows[index].select(false, true)
        index = n
        rows[index].select(true, true)
        host.navSound(if (d > 0) View.FOCUS_DOWN else View.FOCUS_UP)
    }

    override fun dismiss(animate: Boolean) {
        if (!isOpen) return
        isOpen = false
        val done = Runnable { visibility = GONE; list.removeAllViews() }
        if (!animate) { done.run(); host.overlayClosed(this); return }
        scrim.animate().alpha(0f).setDuration(Motion.FAST).start()
        panel.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(160)
            .setInterpolator(Motion.accel).withLayer().withEndAction(done).start()
        host.overlayClosed(this)
    }
}

/**
 * Every app in a five column grid over a blurred, darkened copy of the
 * wallpaper. In edit mode OK adds or removes apps from the dock.
 */
class AppsOverlay(host: HomeActivity) : OverlayView(host) {
    var editMode = false
        private set
    private val frost = ImageView(host)
    private val title = SText(host, W.MEDIUM, 26f, Colors.WHITE)
    private val hint = SText(host, W.REGULAR, 14f, Colors.TEXT3)
    private val clip = FrameLayout(host)
    private val cols = 5
    private val grid: Grid
    private val cards = ArrayList<AppCard>()
    private var index = 0

    init {
        CardSpec.init()
        frost.scaleType = ImageView.ScaleType.CENTER_CROP
        frost.colorFilter = LightingColorFilter(0xFF6A6A6A.toInt(), 0)
        setBackgroundColor(0xFF0B0C0F.toInt())
        addView(frost, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        head.addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        head.addView(hint)
        addView(head, frame(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).margins(start = 58.dp, top = 40.dp, end = 58.dp))

        val labelH = 22.dp
        grid = Grid(host, cols, CardSpec.w, CardSpec.h + 10.dp + labelH, 26.dp, 22.dp)
        clip.clipChildren = true
        clip.clipToPadding = false
        clip.setPadding(0, 18.dp, 0, 0)
        clip.addView(grid, frame(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(clip, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).margins(top = 96.dp))
    }

    fun setFrost(b: Bitmap?) {
        frost.setImageBitmap(b)
        if (b != null) background = null else setBackgroundColor(0xFF0B0C0F.toInt())
    }

    fun open(edit: Boolean) {
        editMode = edit
        title.text = host.getString(if (edit) R.string.edit_dock_title else R.string.apps_title)
        hint.text = host.getString(if (edit) R.string.edit_dock_hint else R.string.apps_hint)
        sync()
        index = 0
        grid.resetScroll()
        isOpen = true
        visibility = VISIBLE
        alpha = 0f
        scaleX = 1.04f
        scaleY = 1.04f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(Motion.out)
            .withLayer().start()
        post { focusAt(0, false) }
    }

    /** Rebuilds the card list from the current app list, reusing card views where possible. */
    fun sync() {
        val hidden = dev.multix.tvhome.core.Prefs.hidden
        val list = Apps.sorted(Apps.all.filter { editMode || it.pkg !in hidden }, forDock = false)
        val old = HashMap<String, AppCard>()
        for (c in cards) c.entry?.let { old[it.pkg] = c }
        cards.clear()
        grid.removeAllViews()
        val dock = Apps.dock().toSet()
        for (e in list) {
            val card = old[e.pkg] ?: AppCard(host, Label.BELOW).also { c ->
                c.setOnClickListener { onCardClick(c) }
            }
            if (card.entry !== e) card.bind(e)
            card.dimmed = editMode && e.pkg in hidden
            card.setBadge(if (editMode && e.pkg in dock) R.drawable.ic_badge_check else null)
            cards.add(card)
            grid.addView(card)
        }
        grid.viewport = (resources.displayMetrics.heightPixels - 96.dp - 18.dp)
        if (index >= cards.size) index = (cards.size - 1).coerceAtLeast(0)
    }

    fun cardReady(pkg: String) {
        cards.firstOrNull { it.entry?.pkg == pkg }?.cardReady()
    }

    private fun onCardClick(c: AppCard) {
        val e = c.entry ?: return
        if (editMode) {
            if (Apps.inDock(e.pkg)) Apps.removeFromDock(e.pkg) else Apps.addToDock(e.pkg)
            c.setBadge(if (Apps.inDock(e.pkg)) R.drawable.ic_badge_check else null)
            host.refreshDock()
        } else {
            host.launch(e, c.tileView())
        }
    }

    private fun focusAt(i: Int, animate: Boolean) {
        if (cards.isEmpty()) return
        index = i.coerceIn(0, cards.size - 1)
        cards[index].requestFocus()
        grid.ensureRowVisible(index / cols, animate)
    }

    override fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) dismiss()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return false
        if (cards.isEmpty()) return code != KeyEvent.KEYCODE_MENU
        val col = index % cols
        val row = index / cols
        val rows = (cards.size + cols - 1) / cols
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val step = readingStep(code)
                val nc = col + step
                if (nc < 0 || nc >= cols || index + step >= cards.size) {
                    cards[index].nudge(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                } else {
                    focusAt(index + step, true)
                    host.navSound(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) View.FOCUS_RIGHT else View.FOCUS_LEFT)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (row == 0) {
                    // Up from the first row goes back to the dock, the way Down came in.
                    if (!editMode) dismiss() else cards[index].nudge(-1, vertical = true)
                } else {
                    focusAt(index - cols, true)
                    host.navSound(View.FOCUS_UP)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (row >= rows - 1) {
                    cards[index].nudge(1, vertical = true)
                } else {
                    focusAt(minOf(index + cols, cards.size - 1), true)
                    host.navSound(View.FOCUS_DOWN)
                }
                return true
            }
        }
        return false
    }

    fun focusedCard(): AppCard? = cards.getOrNull(index)

    override fun dismiss(animate: Boolean) {
        if (!isOpen) return
        isOpen = false
        val done = Runnable { visibility = GONE }
        if (!animate) {
            done.run()
        } else {
            animate().alpha(0f).scaleX(1.03f).scaleY(1.03f).setDuration(Motion.MED).setInterpolator(Motion.accel)
                .withLayer().withEndAction(done).start()
        }
        val wasEdit = editMode
        editMode = false
        host.overlayClosed(this, wasEdit)
    }
}
