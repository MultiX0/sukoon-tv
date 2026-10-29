package dev.multix.tvhome.ui

import android.animation.ValueAnimator
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.multix.tvhome.R
import dev.multix.tvhome.core.Colors
import dev.multix.tvhome.core.Lang
import dev.multix.tvhome.core.Motion
import dev.multix.tvhome.core.SText
import dev.multix.tvhome.core.W
import dev.multix.tvhome.core.dp
import dev.multix.tvhome.core.frame
import dev.multix.tvhome.core.lerpColor
import dev.multix.tvhome.core.rounded

/** Anything an overlay can move a highlight between. */
interface Selectable {
    val view: View
    fun select(on: Boolean, animate: Boolean)
    fun click() {}
    /** Left/right inside the item (cycling a value). Returns false to let the overlay move instead. */
    fun step(dir: Int): Boolean = false
}

/**
 * A settings or menu row in the tvOS style: quiet glass when idle, a white
 * plate with dark ink when highlighted.
 */
class ListRow(ctx: Context, icon: Int? = null, private val trailing: Trailing = Trailing.NONE) :
    FrameLayout(ctx), Selectable {

    enum class Trailing { NONE, CHEVRON, CHECK, VALUE_ARROWS }

    override val view: View get() = this
    private val plate = View(ctx)
    private val iconView: ImageView? = icon?.let { ImageView(ctx).apply { setImageResource(it) } }
    val title = SText(ctx, W.REGULAR, 15f, Colors.WHITE)
    val value = SText(ctx, W.REGULAR, 14f, Colors.TEXT3)
    private val end = ImageView(ctx)
    private var t = 0f
    private var anim: ValueAnimator? = null
    var onClick: (() -> Unit)? = null
    var onStep: ((Int) -> Unit)? = null
    var checked = false
        set(v) {
            field = v
            if (trailing == Trailing.CHECK) end.visibility = if (v) VISIBLE else INVISIBLE
        }
    private var rawValue: String = ""
    var subtle = false

    init {
        plate.background = rounded(0xF2FFFFFF.toInt(), 12f.dp)
        plate.alpha = 0f
        addView(plate, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 0, 14.dp, 0)
        }
        iconView?.let {
            it.setColorFilter(Colors.WHITE)
            row.addView(it, LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginEnd = 14.dp })
        }
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        row.addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        value.maxLines = 1
        value.ellipsize = TextUtils.TruncateAt.END
        value.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        value.maxWidth = 190.dp
        row.addView(value, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = 10.dp })
        when (trailing) {
            Trailing.CHEVRON -> end.setImageResource(R.drawable.ic_chevron)
            Trailing.CHECK -> end.setImageResource(R.drawable.ic_check)
            else -> end.visibility = GONE
        }
        if (trailing == Trailing.CHEVRON && Lang.isArabic) end.scaleX = -1f
        if (trailing == Trailing.CHECK) end.visibility = INVISIBLE
        end.setColorFilter(Colors.TEXT3)
        row.addView(end, LinearLayout.LayoutParams(18.dp, 18.dp).apply { marginStart = 8.dp })
        addView(row, frame(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 48.dp)
    }

    override fun hasOverlappingRendering() = false

    fun setTitle(s: CharSequence): ListRow { title.text = s; return this }

    fun setValue(s: String): ListRow {
        rawValue = s
        renderValue()
        return this
    }

    private fun renderValue() {
        value.text = if (trailing == Trailing.VALUE_ARROWS && t > 0.5f && rawValue.isNotEmpty())
            "‹  $rawValue  ›" else rawValue
    }

    override fun select(on: Boolean, animate: Boolean) {
        anim?.cancel()
        val from = t
        val to = if (on) 1f else 0f
        if (!animate) {
            apply(to)
            return
        }
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = if (on) Motion.FAST else 160
            interpolator = Motion.out
            addUpdateListener { apply(it.animatedValue as Float) }
            start()
        }
        animate().scaleX(if (on) 1.02f else 1f).scaleY(if (on) 1.02f else 1f)
            .setDuration(if (on) Motion.MED else Motion.FAST).setInterpolator(Motion.out).start()
    }

    private fun apply(v: Float) {
        val wasHigh = t > 0.5f
        t = v
        plate.alpha = if (subtle) v * 0.16f else v
        val ink = if (subtle) Colors.WHITE else lerpColor(Colors.WHITE, Colors.INK, v)
        val ink2 = if (subtle) Colors.TEXT2 else lerpColor(Colors.TEXT3, Colors.INK2, v)
        title.setTextColor(ink)
        value.setTextColor(ink2)
        iconView?.setColorFilter(ink)
        end.setColorFilter(ink2)
        if (wasHigh != (v > 0.5f)) renderValue()
    }

    override fun click() { onClick?.invoke() }

    override fun step(dir: Int): Boolean {
        val s = onStep ?: return false
        s(dir)
        return true
    }
}

/** Small uppercase-free section label inside a page. */
class SectionLabel(ctx: Context, text: String) : SText(ctx, W.MEDIUM, 13f, Colors.TEXT3) {
    init {
        this.text = text
        setPadding(16.dp, 18.dp, 16.dp, 8.dp)
    }
}

fun isConfirm(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
    code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
