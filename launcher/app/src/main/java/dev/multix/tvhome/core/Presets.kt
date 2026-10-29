package dev.multix.tvhome.core

import android.content.Context
import android.content.SharedPreferences
import dev.multix.tvhome.R
import dev.multix.tvhome.wall.Walls
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's own looks. A look is a snapshot of every setting (clock, date, dock and its apps, now playing,
 * wallpaper set and photo, My picks, language, fonts, weather), so applying one brings back the exact home
 * screen. Before any look is applied, built in or saved, the current setup is kept as "previous", so nothing
 * is ever lost by trying a look.
 */
object Presets {
    private const val FILE = "presets"
    /** Settings that describe the moment, not the look. */
    private val skip = setOf("last_focus")

    class Preset(val id: String, val name: String, val created: Long, val data: JSONObject)

    private fun store(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun settings(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun walls(ctx: Context): SharedPreferences = ctx.getSharedPreferences("walls", Context.MODE_PRIVATE)

    /** Every setting with its type, plus My picks. */
    fun snapshot(ctx: Context): JSONObject {
        val values = JSONObject()
        for ((k, v) in settings(ctx).all) {
            if (k in skip || v == null) continue
            val t = when (v) {
                is String -> "s"
                is Boolean -> "b"
                is Int -> "i"
                is Long -> "l"
                is Float -> "f"
                is Set<*> -> "set"
                else -> continue
            }
            values.put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) JSONArray(v.map { it.toString() }) else v))
        }
        return JSONObject()
            .put("settings", values)
            .put("picks", walls(ctx).getString("picks", "") ?: "")
    }

    /** Writes a snapshot back. The caller then rebuilds the home screen (HomeActivity.applySavedLook). */
    fun restore(ctx: Context, data: JSONObject) {
        val values = data.optJSONObject("settings") ?: return
        val sp = settings(ctx)
        val e = sp.edit()
        for (k in sp.all.keys) if (k !in skip) e.remove(k)
        for (k in values.keys()) {
            val o = values.optJSONObject(k) ?: continue
            when (o.optString("t")) {
                "s" -> e.putString(k, o.optString("v"))
                "b" -> e.putBoolean(k, o.optBoolean("v"))
                "i" -> e.putInt(k, o.optInt("v"))
                "l" -> e.putLong(k, o.optLong("v"))
                "f" -> e.putFloat(k, o.optDouble("v").toFloat())
                "set" -> e.putStringSet(k, o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
            }
        }
        // Synchronous: the screen is recreated right after and must read the restored values.
        e.commit()
        walls(ctx).edit().putString("picks", data.optString("picks")).commit()
        Walls.attachPicks(ctx)
    }

    fun list(ctx: Context): List<Preset> {
        val a = try { JSONArray(store(ctx).getString("list", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Preset(o.optString("id"), o.optString("name"), o.optLong("created"), o.optJSONObject("data") ?: return@mapNotNull null)
        }
    }

    private fun write(ctx: Context, list: List<Preset>) {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().put("id", p.id).put("name", p.name).put("created", p.created).put("data", p.data))
        store(ctx).edit().putString("list", a.toString()).commit()
    }

    fun find(ctx: Context, id: String): Preset? = list(ctx).firstOrNull { it.id == id }

    /** Saves the current setup as a new look named "My look N". */
    fun saveCurrent(ctx: Context): Preset {
        val list = list(ctx)
        var n = list.size + 1
        // In the UI language, also when called with the plain application context at start.
        val base = Lang.wrap(ctx).getString(R.string.pr_default_name)
        while (list.any { it.name == "$base $n" }) n++
        val p = Preset("p${System.currentTimeMillis()}", "$base $n", System.currentTimeMillis(), snapshot(ctx))
        write(ctx, list + p)
        return p
    }

    fun replace(ctx: Context, id: String) {
        write(ctx, list(ctx).map { if (it.id == id) Preset(it.id, it.name, it.created, snapshot(ctx)) else it })
    }

    fun rename(ctx: Context, id: String, name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        write(ctx, list(ctx).map { if (it.id == id) Preset(it.id, clean, it.created, it.data) else it })
    }

    fun delete(ctx: Context, id: String) {
        write(ctx, list(ctx).filter { it.id != id })
    }

    /** Keeps the current setup so the next look change can be undone. */
    fun rememberPrevious(ctx: Context) {
        store(ctx).edit().putString("previous", snapshot(ctx).toString()).commit()
    }

    fun previous(ctx: Context): JSONObject? =
        store(ctx).getString("previous", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }

    /**
     * Once, on the first start of a version with looks: the home screen as the user left it becomes their
     * first saved look, before anything else can change it.
     */
    fun saveFirstLook(ctx: Context) {
        val st = store(ctx)
        if (st.getBoolean("first_saved", false)) return
        if (list(ctx).isEmpty() && settings(ctx).all.keys.any { it !in skip }) saveCurrent(ctx)
        st.edit().putBoolean("first_saved", true).apply()
    }

    /** A short description for the list: wallpaper set, clock font, dock style. */
    fun summary(ctx: Context, p: Preset): String {
        val v = p.data.optJSONObject("settings") ?: return ""
        fun s(k: String) = v.optJSONObject(k)?.optString("v").orEmpty()
        val wall = Walls.sets.firstOrNull { it.id == s("wall_set") }?.name
        val font = Fonts.clock.firstOrNull { it.id == s("clock_font") }?.name
        return listOfNotNull(wall, font).joinToString(" · ")
    }
}
