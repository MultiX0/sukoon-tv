package dev.multix.tvhome.core

/**
 * Everything the Customize mode can change, persisted through Prefs. Each
 * getter carries its default, so "Reset to default" only has to forget keys.
 */
object Look {
    // Clock
    /** A Fonts.clock id, or "" for the language default. */
    var clockFont: String
        get() = Prefs.getS("clock_font", "")
        set(v) = Prefs.putS("clock_font", v)
    /** "line", "stacked" or "outline". */
    var clockStyle: String
        get() = Prefs.getS("clock_style", "line")
        set(v) = Prefs.putS("clock_style", v)
    /** 0 small, 1 medium, 2 large, 3 extra large. */
    var clockSize: Int
        get() = Prefs.getI("clock_size", 2)
        set(v) = Prefs.putI("clock_size", v)
    /** "tl" top left, "center", "bl" bottom left. */
    var clockPos: String
        get() = Prefs.getS("clock_pos", "tl")
        set(v) = Prefs.putS("clock_pos", v)
    /** "white", "auto" (from the wallpaper) or a swatch id. */
    var clockColor: String
        get() = Prefs.getS("clock_color", "white")
        set(v) = Prefs.putS("clock_color", v)
    var arabicDigits: Boolean
        get() = Prefs.getB("arabic_digits", false)
        set(v) = Prefs.putB("arabic_digits", v)

    // Date and greeting
    var dateOn: Boolean
        get() = Prefs.getB("date_on", true)
        set(v) = Prefs.putB("date_on", v)
    var hijri: Boolean
        get() = Prefs.getB("hijri", false)
        set(v) = Prefs.putB("hijri", v)
    var greetOn: Boolean
        get() = Prefs.getB("greet_on", true)
        set(v) = Prefs.putB("greet_on", v)

    // Dock
    /** "cards", "pill", "icons" or "list". */
    var dockStyle: String
        get() = Prefs.getS("dock_style", "cards")
        set(v) = Prefs.putS("dock_style", v)
    /** "squircle", "circle" or "rounded". */
    var iconShape: String
        get() = Prefs.getS("icon_shape", "squircle")
        set(v) = Prefs.putS("icon_shape", v)
    var dockLabels: Boolean
        get() = Prefs.getB("dock_labels", true)
        set(v) = Prefs.putB("dock_labels", v)
    /** "bottom" or "left". The list style always sits on the left. */
    var dockPos: String
        get() = Prefs.getS("dock_pos", "bottom")
        set(v) = Prefs.putS("dock_pos", v)
    /** "banner" (the app's TV banner unless it is a loud flat colour) or "icon" (always the icon tile). */
    var cardArt: String
        get() = Prefs.getS("card_art", "banner")
        set(v) = Prefs.putS("card_art", v)
    /** "custom", "recent" or "az". */
    var sortApps: String
        get() = Prefs.getS("sort_apps", "custom")
        set(v) = Prefs.putS("sort_apps", v)

    val dockVertical: Boolean get() = dockPos == "left" || dockStyle == "list"

    // Text
    var uiFontEn: String
        get() = Prefs.getS("ui_font_en", "inter")
        set(v) = Prefs.putS("ui_font_en", v)
    var uiFontAr: String
        get() = Prefs.getS("ui_font_ar", "plex")
        set(v) = Prefs.putS("ui_font_ar", v)

    // Wallpaper extras
    /** 0..6, tenths of darkening. */
    var wallDim: Int
        get() = Prefs.getI("wall_dim", 0)
        set(v) = Prefs.putI("wall_dim", v)
    /** 0 (sharp) .. 5 (very soft). */
    var wallBlur: Int
        get() = Prefs.getI("wall_blur", 0)
        set(v) = Prefs.putI("wall_blur", v)

    // Now playing
    /** "card", "pill" or "takeover". */
    var npStyle: String
        get() = Prefs.getS("np_style", "card")
        set(v) = Prefs.putS("np_style", v)

    /** Clock colour swatches; 0 marks "auto". */
    val colors: List<Pair<String, Int>> = listOf(
        "white" to 0xFFFFFFFF.toInt(),
        "auto" to 0,
        "sand" to 0xFFF2D6AE.toInt(),
        "rose" to 0xFFF5B7C5.toInt(),
        "sky" to 0xFFB3D5F7.toInt(),
        "mint" to 0xFFB6EAD1.toInt(),
        "lilac" to 0xFFD2C4F7.toInt(),
        "amber" to 0xFFFFC56E.toInt(),
    )

    fun clockColor(auto: Int): Int {
        val c = colors.firstOrNull { it.first == clockColor }?.second ?: Colors.WHITE
        return if (c == 0) auto else c
    }

    private val lookKeys = arrayOf(
        "clock_font", "clock_style", "clock_size", "clock_pos", "clock_color", "arabic_digits",
        "date_on", "hijri", "greet_on", "dock_style", "icon_shape", "dock_labels", "dock_pos", "card_art",
        "ui_font_en", "ui_font_ar", "wall_dim", "wall_blur", "np_style", "clock_seconds", "clock_24",
        "wall_interval", "wall_motion", "lyrics_size",
    )

    /** Forgets every look option. Language, dock apps and the wallpaper set stay. */
    fun reset() {
        Prefs.remove(*lookKeys)
    }

    val presets = listOf("default", "calm", "cinema", "arabic", "minimal")

    /**
     * One-press looks. Returns the wallpaper set folder to switch to, or null.
     * The caller recreates the screen when the language changed.
     */
    fun applyPreset(id: String): String? {
        reset()
        return when (id) {
            "calm" -> {
                clockFont = "inter_thin"; clockSize = 2; dockStyle = "pill"; dockLabels = false
                npStyle = "card"
                "dusk"
            }
            "cinema" -> {
                clockFont = "inter_bold"; clockStyle = "stacked"; clockSize = 3; clockPos = "center"
                dockStyle = "icons"; iconShape = "circle"; dockLabels = false; wallDim = 2
                npStyle = "takeover"
                "space"
            }
            "arabic" -> {
                Prefs.lang = "ar"
                clockFont = "plex_ar"; arabicDigits = true; hijri = true; uiFontAr = "plex"
                dockStyle = "cards"
                "desert"
            }
            "minimal" -> {
                clockFont = "outfit"; clockSize = 1; dockStyle = "list"; npStyle = "pill"
                wallBlur = 2; wallDim = 1
                "gradients"
            }
            else -> "dusk"
        }
    }
}
