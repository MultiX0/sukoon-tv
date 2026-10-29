package dev.multix.tvhome.core

import dev.multix.tvhome.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal GET client. Always called from the background thread. */
object Http {
    val userAgent = "${BuildConfig.BRAND}/${BuildConfig.VERSION_NAME} (Android TV launcher)"

    class Response(val code: Int, val body: String?)

    /**
     * After a network failure every request is skipped for a while, so an
     * offline box never stalls the background thread on DNS or connects.
     */
    @Volatile
    private var offlineUntil = 0L
    private const val BACKOFF_MS = 5 * 60_000L

    fun get(url: String, timeoutMs: Int = 8000): Response {
        if (System.currentTimeMillis() < offlineUntil) return Response(-1, null)
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
            Response(code, body)
        } catch (e: Exception) {
            offlineUntil = System.currentTimeMillis() + BACKOFF_MS
            Bg.warn("GET failed: ${e.javaClass.simpleName}; network calls paused for 5 minutes")
            Response(-1, null)
        } finally {
            conn?.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
