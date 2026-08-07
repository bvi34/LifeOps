package com.citation.app.data.ao3

import com.citation.core.ao3.Ao3Catalog
import com.citation.core.ao3.Ao3Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only class in the Archive of Our Own stack that touches the network — a thin HTTP shell over
 * `HttpURLConnection`, mirroring [com.citation.app.data.rr.RoyalRoadClient]. Every response is handed
 * straight to the pure [Ao3Html] parser in `:core`, so nothing here contains extraction logic and the
 * whole surface stays testable at the core boundary.
 *
 * Two AO3 specifics: requests append `view_adult=true` so a work behind the "this work could have
 * adult content" interstitial still returns its body, and there is **no syndication feed** — the
 * catalog fetch doubles as the update detector's input (see the coordinator's poll).
 *
 * A failure throws [Ao3Exception]; callers treat it as "couldn't fetch — leave the cache as-is".
 */
class Ao3Client(
    private val baseUrl: String = "https://archiveofourown.org",
    private val userAgent: String = DEFAULT_USER_AGENT
) {
    class Ao3Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Fetch + parse a work's chapter catalog (the skim view). AO3's chapter-navigation dropdown lists
     * every chapter on any chapter page, so the work page alone yields the whole catalog.
     */
    suspend fun fetchCatalog(workId: Long): Ao3Catalog = withContext(Dispatchers.IO) {
        val html = get("$baseUrl/works/$workId?view_adult=true")
        val title = Ao3Html.extractWorkTitle(html) ?: "AO3 #$workId"
        val author = Ao3Html.extractWorkAuthor(html)
        Ao3Html.parseWorkChapters(workId, title, author, html)
    }

    /** Fetch a chapter page's raw HTML (parsed by the caller via [Ao3Html.toChapter]). */
    suspend fun fetchChapterHtml(chapterUrl: String): String = withContext(Dispatchers.IO) {
        val base = if (chapterUrl.startsWith("http")) chapterUrl else "$baseUrl$chapterUrl"
        val withAdult = if (base.contains("?")) "$base&view_adult=true" else "$base?view_adult=true"
        get(withAdult)
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw Ao3Exception("HTTP $code for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Ao3Exception) {
            throw e
        } catch (e: Exception) {
            throw Ao3Exception("Fetch failed for $url", e)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Citation/1.0 (personal reading app; contact via app store listing)"
    }
}
