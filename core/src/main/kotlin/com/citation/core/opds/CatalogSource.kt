package com.citation.core.opds

/**
 * A catalog Citation knows how to browse: a saved OPDS root, with whatever credentials it needs.
 *
 * This is the piece that turns Citation from "an app you put files into" into "an app connected to
 * libraries". One protocol reaches an unusually large share of the readable world — a self-hosted
 * Calibre content server or Calibre-Web instance, Standard Ebooks, Project Gutenberg, Feedbooks,
 * Kavita and Komga for comics and manga, and most library lending platforms — so the work is in
 * speaking OPDS properly rather than in writing an integration per source.
 *
 * Credentials are HTTP Basic only, because that is what Calibre's content server and Calibre-Web
 * actually use. They are stored by the Android layer in the same encrypted store as the O'Reilly
 * library card, and never ride the sync seam.
 */
data class CatalogSource(
    val id: String,
    val name: String,
    /** The catalog root. Normalised through [normalizeRoot] before use. */
    val url: String,
    val username: String? = null,
    val password: String? = null,
    /** Ordering on the catalogs screen; lower sorts first. */
    val position: Int = 0
) {
    val requiresAuth: Boolean get() = !username.isNullOrBlank()

    /** The URL actually fetched for this catalog's root. */
    val rootUrl: String get() = normalizeRoot(url)

    companion object {

        /**
         * Fix up the URL a person is likely to type.
         *
         * Nobody types `http://nas.local:8080/opds`; they type `nas.local:8080` — the address they
         * see in a browser. A catalog that cannot be reached from what the user actually typed
         * feels broken, so the guesses are made here rather than being the user's problem: assume
         * HTTPS when no scheme is given, and append Calibre's well-known `/opds` path when the URL
         * is a bare host that looks like a Calibre server.
         */
        fun normalizeRoot(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return url
            if (!OpdsUrl.isAbsolute(url)) {
                // A bare host on a LAN port is almost never HTTPS; anything else almost always is.
                val looksLocal = Regex("^([\\w.-]+):(\\d+)").containsMatchIn(url) ||
                    url.startsWith("localhost") || url.startsWith("192.168.") || url.startsWith("10.")
                url = (if (looksLocal) "http://" else "https://") + url.removePrefix("//")
            }
            url = url.trimEnd('/')
            // A Calibre content server's root is an HTML page; its catalog lives at /opds.
            val path = url.substringAfter("://").substringAfter('/', "")
            if (path.isEmpty()) return "$url/opds"
            return url
        }

        /**
         * Catalogs worth offering out of the box: all free, all public, none requiring an account.
         * They are seeds, not a fixed list — each is editable and removable, and the point of the
         * screen is adding your own server.
         */
        fun presets(): List<CatalogSource> = listOf(
            CatalogSource(
                id = "standard-ebooks",
                name = "Standard Ebooks",
                url = "https://standardebooks.org/feeds/opds",
                position = 0
            ),
            CatalogSource(
                id = "gutenberg",
                name = "Project Gutenberg",
                url = "https://m.gutenberg.org/ebooks.opds/",
                position = 1
            ),
            // Feedbooks retired its public-domain sections (the old
            // `catalog/public_domain.atom` now 404s) when it became Cantook Market. The
            // catalog root still speaks OPDS and still answers searches, so the seed points
            // there: an empty shelf with a working search box rather than a dead address.
            CatalogSource(
                id = "feedbooks-public",
                name = "Feedbooks",
                url = "https://catalog.feedbooks.com/catalog/index.atom",
                position = 2
            )
        )
    }
}

/**
 * Where a catalog fetch ended up, so the browser can keep a back stack and a title bar without
 * re-deriving either.
 */
data class CatalogPage(
    val source: CatalogSource,
    val url: String,
    val feed: OpdsFeed,
    /** The search terms that produced this page, when it is a search result. */
    val query: String? = null
) {
    val title: String get() = query?.let { "Results for “$it”" } ?: feed.title ?: source.name
}

/**
 * Decoding whatever a catalog server actually sent back.
 *
 * A server may answer with Atom, with OPDS 2.0 JSON, or — when a URL was mistyped or a login page
 * intervened — with HTML. Content types are unreliable enough that the body is sniffed as well, so
 * a correct feed served under the wrong type still parses, and an HTML error page is reported as
 * "not a catalog" instead of silently parsing to an empty shelf.
 */
object CatalogDecoder {

    sealed class Result {
        data class Success(val feed: OpdsFeed) : Result()

        /** The response parsed as neither Atom nor OPDS 2.0. [looksLikeHtml] usually means a wrong URL. */
        data class NotACatalog(val looksLikeHtml: Boolean) : Result()
    }

    fun decode(body: String, url: String, contentType: String? = null): Result {
        if (Opds2Parser.looksLikeJson(body, contentType)) {
            Opds2Parser.parse(body, url)?.let { return Result.Success(it) }
        }
        if (OpdsParser.looksLikeAtom(body)) {
            OpdsParser.parse(body, url)?.let { return Result.Success(it) }
        }
        // Last chance: a server that mislabels JSON as text, or Atom without a recognisable head.
        Opds2Parser.parse(body, url)?.let { return Result.Success(it) }
        OpdsParser.parse(body, url)?.let { return Result.Success(it) }

        val head = body.trimStart().take(200).lowercase()
        return Result.NotACatalog(looksLikeHtml = head.startsWith("<!doctype html") || head.startsWith("<html"))
    }
}
