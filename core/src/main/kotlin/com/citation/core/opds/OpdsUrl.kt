package com.citation.core.opds

import java.net.URI

/**
 * URL work for catalog browsing: resolving the relative hrefs feeds are full of, and filling in
 * OpenSearch templates.
 *
 * Catalogs state links every legal way at once — absolute, protocol-relative, root-relative, and
 * plain relative, sometimes within a single feed — so resolution happens once at parse time and
 * everything downstream sees absolute URLs. It degrades rather than throws: a href that cannot be
 * parsed comes back unchanged, and the caller fails at fetch time with a real error instead of the
 * parser losing the whole feed over one bad link.
 */
object OpdsUrl {

    /** Resolve [href] against the [base] URL it was stated in. */
    fun resolve(base: String, href: String): String {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) return base
        if (trimmed.startsWith("data:")) return trimmed
        val resolved = try {
            // Spaces and other stray characters are common in real hrefs and illegal in a URI.
            URI(base).resolve(URI(encodeIllegal(trimmed))).toString()
        } catch (_: Exception) {
            fallback(base, trimmed)
        }
        return keepSecure(base, resolved)
    }

    /**
     * Never step down from https to http part-way through browsing a catalog.
     *
     * Real feeds are full of `http://` links they no longer mean: a server reached over https states
     * its own entries with the scheme it was configured with years ago, and a redirect chain hops to
     * a plain-http host that has served https for just as long. Android refuses cleartext by default,
     * so following either one verbatim fails the whole page with "Cleartext HTTP traffic to … not
     * permitted" — a platform error about a link the user never saw, on a catalog that works.
     *
     * A link stated inside an https page is therefore fetched over https. It costs nothing when the
     * host speaks both (nearly all do), and where it does not, the failure is an ordinary
     * unreachable-server one instead of a policy violation. A catalog the user deliberately added as
     * `http://` — a Calibre server on the LAN — starts on http and stays there; this only refuses to
     * *downgrade*.
     */
    fun keepSecure(base: String, url: String): String =
        if (base.startsWith("https://", true) && url.startsWith("http://", true)) {
            "https://" + url.substring("http://".length)
        } else {
            url
        }

    /** Percent-encode the characters that make an otherwise-fine href unparseable. */
    private fun encodeIllegal(href: String): String =
        href.replace(" ", "%20").replace("|", "%7C").replace("\"", "%22")
            .replace("<", "%3C").replace(">", "%3E").replace("\\", "%5C")

    private fun fallback(base: String, href: String): String = when {
        href.startsWith("http://", true) || href.startsWith("https://", true) -> href
        href.startsWith("//") -> base.substringBefore("//").ifEmpty { "https:" } + href
        href.startsWith("/") -> origin(base) + href
        else -> base.substringBeforeLast('/', base).trimEnd('/') + "/" + href
    }

    /** Scheme + authority of [url], e.g. `https://standardebooks.org`. */
    fun origin(url: String): String = try {
        val uri = URI(url)
        if (uri.scheme != null && uri.authority != null) "${uri.scheme}://${uri.authority}"
        else url.substringBefore("/", url)
    } catch (_: Exception) {
        url.split("/").take(3).joinToString("/")
    }

    /** Whether [url] is usable as a fetch target at all. */
    fun isAbsolute(url: String): Boolean =
        url.startsWith("http://", true) || url.startsWith("https://", true)

    /**
     * Fill an OpenSearch template with [terms].
     *
     * Templates carry required parameters in braces (`{searchTerms}`) and optional ones marked with
     * a trailing `?` (`{startIndex?}`). The optional ones are dropped — a server must accept their
     * absence — and any remaining unknown required parameter is emptied rather than left as a
     * literal brace, which would 404.
     */
    fun expandTemplate(template: String, terms: String): String {
        val encoded = urlEncode(terms)
        var out = template
        out = Regex("\\{(searchTerms|atom:searchTerms|opds:searchTerms)\\??}").replace(out, encoded)
        out = Regex("\\{[^}]*\\?}").replace(out, "")
        out = Regex("\\{[^}]*}").replace(out, "")
        return tidyQuery(out)
    }

    /** Remove the empty parameters template expansion leaves behind. */
    private fun tidyQuery(url: String): String {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return url
        val kept = query.split('&')
            .filter { it.isNotBlank() && !it.endsWith("=") }
            .joinToString("&")
        val head = url.substringBefore('?')
        return if (kept.isEmpty()) head else "$head?$kept"
    }

    fun urlEncode(value: String): String =
        try {
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            value.replace(" ", "%20")
        }
}

/**
 * The OpenSearch description document a catalog points at when it supports search.
 *
 * It exists because OPDS deliberately did not invent its own search: a feed links to one of these,
 * and the document says which URL template to fill in. Only the Atom-typed template is of interest —
 * the HTML one leads to a web page, not a feed.
 */
object OpenSearchDescription {

    /** The best Atom search template in [xml], or `null` if the document offers none. */
    fun template(xml: String): String? {
        val urls = Regex("(?is)<Url\\b[^>]*>").findAll(xml).map { it.value }.toList()
        fun attr(tag: String, name: String) =
            Regex("(?i)\\b${Regex.escape(name)}\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1)

        val templates = urls.mapNotNull { tag ->
            val template = attr(tag, "template") ?: return@mapNotNull null
            val type = attr(tag, "type").orEmpty().lowercase()
            type to template
        }
        return templates.firstOrNull { it.first.contains("opds-catalog") }?.second
            ?: templates.firstOrNull { it.first.contains("atom") }?.second
            ?: templates.firstOrNull()?.second
    }
}
