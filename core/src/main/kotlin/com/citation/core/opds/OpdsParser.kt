package com.citation.core.opds

import com.citation.core.xml.Xml

/**
 * Parses an OPDS catalog page — the Atom form (OPDS 1.x), which is what nearly every real server
 * speaks: Calibre's content server, Calibre-Web, COPS, Standard Ebooks, Project Gutenberg,
 * Feedbooks, Kavita, Komga, and every library lending platform.
 *
 * Written in the same spirit as the EPUB producer: a small nesting-aware scan rather than an XML
 * library, and lenient throughout. Feeds in the wild are inconsistent about namespaces, about
 * whether Dublin Core is `dc:` or `dcterms:`, about `<content>` versus `<summary>`, and about
 * mislabelling navigation feeds as acquisition ones. Each of those is absorbed here rather than
 * left for the UI, and an entry that cannot be understood is skipped rather than failing the page.
 *
 * Every href is resolved against the feed's own URL as it is parsed, so callers only ever see
 * absolute links.
 */
object OpdsParser {

    /** Whether [body] looks like an Atom feed at all, before trying to parse it. */
    fun looksLikeAtom(body: String): Boolean {
        val head = body.take(2000)
        return head.contains("<feed", true) || head.contains("<entry", true)
    }

    /**
     * Parse [xml] fetched from [url]. Returns `null` only when there is no feed element at all —
     * a feed with zero entries is a legitimate empty shelf, not a failure.
     */
    fun parse(xml: String, url: String): OpdsFeed? {
        val feed = Xml.element(xml, "feed", anyPrefix = true)
            ?: return singleEntryFeed(xml, url)
        val inner = feed.inner

        return OpdsFeed(
            title = Xml.text(inner, "title", anyPrefix = true),
            id = Xml.text(inner, "id", anyPrefix = true),
            links = Xml.elements(inner, "link", anyPrefix = true).map { link(it, url) },
            entries = Xml.elements(inner, "entry", anyPrefix = true).mapNotNull { entry(it.inner, url) },
            url = url
        )
    }

    /** Some servers answer a single-book request with a bare `<entry>` rather than a feed. */
    private fun singleEntryFeed(xml: String, url: String): OpdsFeed? {
        val entry = Xml.element(xml, "entry", anyPrefix = true) ?: return null
        val parsed = entry(entry.inner, url) ?: return null
        return OpdsFeed(title = parsed.title, id = parsed.id, links = emptyList(), entries = listOf(parsed), url = url)
    }

    private fun link(element: Xml.Element, base: String): OpdsLink {
        val tag = element.open
        return OpdsLink(
            href = Xml.attr(tag, "href")?.let { OpdsUrl.resolve(base, it) }.orEmpty(),
            rel = Xml.attr(tag, "rel"),
            type = Xml.attr(tag, "type"),
            title = Xml.attr(tag, "title"),
            facetGroup = Xml.attr(tag, "facetGroup"),
            activeFacet = Xml.attr(tag, "activeFacet")?.equals("true", true) == true,
            count = Xml.attr(tag, "count")?.toIntOrNull()
        )
    }

    private fun entry(inner: String, base: String): OpdsEntry? {
        val title = Xml.text(inner, "title", anyPrefix = true) ?: return null

        // `<author><name>` is the Atom form; a bare `<author>` or a Dublin Core creator both occur.
        val authors = Xml.elements(inner, "author", anyPrefix = true)
            .mapNotNull { it.let { a -> Xml.text(a.inner, "name", anyPrefix = true) ?: a.text.takeIf { t -> t.isNotBlank() } } }
            .ifEmpty { Xml.texts(inner, "dc:creator") }
            .ifEmpty { Xml.texts(inner, "creator", anyPrefix = true) }

        return OpdsEntry(
            id = Xml.text(inner, "id", anyPrefix = true),
            title = title,
            authors = authors.distinct(),
            // `<content>` is richer than `<summary>` when both are present.
            summary = Xml.text(inner, "content", anyPrefix = true) ?: Xml.text(inner, "summary", anyPrefix = true),
            published = Xml.text(inner, "published", anyPrefix = true)
                ?: Xml.text(inner, "dc:issued")
                ?: Xml.text(inner, "issued", anyPrefix = true),
            updated = Xml.text(inner, "updated", anyPrefix = true),
            categories = categories(inner),
            language = Xml.text(inner, "dc:language") ?: Xml.text(inner, "language", anyPrefix = true),
            publisher = Xml.text(inner, "dc:publisher") ?: Xml.text(inner, "publisher", anyPrefix = true),
            identifiers = (Xml.texts(inner, "dc:identifier") + Xml.texts(inner, "identifier", anyPrefix = true)).distinct(),
            series = series(inner),
            seriesIndex = seriesIndex(inner),
            links = Xml.elements(inner, "link", anyPrefix = true).map { link(it, base) }.filter { it.href.isNotBlank() }
        )
    }

    /** Category labels, preferring the human `label` over the machine `term`. */
    private fun categories(inner: String): List<String> =
        Xml.elements(inner, "category", anyPrefix = true)
            .mapNotNull { Xml.attr(it.open, "label") ?: Xml.attr(it.open, "term") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    /**
     * Series, stated by whichever convention the server follows: calibre's `<series>` element, the
     * Schema.org-flavoured `belongs-to-collection` some servers emit, or a `<category>` scheme
     * naming a series.
     */
    private fun series(inner: String): String? =
        Xml.text(inner, "series", anyPrefix = true)
            ?: Xml.element(inner, "belongs_to_collection", anyPrefix = true)?.text?.takeIf { it.isNotBlank() }
            ?: Xml.elements(inner, "link", anyPrefix = true)
                .firstOrNull { Xml.attr(it.open, "rel")?.contains("series", true) == true }
                ?.let { Xml.attr(it.open, "title") }

    private fun seriesIndex(inner: String): Float? =
        (Xml.text(inner, "series_index", anyPrefix = true)
            ?: Xml.elements(inner, "series", anyPrefix = true).firstOrNull()?.let { Xml.attr(it.open, "position") }
            ?: Xml.text(inner, "group_position", anyPrefix = true))
            ?.trim()?.toFloatOrNull()
}
