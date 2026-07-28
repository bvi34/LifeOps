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

    /** A resolved O'Reilly destination: which book, and (optionally) where in it. */
    data class Destination(val bookId: String, val location: String?)

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
        return Destination(bookId = bookId, location = fragment)
    }

    private fun fragment(location: String): String = "#!" + location.removePrefix("!")
}
