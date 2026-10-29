package dev.multix.tvhome.core

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import dev.multix.tvhome.R
import java.util.Calendar
import java.util.Locale

/**
 * The launcher has its own language setting, independent of the system. The
 * chosen locale is applied by wrapping each activity's base context, which
 * also gives Arabic a full right to left layout.
 */
object Lang {
    val isArabic: Boolean get() = Prefs.langResolved == "ar"

    /** Arabic uses Latin digits so times and temperatures read the same as in English. */
    fun locale(): Locale =
        if (isArabic) Locale.forLanguageTag("ar-u-nu-latn")
        else Locale("en", Locale.getDefault().country)

    fun wrap(base: Context): Context {
        val loc = locale()
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        cfg.setLayoutDirection(loc)
        return base.createConfigurationContext(cfg)
    }

    fun greeting(ctx: Context): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return ctx.getString(
            when (h) {
                in 5..11 -> R.string.greet_morning
                in 12..16 -> R.string.greet_afternoon
                else -> R.string.greet_evening
            }
        )
    }

    fun brand(ctx: Context): String =
        ctx.getString(if (isArabic) R.string.brand_name_ar else R.string.brand_name)
}

enum class W(val wght: Int) { DISPLAY(200), LIGHT(300), REGULAR(400), MEDIUM(500), SEMIBOLD(600) }

/**
 * A bundled font. Inter and IBM Plex Sans Arabic ship as static weights in
 * res/font; the others live in assets/fonts, most as variable fonts whose
 * weight is picked through the wght axis.
 */
class FontDef(
    val id: String,
    val name: String,
    val asset: String? = null,
    /** Static resources by weight, used instead of [asset]. */
    val res: Map<W, Int> = emptyMap(),
    /** Weight the clock uses. */
    val clockWeight: Int = 300,
    val minWeight: Int = 100,
    val maxWeight: Int = 900,
    val arabic: Boolean = false,
)

object Fonts {
    private val inter = mapOf(
        W.DISPLAY to R.font.inter_display_extralight, W.LIGHT to R.font.inter_light,
        W.REGULAR to R.font.inter_regular, W.MEDIUM to R.font.inter_medium, W.SEMIBOLD to R.font.inter_semibold,
    )
    private val plex = mapOf(
        W.DISPLAY to R.font.plex_arabic_extralight, W.LIGHT to R.font.plex_arabic_light,
        W.REGULAR to R.font.plex_arabic_regular, W.MEDIUM to R.font.plex_arabic_medium, W.SEMIBOLD to R.font.plex_arabic_semibold,
    )

    val INTER = FontDef("inter", "Inter", res = inter)
    val PLEX = FontDef("plex", "IBM Plex Sans Arabic", res = plex, arabic = true)
    private val OUTFIT = FontDef("outfit", "Outfit", "fonts/outfit.ttf", clockWeight = 200)
    private val SPACE = FontDef("space", "Space Grotesk", "fonts/space_grotesk.ttf", clockWeight = 300, minWeight = 300, maxWeight = 700)
    private val READEX = FontDef("readex", "Readex Pro", "fonts/readex_pro.ttf", clockWeight = 200, minWeight = 160, maxWeight = 700, arabic = true)
    private val KUFI = FontDef("kufi", "Noto Kufi Arabic", "fonts/noto_kufi_arabic.ttf", clockWeight = 200, arabic = true)
    private val CAIRO = FontDef("cairo", "Cairo", "fonts/cairo.ttf", clockWeight = 300, minWeight = 200, maxWeight = 1000, arabic = true)

    /** Clock faces, in the order Customize cycles through them. */
    val clock: List<FontDef> = listOf(
        FontDef("inter_light", "Inter Display", res = mapOf(W.DISPLAY to R.font.inter_display_extralight)),
        FontDef("inter_thin", "Inter Display Thin", res = mapOf(W.DISPLAY to R.font.inter_display_thin)),
        FontDef("inter_bold", "Inter Display Bold", res = mapOf(W.DISPLAY to R.font.inter_display_bold)),
        FontDef("dm_serif", "DM Serif Display", "fonts/dm_serif_display.ttf", clockWeight = 400),
        FontDef("bebas", "Bebas Neue", "fonts/bebas_neue.ttf", clockWeight = 400),
        SPACE,
        FontDef("mono", "JetBrains Mono", "fonts/jetbrains_mono.ttf", clockWeight = 300),
        FontDef("playfair", "Playfair Display", "fonts/playfair_display.ttf", clockWeight = 400, minWeight = 400),
        OUTFIT,
        FontDef("plex_ar", "IBM Plex Sans Arabic", res = mapOf(W.DISPLAY to R.font.plex_arabic_extralight), arabic = true),
        READEX,
        KUFI,
        CAIRO,
    )

    /** Body fonts offered for each UI language. */
    val uiEn: List<FontDef> = listOf(INTER, OUTFIT, SPACE)
    val uiAr: List<FontDef> = listOf(PLEX, READEX, KUFI, CAIRO)

    private val cache = HashMap<String, Typeface>()

    /** Bumped whenever the body font changes so screens can re-apply it. */
    var version = 0
        private set

    fun uiFont(): FontDef =
        if (Lang.isArabic) uiAr.firstOrNull { it.id == Look.uiFontAr } ?: PLEX
        else uiEn.firstOrNull { it.id == Look.uiFontEn } ?: INTER

    fun bump() { version++ }

    @Synchronized
    fun get(ctx: Context, w: W): Typeface = load(ctx, uiFont(), w, w.wght)

    /** The clock face, swapped for an Arabic one when Arabic-Indic digits are on. */
    fun clockDef(): FontDef {
        val chosen = clock.firstOrNull { it.id == Look.clockFont }
            ?: if (Lang.isArabic) clock.first { it.id == "plex_ar" } else clock.first()
        if (Look.arabicDigits && !chosen.arabic) return clock.first { it.id == "plex_ar" }
        return chosen
    }

    @Synchronized
    fun clock(ctx: Context): Typeface {
        val d = clockDef()
        return load(ctx, d, W.DISPLAY, d.clockWeight)
    }

    @Synchronized
    fun of(ctx: Context, d: FontDef, w: W): Typeface = load(ctx, d, w, w.wght)

    private fun load(ctx: Context, d: FontDef, w: W, weight: Int): Typeface {
        val wt = weight.coerceIn(d.minWeight, d.maxWeight)
        val key = "${d.id}:${if (d.asset != null) wt.toString() else w.name}"
        cache[key]?.let { return it }
        val tf = try {
            if (d.asset != null) {
                Typeface.Builder(ctx.assets, d.asset)
                    .setFontVariationSettings("'wght' $wt")
                    .setWeight(wt)
                    .build()
            } else {
                val id = d.res[w] ?: d.res.values.first()
                ctx.resources.getFont(id)
            }
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
        cache[key] = tf
        return tf
    }

    /** Warms the typefaces on the background thread so the first frame does not parse fonts. */
    fun preload(ctx: Context) {
        for (w in W.values()) get(ctx, w)
        clock(ctx)
    }
}

/** Plain TextView with the launcher's type and no overlapping-render layer for fades. */
open class SText(ctx: Context, val weight: W = W.REGULAR, sizeSp: Float = 15f, color: Int = Colors.WHITE) : TextView(ctx) {
    /** Set for text that keeps its own face (the clock) when the body font changes. */
    var fixedFace = false

    init {
        typeface = Fonts.get(ctx, weight)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        // Inter sits tighter without the extra padding; Arabic marks need it.
        includeFontPadding = Lang.isArabic
        textAlignment = TEXT_ALIGNMENT_VIEW_START
        isFocusable = false
    }

    fun tracking(em: Float): SText {
        if (!Lang.isArabic) letterSpacing = em
        return this
    }

    override fun hasOverlappingRendering() = false

    fun refreshFont() {
        if (!fixedFace) typeface = Fonts.get(context, weight)
    }
}

/** Re-applies the body font to every text view below [v]. */
fun refreshFonts(v: android.view.View) {
    if (v is SText) v.refreshFont()
    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) refreshFonts(v.getChildAt(i))
}
