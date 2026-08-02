package com.logistics.app.net

import com.logistics.app.data.model.ParsedRecipe
import com.logistics.app.logic.RecipeLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a recipe web page and hands its HTML to the framework-free [RecipeLinkParser]. The only
 * networked class in the recipe-import path; parsing (schema.org/Recipe extraction) is pure and
 * unit-tested. Built on HttpURLConnection to avoid adding an HTTP library, mirroring LifeOps'
 * NwsClient. Follows redirects (incl. http→https) and sends a browser-like User-Agent because many
 * recipe sites serve stripped markup to unknown clients.
 */
object RecipeFetcher {

    class FetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val USER_AGENT =
        "Mozilla/5.0 (Android) Logistics/1.0 (+recipe import)"

    suspend fun fetch(url: String): ParsedRecipe = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(url)
        val html = get(normalized)
        RecipeLinkParser.parse(html, normalized)
            ?: throw FetchException("No recipe data found on that page")
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun get(urlString: String, redirectsLeft: Int = 4): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        try {
            val code = conn.responseCode
            // Handle cross-protocol redirects HttpURLConnection won't follow itself.
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                if (location != null && redirectsLeft > 0) {
                    val next = URL(URL(urlString), location).toString()
                    return get(next, redirectsLeft - 1)
                }
            }
            if (code !in 200..299) throw FetchException("Server returned HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: FetchException) {
            throw e
        } catch (e: Exception) {
            throw FetchException("Could not load the page: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}
