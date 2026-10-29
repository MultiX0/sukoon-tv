package dev.multix.tvhome.core

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/** Every user setting, backed by one SharedPreferences file. */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val listeners = ArrayList<() -> Unit>()

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners.toTypedArray()) l() }

    // Generic accessors for the look options (see Look).
    fun getS(key: String, def: String): String = sp.getString(key, null) ?: def
    fun getI(key: String, def: Int): Int = sp.getInt(key, def)
    fun getB(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    fun putS(key: String, v: String) = putStr(key, v)
    fun putI(key: String, v: Int) = putInt(key, v)
    fun putB(key: String, v: Boolean) = putBool(key, v)
    fun remove(vararg keys: String) {
        val e = sp.edit()
        for (k in keys) e.remove(k)
        e.apply()
        changed()
    }

    private fun str(key: String, def: String): String = sp.getString(key, null) ?: def
    private fun putStr(key: String, v: String) { sp.edit().putString(key, v).apply(); changed() }
    private fun putInt(key: String, v: Int) { sp.edit().putInt(key, v).apply(); changed() }
    private fun putBool(key: String, v: Boolean) { sp.edit().putBoolean(key, v).apply(); changed() }

    /** "en", "ar" or "system". */
    var lang: String
        get() = str("lang", "system")
        set(v) = putStr("lang", v)

    /** The language actually in use once "system" is resolved. */
    val langResolved: String
        get() = lang.let { if (it == "system") (if (Locale.getDefault().language == "ar") "ar" else "en") else it }

    // Wallpaper
    var wallSet: String
        get() = str("wall_set", "")
        set(v) = putStr("wall_set", v)
    var wallIndex: Int
        get() = sp.getInt("wall_index", 0)
        set(v) { sp.edit().putInt("wall_index", v).apply() }
    /** Minutes between wallpapers: 0 (off), 1, 5, 10 or 30. */
    var wallInterval: Int
        get() = sp.getInt("wall_interval", 10)
        set(v) = putInt("wall_interval", v)
    var wallMotion: Boolean
        get() = sp.getBoolean("wall_motion", true)
        set(v) = putBool("wall_motion", v)

    // Clock. The 24 hour default follows the system setting until the user picks.
    fun clock24(ctx: Context): Boolean =
        if (sp.contains("clock_24")) sp.getBoolean("clock_24", true)
        else android.text.format.DateFormat.is24HourFormat(ctx)
    fun setClock24(v: Boolean) = putBool("clock_24", v)
    var clockSeconds: Boolean
        get() = sp.getBoolean("clock_seconds", false)
        set(v) = putBool("clock_seconds", v)

    // Lyrics
    var lyricsOn: Boolean
        get() = sp.getBoolean("lyrics_on", true)
        set(v) = putBool("lyrics_on", v)
    /** 0 small, 1 medium, 2 large. */
    var lyricsSize: Int
        get() = sp.getInt("lyrics_size", 1)
        set(v) = putInt("lyrics_size", v)
    /** Seconds of no input before full screen lyrics; 0 turns it off. */
    var ambientDelay: Int
        get() = sp.getInt("ambient_delay", 20)
        set(v) = putInt("ambient_delay", v)
    /** "art" or "wall". */
    var ambientBg: String
        get() = str("ambient_bg", "art")
        set(v) = putStr("ambient_bg", v)

    // Weather, off until the user types a city.
    var weatherOn: Boolean
        get() = sp.getBoolean("weather_on", false)
        set(v) = putBool("weather_on", v)
    var weatherCity: String
        get() = str("weather_city", "")
        set(v) = putStr("weather_city", v)
    var weatherPlace: String
        get() = str("weather_place", "")
        set(v) = putStr("weather_place", v)
    var weatherLat: Float
        get() = sp.getFloat("weather_lat", Float.NaN)
        set(v) { sp.edit().putFloat("weather_lat", v).apply() }
    var weatherLon: Float
        get() = sp.getFloat("weather_lon", Float.NaN)
        set(v) { sp.edit().putFloat("weather_lon", v).apply() }
    var fahrenheit: Boolean
        get() = sp.getBoolean("weather_f", false)
        set(v) = putBool("weather_f", v)

    // Apps
    var dock: List<String>
        get() = csv(sp.getString("dock", null))
        set(v) = putStr("dock", v.joinToString(","))
    val hasDock: Boolean get() = sp.contains("dock")
    fun resetDock() { sp.edit().remove("dock").apply(); changed() }

    var hidden: Set<String>
        get() = csv(sp.getString("hidden", null)).toSet()
        set(v) = putStr("hidden", v.joinToString(","))

    var lastFocus: String
        get() = str("last_focus", "")
        set(v) { sp.edit().putString("last_focus", v).apply() }

    private fun csv(s: String?): List<String> =
        if (s.isNullOrEmpty()) emptyList() else s.split(',').filter { it.isNotEmpty() }
}
