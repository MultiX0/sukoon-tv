package dev.multix.tvhome.update

import org.json.JSONObject

/** A version such as 1.4.2, read from a tag like "v1.4.2" or "1.4.2-beta.1". */
data class Version(val parts: List<Int>, val pre: String?) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        // 1.2.0 is newer than 1.2.0-beta.1.
        return when {
            pre == other.pre -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> pre.compareTo(other.pre)
        }
    }

    override fun toString(): String = parts.joinToString(".") + (pre?.let { "-$it" } ?: "")

    companion object {
        private val pattern = Regex("""^\s*[vV]?(\d{1,6}(?:\.\d{1,6}){0,3})(?:-([0-9A-Za-z.-]+))?(?:\+\S*)?\s*$""")

        fun parse(s: String?): Version? {
            val m = pattern.find(s ?: return null) ?: return null
            return Version(m.groupValues[1].split('.').map { it.toInt() }, m.groupValues[2].ifEmpty { null })
        }
    }
}

/** The newest release on GitHub, reduced to what the updater needs. */
class Release(
    val version: Version,
    val tag: String,
    val notes: String,
    val page: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Hex SHA-256 of the APK when GitHub reports it (the asset "digest" field). */
    val sha256: String?,
)

/** Why a release response can't be used. */
class ReleaseError(val reason: Updater.Reason) : Exception(reason.name)

object Releases {

    /**
     * Reads GitHub's "latest release" JSON. Drafts and pre-releases are refused (the endpoint never returns
     * them, a mock server might). The APK is the uploaded .apk asset, preferring one named after the launcher.
     */
    fun parse(json: String, brand: String): Release {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw ReleaseError(Updater.Reason.BAD_RESPONSE)
        }
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) throw ReleaseError(Updater.Reason.NO_RELEASES)
        val tag = o.optString("tag_name")
        val version = Version.parse(tag) ?: throw ReleaseError(Updater.Reason.BAD_RESPONSE)
        val assets = o.optJSONArray("assets")
        var best: JSONObject? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                if (a.has("state") && a.optString("state") != "uploaded") continue
                if (a.optString("browser_download_url").isEmpty()) continue
                if (best == null || name.contains(brand, ignoreCase = true)) best = a
            }
        }
        val apk = best ?: throw ReleaseError(Updater.Reason.NO_APK)
        val digest = apk.optString("digest")
        return Release(
            version = version,
            tag = tag,
            notes = cleanNotes(o.optString("body")),
            page = o.optString("html_url"),
            apkName = apk.optString("name"),
            apkUrl = apk.optString("browser_download_url"),
            apkSize = apk.optLong("size", -1L),
            sha256 = digest.removePrefix("sha256:").lowercase().takeIf { digest.startsWith("sha256:") && it.length == 64 },
        )
    }

    /** Release notes as plain text for the TV: Markdown marks removed, at most 8 lines. */
    fun cleanNotes(body: String?): String {
        if (body.isNullOrBlank()) return ""
        return body.replace("\r", "").lines()
            .map { line ->
                line.trim()
                    .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
                    .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
                    .replace(Regex("""^#{1,6}\s*"""), "")
                    .replace(Regex("""^[-*+]\s+"""), "• ")
                    .replace(Regex("""(\*\*|__|`)"""), "")
                    .trim()
            }
            .filter { it.isNotEmpty() && !it.startsWith("<!--") }
            .take(8)
            .joinToString("\n")
            .let { if (it.length > 420) it.substring(0, 419).trimEnd() + "…" else it }
    }
}
