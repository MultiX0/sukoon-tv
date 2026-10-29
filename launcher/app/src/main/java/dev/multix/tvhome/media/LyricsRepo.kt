package dev.multix.tvhome.media

import android.content.Context
import dev.multix.tvhome.core.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Time-synced lyrics from LRCLIB (lrclib.net), cached on disk per track. A
 * miss is cached too, for a few days, so a song without lyrics does not cost
 * a request every time it comes back around.
 */
object LyricsRepo {
    private const val API = "https://lrclib.net/api"
    private const val MISS_TTL = 3L * 24 * 3600 * 1000

    class Result(val synced: String?, val plain: String?)

    /** Background thread only. */
    fun get(ctx: Context, title: String, artist: String, album: String, durationMs: Long): Lyrics? {
        val file = cacheFile(ctx, "$artist|$title|$album|${durationMs / 1000}")
        val cached = readCache(file)
        val res = cached ?: fetch(title, artist, album, durationMs)?.also { writeCache(file, it) }
        res ?: return null
        res.synced?.let { s -> Lrc.parse(s)?.let { return it } }
        res.plain?.let { p -> return Lrc.plain(p, durationMs) }
        return null
    }

    private fun fetch(title: String, artist: String, album: String, durationMs: Long): Result? {
        val secs = durationMs / 1000
        val q = Clean.of(title, artist)

        // 1. Exact signature match.
        if (artist.isNotBlank() && secs > 0) {
            val url = StringBuilder("$API/get?artist_name=").append(Http.enc(artist))
                .append("&track_name=").append(Http.enc(title))
            if (album.isNotBlank()) url.append("&album_name=").append(Http.enc(album))
            url.append("&duration=").append(secs)
            val r = Http.get(url.toString())
            if (r.code == 200 && r.body != null) {
                val o = JSONObject(r.body)
                val res = Result(o.optStringOrNull("syncedLyrics"), o.optStringOrNull("plainLyrics"))
                if (res.synced != null || res.plain != null) return res
            } else if (r.code < 0) {
                return null
            }
        }

        // 2. Search with a cleaned title, then a free text query.
        val first = search("$API/search?track_name=${Http.enc(q.title)}" +
            if (q.artist.isNotBlank()) "&artist_name=${Http.enc(q.artist)}" else "", secs)
        if (first == null) return null
        if (first.synced != null || first.plain != null) return first
        return search("$API/search?q=${Http.enc(q.free)}", secs)
    }

    /** Returns null only on a network failure, so failures are not cached as misses. */
    private fun search(url: String, secs: Long): Result? {
        val r = Http.get(url)
        if (r.code < 0) return null
        val arr = try { JSONArray(r.body ?: "[]") } catch (e: Exception) { JSONArray() }
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until minOf(arr.length(), 20)) {
            val o = arr.optJSONObject(i) ?: continue
            val synced = o.optStringOrNull("syncedLyrics") != null
            val plain = o.optStringOrNull("plainLyrics") != null
            if (!synced && !plain) continue
            val d = o.optDouble("duration", 0.0)
            var score = if (synced) 4 else 0
            if (secs > 0 && d > 0) {
                val diff = abs(d - secs)
                score += when {
                    diff <= 2 -> 4
                    diff <= 6 -> 2
                    diff <= 15 -> 0
                    else -> -3
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = o
            }
        }
        val b = best ?: return Result(null, null)
        if (bestScore < 0) return Result(null, null)
        return Result(b.optStringOrNull("syncedLyrics"), b.optStringOrNull("plainLyrics"))
    }

    private fun cacheFile(ctx: Context, key: String): File {
        val md = MessageDigest.getInstance("SHA-1").digest(key.lowercase().toByteArray())
        val name = md.joinToString("") { "%02x".format(it) }
        return File(File(ctx.filesDir, "lyrics").apply { mkdirs() }, "$name.json")
    }

    private fun readCache(f: File): Result? {
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            val synced = o.optStringOrNull("synced")
            val plain = o.optStringOrNull("plain")
            if (synced == null && plain == null && System.currentTimeMillis() - o.optLong("at") > MISS_TTL) null
            else Result(synced, plain)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCache(f: File, r: Result) {
        try {
            val o = JSONObject()
            o.put("synced", r.synced ?: JSONObject.NULL)
            o.put("plain", r.plain ?: JSONObject.NULL)
            o.put("at", System.currentTimeMillis())
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            // A failed cache write only costs a refetch next time.
        }
    }

    private fun JSONObject.optStringOrNull(k: String): String? =
        if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
}

/**
 * Video players report titles like "Artist - Song (Official Video)" and
 * channel names like "ArtistVEVO". This turns them into something a lyrics
 * search can match.
 */
object Clean {
    class Query(val title: String, val artist: String, val free: String)

    private val noise = Regex(
        """\s*[(\[][^)\]]*(official|video|audio|lyric|visuali[sz]er|remaster|hd|4k|mv|m/v|clip)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val suffix = Regex(
        """\s+-\s+(\d{4}\s+)?(remaster|remastered|live|radio edit|single version|mono|stereo)\b.*$""",
        RegexOption.IGNORE_CASE
    )
    private val feat = Regex("""\s*[(\[]?\s*(feat\.?|ft\.?|featuring)\s+[^)\]]*[)\]]?""", RegexOption.IGNORE_CASE)

    fun of(title: String, artist: String): Query {
        var t = suffix.replace(noise.replace(title, ""), "").trim()
        var a = artist.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""VEVO$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*official$""", RegexOption.IGNORE_CASE), "")
            .trim()
        // "Artist - Song" titles only get split when the left side looks like
        // the artist, so a Spotify title such as "Song - Acoustic" survives.
        val dash = t.indexOf(" - ")
        var split = false
        if (dash > 0) {
            val left = t.substring(0, dash).trim()
            val right = t.substring(dash + 3).trim()
            if (a.isEmpty() || left.equals(a, true) || left.contains(a, true) || a.contains(left, true)) {
                a = left
                t = right
                split = true
            }
        }
        t = feat.replace(t, "").trim().trim('"', '\'')
        val free = if (split || dash < 0) "$a $t" else t.replace(" - ", " ")
        return Query(t, a, free.trim())
    }
}
