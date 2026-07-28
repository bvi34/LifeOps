package com.citation.app.data.rr

import com.citation.core.rr.FictionCatalog
import com.citation.core.rr.RoyalRoadFeed
import com.citation.core.rr.RoyalRoadHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only class in the Royal Road stack that touches the network — a thin HTTP shell over
 * `HttpURLConnection`, mirroring how LifeOps isolates all NWS I/O in `NwsClient`. Every response is
 * handed straight to the pure [RoyalRoadHtml] / [RoyalRoadFeed] parsers in `:core`, so nothing here
 * contains extraction logic and the whole surface stays testable at the core boundary.
 *
 * Fetches are the *body puller* half of the design: they run only when the cross-lane queue and the
 * scrape [com.citation.core.rr.RateBudget] permit, never speculatively. A failure throws
 * [RoyalRoadException]; callers treat it as "couldn't fetch — leave the cache as-is".
 */
class RoyalRoadClient(
    private val baseUrl: String = "https://www.royalroad.com",
    private val userAgent: String = DEFAULT_USER_AGENT
) {
    class RoyalRoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Fetch + parse a fiction's chapter catalog (the skim view). */
    suspend fun fetchCatalog(fictionId: Long): FictionCatalog = withContext(Dispatchers.IO) {
        val html = get("$baseUrl/fiction/$fictionId")
        // Take the title from the page's og:title/<title> metadata, not the first heading: the header
        // chrome (notifications widget, etc.) renders <h*> elements before the fiction's own <h1>.
        val title = RoyalRoadHtml.extractFictionTitle(html) ?: "Royal Road #$fictionId"
        RoyalRoadHtml.parseFictionChapters(fictionId, title, html)
    }

    /** Fetch a chapter page's raw HTML (parsed by the caller via [RoyalRoadHtml.toChapter]). */
    suspend fun fetchChapterHtml(chapterUrl: String): String = withContext(Dispatchers.IO) {
        get(if (chapterUrl.startsWith("http")) chapterUrl else "$baseUrl$chapterUrl")
    }

    /** Fetch + parse the per-fiction syndication feed — the cheap update *detector*. */
    suspend fun fetchFeed(fictionId: Long): List<RoyalRoadFeed.FeedEntry> = withContext(Dispatchers.IO) {
        RoyalRoadFeed.parse(get("$baseUrl/fiction/syndication/$fictionId"))
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
            if (code !in 200..299) throw RoyalRoadException("HTTP $code for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: RoyalRoadException) {
            throw e
        } catch (e: Exception) {
            throw RoyalRoadException("Fetch failed for $url", e)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Citation/1.0 (personal reading app; contact via app store listing)"
    }
}
