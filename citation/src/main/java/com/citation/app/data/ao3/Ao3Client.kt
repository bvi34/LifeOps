package com.citation.app.data.ao3

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only class in the Archive of Our Own stack that touches the network.
 *
 * AO3 is ingested via its **official EPUB download**, not by scraping HTML: scraping is fragile
 * (works redirect through adult-content gates, some require login, the markup drifts), whereas the
 * sanctioned download at `/downloads/{workId}/work.epub` is one complete, robustly-parseable file
 * that the shared [com.citation.core.epub.EpubParser] already handles. The slug and `updated_at`
 * query param AO3 puts on its own download links are optional — a bare `work.epub` resolves fine.
 *
 * Redirects are followed **manually**: the download 302-hops to `download.archiveofourown.org`, and
 * relying on `HttpURLConnection`'s built-in redirect handling proved unreliable in practice (the
 * earlier scraping attempt landed on a pre-redirect page). Following them ourselves is deterministic.
 *
 * A failure throws [Ao3Exception]; callers treat it as "couldn't fetch — nothing added".
 */
class Ao3Client(
    private val baseUrl: String = "https://archiveofourown.org",
    private val userAgent: String = DEFAULT_USER_AGENT
) {
    class Ao3Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Download a work's official EPUB export as raw bytes, ready for the EPUB parser. */
    suspend fun downloadEpub(workId: Long): ByteArray = withContext(Dispatchers.IO) {
        getFollowingRedirects("$baseUrl/downloads/$workId/work.epub")
    }

    private fun getFollowingRedirects(startUrl: String, maxHops: Int = 5): ByteArray {
        var url = startUrl
        var hops = 0
        while (true) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/epub+zip, application/epub, */*")
                instanceFollowRedirects = false // we hop manually so cross-host 3xx is deterministic
                connectTimeout = 20_000
                readTimeout = 20_000
            }
            try {
                val code = conn.responseCode
                when {
                    code in 200..299 -> return conn.inputStream.use { it.readBytes() }
                    code in 300..399 -> {
                        val location = conn.getHeaderField("Location")
                            ?: throw Ao3Exception("HTTP $code with no Location for $url")
                        if (++hops > maxHops) throw Ao3Exception("Too many redirects from $startUrl")
                        // Resolve relative Location against the current URL.
                        url = URL(URL(url), location).toString()
                    }
                    else -> throw Ao3Exception("HTTP $code for $url")
                }
            } catch (e: Ao3Exception) {
                throw e
            } catch (e: Exception) {
                throw Ao3Exception("Download failed for $url", e)
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Android) Citation/1.0 (personal reading app; contact via app store listing)"
    }
}
