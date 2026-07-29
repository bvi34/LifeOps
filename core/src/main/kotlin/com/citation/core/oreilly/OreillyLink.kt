package com.citation.core.oreilly

/**
 * Deep-linking for **O'Reilly read-in-place**. O'Reilly's own reader is the real reader (the content
 * is DRM'd and licensed, so Citation keeps *no* local copy); Citation's job is to get you back to
 * your spot in one tap instead of at login+search.
 *
 * This builds and parses the URL that points at a specific book and, when known, a specific position
 * inside it. Re-auth is expected and left to the WebView — the design says make returning cheap,
 * don't try to defeat the short-lived federated tokens — so this only concerns the *destination*,
 * which survives a re-login.
 */
object OreillyLink {

    private const val HOST = "https://learning.oreilly.com"

    /** Where a browse session lands: O'Reilly's own search/discovery home. */
    private const val BROWSE_PATH = "/search/"

    /** A resolved O'Reilly destination: which book, (optionally) where in it, and its URL slug. */
    data class Destination(val bookId: String, val location: String?, val slug: String? = null)

    /**
     * The URL to open a **browse** session at — O'Reilly's own catalog, routed through your library's
     * EZproxy when one is configured (so the whole skim runs on a library card, exactly like the
     * reader). With no proxy it's the direct O'Reilly search home.
     */
    fun browseUrl(proxy: OreillyLibraryProxy? = null): String {
        val direct = HOST + BROWSE_PATH
        return proxy?.rewrite(direct) ?: direct
    }

    /**
     * Build a deep link to [bookId] (an O'Reilly urn/ISBN identifier), optionally at [location] —
     * the reader's own position token (an epubcfi or fragment). With no location it lands on the
     * book's cover/last-server-position; with one it lands on the saved spot.
     *
     * When a [proxy] is given, the link is routed through your library's EZproxy so you reach the
     * content on a library card rather than a personal O'Reilly account. The destination path is
     * identical either way — only the host changes — so a saved position survives switching the proxy
     * on or off.
     */
    fun deepLink(bookId: String, location: String? = null, proxy: OreillyLibraryProxy? = null): String {
        val base = "$HOST/library/view/-/$bookId/"
        val direct = if (location.isNullOrBlank()) base else base + fragment(location)
        return proxy?.rewrite(direct) ?: direct
    }

    /**
     * Parse an O'Reilly URL back into a [Destination]. Recognises the `/library/view/-/{bookId}/`
     * shape and pulls any `#`-fragment as the location. Returns `null` if it isn't an O'Reilly
     * library URL.
     */
    fun parse(url: String): Destination? {
        val marker = "/library/view/"
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        val afterView = url.substring(idx + marker.length)
        // Shape: {slug-or-dash}/{bookId}/{...}#{fragment}
        val hashSplit = afterView.split('#', limit = 2)
        val path = hashSplit[0]
        val fragment = hashSplit.getOrNull(1)?.removePrefix("!")?.takeIf { it.isNotBlank() }
        val segments = path.split('/').filter { it.isNotBlank() }
        val bookId = segments.getOrNull(1) ?: return null
        // Segment 0 is the URL slug on a real catalog link (e.g. `designing-data-intensive`); our own
        // deep links use a bare `-` placeholder there, which isn't a slug.
        val slug = segments.getOrNull(0)?.takeUnless { it == "-" }
        return Destination(bookId = bookId, location = fragment, slug = slug)
    }

    /**
     * A human title guessed from a catalog URL [slug] (`the-pragmatic-programmer` →
     * `The Pragmatic Programmer`), or null when there's no usable slug. Used to give a browsed O'Reilly
     * book a readable library name without an API call — the reader's page can refine it later.
     */
    fun titleFromSlug(slug: String?): String? {
        val words = slug?.replace('_', '-')?.split('-')?.filter { it.isNotBlank() } ?: return null
        if (words.isEmpty()) return null
        return words.joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
    }

    private fun fragment(location: String): String = "#!" + location.removePrefix("!")
}
