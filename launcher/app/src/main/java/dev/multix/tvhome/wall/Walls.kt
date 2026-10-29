package dev.multix.tvhome.wall

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import dev.multix.tvhome.core.Images
import dev.multix.tvhome.core.Lang
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * A wallpaper set is a folder of images. Sets come from the APK assets
 * (bundled at build time from tv-box/wallpapers) and from /sdcard/Wallpapers,
 * so people can add their own without a rebuild. Ids are "a:<name>" for
 * bundled sets and "u:<name>" for user folders.
 */
class WallSet(
    val id: String,
    val folder: String,
    val user: Boolean,
    val files: List<String>,
    /** For "My picks": the set id and file name of every picked photo. */
    val refs: List<Pair<String, String>>? = null,
) {
    val picks: Boolean get() = refs != null
    var nameEn: String = pretty(folder)
    var nameAr: String? = null
    val name: String get() = if (Lang.isArabic) nameAr ?: nameEn else nameEn

    fun open(ctx: Context, index: Int): InputStream? {
        if (files.isEmpty()) return null
        refs?.let { r ->
            val (setId, file) = r[Math.floorMod(index, r.size)]
            val src = Walls.sets.firstOrNull { it.id == setId } ?: return null
            return src.open(ctx, src.files.indexOf(file).takeIf { it >= 0 } ?: return null)
        }
        val f = files[Math.floorMod(index, files.size)]
        return try {
            if (user) FileInputStream(File(Walls.userRoot, "$folder/$f"))
            else ctx.assets.open("${Walls.ASSET_ROOT}/$folder/$f")
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun pretty(folder: String): String =
            folder.replace('_', ' ').replace('-', ' ').trim()
                .split(' ').filter { it.isNotEmpty() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
    }
}

object Walls {
    const val ASSET_ROOT = "wallpapers"
    private val exts = setOf("jpg", "jpeg", "png", "webp")

    val userRoot: File get() = File(Environment.getExternalStorageDirectory(), "Wallpapers")

    /**
     * Arabic names for the curated sets, used when sets.json only carries an
     * English name. sets.json may also give {"en": "...", "ar": "..."} per set.
     */
    private val arabic = mapOf(
        "dusk" to "الغروب",
        "mist" to "الضباب",
        "desert" to "الصحراء",
        "earth" to "الطبيعة",
        "gradients" to "تدرجات",
        "space" to "الفضاء",
    )

    private class Names(val order: List<String>, val en: Map<String, String>, val ar: Map<String, String>)

    private fun names(json: String?): Names {
        val order = ArrayList<String>()
        val en = HashMap<String, String>()
        val ar = HashMap<String, String>()
        if (json != null) {
            try {
                val o = JSONObject(json)
                // Android's JSONObject keeps file order, so sets.json also sets the order.
                for (k in o.keys()) {
                    order.add(k)
                    val v = o.get(k)
                    if (v is JSONObject) {
                        v.optString("en").takeIf { it.isNotBlank() }?.let { en[k] = it }
                        v.optString("ar").takeIf { it.isNotBlank() }?.let { ar[k] = it }
                    } else if (v is String && v.isNotBlank()) {
                        en[k] = v
                    }
                }
            } catch (e: Exception) {
                // A broken sets.json only costs the nice names.
            }
        }
        return Names(order, en, ar)
    }

    private fun applyNames(set: WallSet, n: Names) {
        n.en[set.folder]?.let { set.nameEn = it }
        set.nameAr = n.ar[set.folder] ?: arabic[set.folder.lowercase()]
    }

    @Volatile
    var sets: List<WallSet> = emptyList()
        private set

    @Volatile
    var defaultId: String = ""
        private set

    /** Lists bundled and user sets. Background thread only. */
    fun scan(ctx: Context): List<WallSet> {
        val list = ArrayList<WallSet>()
        val am = ctx.assets
        val bundledNames = names(try {
            am.open("$ASSET_ROOT/sets.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) { null })
        val present = (am.list(ASSET_ROOT) ?: emptyArray()).toSet()
        val folders = bundledNames.order.filter { it in present } +
            present.filter { it !in bundledNames.order }.sorted()
        for (folder in folders) {
            val files = (am.list("$ASSET_ROOT/$folder") ?: emptyArray())
                .filter { it.substringAfterLast('.', "").lowercase() in exts }
                .sorted()
            if (files.isNotEmpty()) list.add(WallSet("a:$folder", folder, false, files).also { applyNames(it, bundledNames) })
        }
        val bundled = list.size

        val root = userRoot
        val dirs = try { root.listFiles() } catch (e: SecurityException) { null }
        val userNames = names(try { File(root, "sets.json").takeIf { it.isFile }?.readText() } catch (e: Exception) { null })
        dirs?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { dir ->
                val files = (dir.list() ?: emptyArray())
                    .filter { it.substringAfterLast('.', "").lowercase() in exts }
                    .sorted()
                if (files.isNotEmpty()) list.add(WallSet("u:${dir.name}", dir.name, true, files).also { applyNames(it, userNames) })
            }

        val wanted = try {
            am.open("$ASSET_ROOT/default.txt").bufferedReader().use { it.readLine()?.trim() }
        } catch (e: Exception) {
            null
        }
        defaultId = list.take(bundled).firstOrNull { it.folder == wanted }?.id
            ?: list.firstOrNull()?.id ?: ""
        sets = list
        attachPicks(ctx)
        return sets
    }

    fun find(id: String): WallSet? = sets.firstOrNull { it.id == id } ?: sets.firstOrNull { it.id == defaultId }

    fun decode(ctx: Context, set: WallSet, index: Int, w: Int, h: Int): Bitmap? =
        Images.decodeCover({ set.open(ctx, index) }, w, h)

    /** Rounded 16:9 thumbnail of a set's first image. */
    fun thumb(ctx: Context, set: WallSet, w: Int, h: Int, radius: Float): Bitmap? {
        val src = Images.decodeCover({ set.open(ctx, 0) }, w, h) ?: return null
        val out = Images.roundedCover(src, w, h, radius)
        src.recycle()
        return out
    }

    /** Rounded 16:9 thumbnail of one photo of a set. */
    fun photoThumb(ctx: Context, set: WallSet, index: Int, w: Int, h: Int, radius: Float): Bitmap? {
        val src = Images.decodeCover({ set.open(ctx, index) }, w, h) ?: return null
        val out = Images.roundedCover(src, w, h, radius)
        src.recycle()
        return out
    }

    // My picks: single photos chosen from any set, rotated as one set.

    const val PICKS_ID = "p:picks"
    private const val PREFS = "walls"

    private fun pickRefs(ctx: Context): List<Pair<String, String>> =
        (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("picks", "") ?: "")
            .split('\n').filter { it.contains('|') }
            .map { it.substringBefore('|') to it.substringAfter('|') }
            .filter { (id, f) -> sets.any { s -> s.id == id && f in s.files } }

    fun isPicked(ctx: Context, setId: String, file: String): Boolean = (setId to file) in pickRefs(ctx)

    fun pickCount(ctx: Context): Int = pickRefs(ctx).size

    /** Adds or removes a photo from My picks and rebuilds the picks set. Returns whether it is now picked. */
    fun togglePick(ctx: Context, setId: String, file: String): Boolean {
        val list = pickRefs(ctx).toMutableList()
        val ref = setId to file
        val now = if (list.remove(ref)) false else { list.add(ref); true }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("picks", list.joinToString("\n") { "${it.first}|${it.second}" }).apply()
        attachPicks(ctx)
        return now
    }

    /** Puts the My picks set first in [sets], or removes it when nothing is picked. */
    fun attachPicks(ctx: Context) {
        val base = sets.filter { !it.picks }
        val refs = pickRefs(ctx)
        sets = if (refs.isEmpty()) base else listOf(
            WallSet(PICKS_ID, "picks", false, refs.map { it.second }, refs).apply {
                nameEn = "My picks"
                nameAr = "مختاراتي"
            }
        ) + base
    }

    fun hasUserFolder(): Boolean = try { userRoot.isDirectory } catch (e: SecurityException) { false }
}
