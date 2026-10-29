package dev.multix.tvhome.weather

import dev.multix.tvhome.R
import dev.multix.tvhome.core.Http
import org.json.JSONObject
import kotlin.math.roundToInt

/** Current conditions from Open-Meteo, which needs no API key. */
object Weather {
    class Place(val name: String, val lat: Double, val lon: Double)
    class Now(val temp: Int, val code: Int, val at: Long)

    /** Background thread only. Returns null when nothing matched or the network failed. */
    fun geocode(city: String, lang: String): Place? {
        val r = Http.get("https://geocoding-api.open-meteo.com/v1/search?count=1&format=json" +
            "&language=$lang&name=${Http.enc(city.trim())}")
        val body = r.body ?: return null
        val res = JSONObject(body).optJSONArray("results") ?: return null
        val o = res.optJSONObject(0) ?: return null
        val country = o.optString("country", "")
        val name = o.optString("name", city) + if (country.isNotEmpty()) ", $country" else ""
        return Place(name, o.getDouble("latitude"), o.getDouble("longitude"))
    }

    /** Background thread only. */
    fun current(lat: Double, lon: Double, fahrenheit: Boolean): Now? {
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val r = Http.get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code&temperature_unit=$unit&timezone=auto")
        val body = r.body ?: return null
        val cur = JSONObject(body).optJSONObject("current") ?: return null
        return Now(cur.optDouble("temperature_2m").roundToInt(), cur.optInt("weather_code"), System.currentTimeMillis())
    }

    /** WMO weather interpretation codes, grouped the way people talk about the sky. */
    fun label(code: Int): Int = when (code) {
        0 -> R.string.wx_clear
        1 -> R.string.wx_mostly_clear
        2 -> R.string.wx_partly_cloudy
        3 -> R.string.wx_cloudy
        45, 48 -> R.string.wx_fog
        in 51..57 -> R.string.wx_drizzle
        in 61..67 -> R.string.wx_rain
        in 71..77, 85, 86 -> R.string.wx_snow
        in 80..82 -> R.string.wx_showers
        in 95..99 -> R.string.wx_thunder
        else -> R.string.wx_cloudy
    }
}
