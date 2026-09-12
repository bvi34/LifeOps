package com.citation.app.data.opds

import com.citation.core.opds.CatalogDecoder
import com.citation.core.opds.CatalogSource
import com.citation.core.opds.OpdsFeed
import com.citation.core.opds.OpdsUrl
import com.citation.core.opds.OpenSearchDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only class in the OPDS stack that touches the network — the `NwsClient`/`NwsParser` split
 * again, so every decision about what a feed *means* stays in JVM-tested `:core` and this file only
 * moves bytes.
 *
 * What it has to cope with, because real catalogs do all of it: HTTP Basic on private servers,
 * redirects across hosts (a library proxy, an http→https upgrade), servers that mislabel content
 * types, and downloads that arrive without a filename. Failures come back as a typed [Result]
 * rather than exceptions, because "this catalog is unreachable" is an ordinary state for a browser
 * pointed at a NAS that is currently asleep — it should render as a message with a retry, not a
 * crash.
 */
class OpdsClient(
    private val credentials: CatalogCredentials?,
    private val userAgent: String = DEFAULT_USER_AGENT
) {

    /** What a fetch produced. Every failure is nameable, so the UI can say something useful. */
    sealed class Result<out T> {
        data class Success<T>(val value: T) : Result<T>()

        /** The server wants (different) credentials. */
        object Unauthorized : Result<Nothing>()

        /** Reached the server, but it did not answer with a catalog. */
        data class NotACatalog(val looksLikeHtml: Boolean) : Result<Nothing>()

        /** Never reached it: no network, wrong host, TLS failure, timeout. */
        data class Unreachable(val message: String) : Result<Nothing>()

        /** Reached it and it said no. */
        data class HttpError(val code: Int) : Result<Nothing>()

        /** The catalog simply does not offer what was asked for (search, most often). */
        object Unsupported : Result<Nothing>()
    }

    /** Fetch and parse one catalog page. */
    suspend fun feed(source: CatalogSource, url: String): Result<OpdsFeed> = withContext(Dispatchers.IO) {
        when (val response = get(source, url, ACCEPT_FEED)) {
            is Result.Success -> {
                val body = response.value.bytes.toString(Charsets.UTF_8)
                when (val decoded = CatalogDecoder.decode(body, response.value.url, response.value.contentType)) {
                    is CatalogDecoder.Result.Success -> Result.Success(decoded.feed)
                    is CatalogDecoder.Result.NotACatalog -> Result.NotACatalog(decoded.looksLikeHtml)
                }
            }
            is Result.Unauthorized -> Result.Unauthorized
            is Result.HttpError -> response
            is Result.Unreachable -> response
            is Result.NotACatalog -> response
            is Result.Unsupported -> Result.Unsupported
        }
    }

    /** The catalog's root page. */
    suspend fun root(source: CatalogSource): Result<OpdsFeed> = feed(source, source.rootUrl)

    /**
     * Run a search against a catalog.
     *
     * OPDS deliberately did not invent its own search: a feed points at an OpenSearch description
     * document, which names the URL template to fill in. Many servers skip that indirection and
     * give a template inline, so both are handled — and the description document is only fetched
     * when it is actually needed.
     */
    suspend fun search(source: CatalogSource, page: OpdsFeed, terms: String): Result<OpdsFeed> {
        val link = page.search ?: return Result.Unsupported
        val template = if (page.searchIsTemplate) {
            link.href
        } else {
            when (val description = get(source, link.href, ACCEPT_FEED)) {
                is Result.Success ->
                    OpenSearchDescription.template(description.value.bytes.toString(Charsets.UTF_8))
                        ?.let { OpdsUrl.resolve(description.value.url, it) }
                        ?: return Result.Unsupported
                is Result.Unauthorized -> return Result.Unauthorized
                is Result.HttpError -> return description
                is Result.Unreachable -> return description
                is Result.NotACatalog -> return description
                is Result.Unsupported -> return Result.Unsupported
            }
        }
        return feed(source, OpdsUrl.expandTemplate(template, terms))
    }

    /** A downloaded file: its bytes, and the media type the server actually served it as. */
    class Download(val bytes: ByteArray, val contentType: String?, val url: String)

    /**
     * Download a book. [maxBytes] is a guard, not a policy: a mistyped URL that returns a video
     * stream should fail rather than fill the device.
     */
    suspend fun download(
        source: CatalogSource,
        url: String,
        maxBytes: Long = MAX_DOWNLOAD_BYTES
    ): Result<Download> = withContext(Dispatchers.IO) {
        when (val response = get(source, url, ACCEPT_BOOK, maxBytes)) {
            is Result.Success -> Result.Success(
                Download(response.value.bytes, response.value.contentType, response.value.url)
            )
            is Result.Unauthorized -> Result.Unauthorized
            is Result.HttpError -> response
            is Result.Unreachable -> response
            is Result.NotACatalog -> response
            is Result.Unsupported -> Result.Unsupported
        }
    }

    /** Fetch a cover. Small, cacheable, and never worth failing a screen over. */
    suspend fun image(source: CatalogSource, url: String): ByteArray? = withContext(Dispatchers.IO) {
        (get(source, url, "image/*", MAX_IMAGE_BYTES) as? Result.Success)?.value?.bytes
    }

    // --- HTTP ---------------------------------------------------------------------------------

    private class Response(val bytes: ByteArray, val contentType: String?, val url: String)

    private fun get(
        source: CatalogSource,
        startUrl: String,
        accept: String,
        maxBytes: Long = MAX_DOWNLOAD_BYTES
    ): Result<Response> {
        if (!OpdsUrl.isAbsolute(startUrl)) return Result.Unreachable("Not a usable address")

        var url = startUrl
        var hops = 0
        while (true) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", accept)
                    // Hop manually so a cross-host redirect keeps its credentials decision explicit.
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 30_000
                }
                authHeader(source, url)?.let { conn.setRequestProperty("Authorization", it) }

                val code = conn.responseCode
                when {
                    code == 401 || code == 403 -> return Result.Unauthorized
                    code in 200..299 -> {
                        val bytes = conn.inputStream.use { read(it, maxBytes) }
                            ?: return Result.Unreachable("File too large")
                        return Result.Success(Response(bytes, conn.contentType, url))
                    }
                    code in 300..399 -> {
                        val location = conn.getHeaderField("Location")
                            ?: return Result.HttpError(code)
                        if (++hops > MAX_REDIRECTS) return Result.Unreachable("Too many redirects")
                        // Resolved rather than concatenated, so a relative Location works and a hop
                        // that would drop an https fetch onto plain http is kept on https instead.
                        url = OpdsUrl.resolve(url, location)
                    }
                    else -> return Result.HttpError(code)
                }
            } catch (e: Exception) {
                return Result.Unreachable(describe(e))
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Basic auth, sent **only to the catalog's own origin**.
     *
     * A redirect can leave the server it started on — an http→https upgrade stays put, but a link
     * out to a CDN or an identity provider does not. Sending the user's server password onward to
     * whatever host a redirect names would leak it, so credentials stop at the origin boundary.
     */
    private fun authHeader(source: CatalogSource, url: String): String? {
        val creds = credentials?.credentials(source.id) ?: return null
        if (OpdsUrl.origin(url) != OpdsUrl.origin(source.rootUrl)) return null
        val raw = "${creds.username}:${creds.password}"
        val encoded = android.util.Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        return "Basic $encoded"
    }

    /**
     * Say what actually went wrong, in words the catalog screen can show.
     *
     * Android refuses plain http by default, and the exception it throws for that ("Cleartext HTTP
     * traffic to … not permitted") reads as an app bug rather than as what it is: a server that only
     * offers an unencrypted connection. Redirects are kept on https by [OpdsUrl.resolve], so what
     * reaches here is a catalog whose own address is `http://` — most often a server on the LAN.
     */
    private fun describe(e: Exception): String {
        val message = e.message ?: e.javaClass.simpleName
        return if (message.contains("cleartext", ignoreCase = true)) {
            "that server only offers an unencrypted http connection, which Android blocks."
        } else {
            message
        }
    }

    /** Read at most [maxBytes]; `null` means the response ran past the cap. */
    private fun read(input: java.io.InputStream, maxBytes: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Android) Citation/1.0 (personal reading app; contact via app store listing)"

        private const val ACCEPT_FEED =
            "application/atom+xml;profile=opds-catalog, application/opds+json, application/atom+xml, application/xml, */*"

        private const val ACCEPT_BOOK = "application/epub+zip, application/pdf, */*"

        private const val MAX_REDIRECTS = 6
        private const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
    }
}

/**
 * Carry a result through a transformation of its value.
 *
 * Failures hold no value, so re-typing one is total and loses nothing — which is what lets the
 * repository turn a `Result<OpdsFeed>` into a `Result<CatalogPage>` without an unchecked cast or a
 * branch per failure at every call site.
 */
internal inline fun <A, B> OpdsClient.Result<A>.map(transform: (A) -> B): OpdsClient.Result<B> =
    when (this) {
        is OpdsClient.Result.Success -> OpdsClient.Result.Success(transform(value))
        is OpdsClient.Result.Unauthorized -> OpdsClient.Result.Unauthorized
        is OpdsClient.Result.NotACatalog -> this
        is OpdsClient.Result.Unreachable -> this
        is OpdsClient.Result.HttpError -> this
        is OpdsClient.Result.Unsupported -> OpdsClient.Result.Unsupported
    }
