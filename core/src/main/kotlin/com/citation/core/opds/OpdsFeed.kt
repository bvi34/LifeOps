package com.citation.core.opds

/**
 * A parsed OPDS catalog page.
 *
 * OPDS has exactly two shapes and one feed can mix them: **navigation** entries (folders — "Fiction",
 * "Recently Added") and **acquisition** entries (actual books). Rather than force a caller to guess,
 * a feed exposes both lists already separated, along with the paging, search and facet links a
 * browser needs. Every href here is already **absolute** — resolved against the feed's own URL at
 * parse time — so nothing downstream has to remember where the page came from.
 */
data class OpdsFeed(
    val title: String?,
    val id: String?,
    val links: List<OpdsLink>,
    val entries: List<OpdsEntry>,
    /** The URL this feed was fetched from, so relative links elsewhere can still be resolved. */
    val url: String
) {

    /** Entries that are books: they offer something to download, borrow or buy. */
    val publications: List<OpdsEntry> get() = entries.filter { it.isPublication }

    /** Entries that are folders: following them yields another feed. */
    val navigation: List<OpdsEntry> get() = entries.filter { !it.isPublication }

    fun link(kind: OpdsLinkKind): OpdsLink? = links.firstOrNull { it.kind == kind }

    val next: String? get() = link(OpdsLinkKind.NEXT)?.href
    val previous: String? get() = link(OpdsLinkKind.PREVIOUS)?.href
    val up: String? get() = link(OpdsLinkKind.UP)?.href
    val start: String? get() = link(OpdsLinkKind.START)?.href

    /**
     * Where to look for search. A catalog either points at an OpenSearch description document
     * (which must be fetched and parsed for its template) or, less correctly but very commonly,
     * gives a templated feed URL directly.
     */
    val search: OpdsLink? get() = link(OpdsLinkKind.SEARCH)

    /** Whether [search] is already a template we can fill in without another round trip. */
    val searchIsTemplate: Boolean
        get() = search?.href?.contains("{searchTerms}") == true

    /**
     * Facets grouped under their headings, in first-seen order. Facets are how a catalog offers
     * "sort by popularity" or "English only" without inventing a query language.
     */
    val facets: List<OpdsFacetGroup>
        get() = links
            .filter { it.facetGroup != null }
            .groupBy { it.facetGroup!! }
            .map { (name, links) -> OpdsFacetGroup(name, links) }

    val isEmpty: Boolean get() = entries.isEmpty()
}

/** One heading's worth of facets, e.g. "Sort by" over {Popularity, Recency, Title}. */
data class OpdsFacetGroup(val name: String, val facets: List<OpdsLink>) {
    val active: OpdsLink? get() = facets.firstOrNull { it.activeFacet }
}

/**
 * One entry in a catalog — a book, or a folder leading to more of them.
 *
 * The metadata mirrors [com.citation.core.model.BookMetadata] deliberately: an entry the user
 * chooses to download becomes a book, and everything the catalog already told us about it (series,
 * publisher, blurb, subjects) should survive that transition rather than being re-derived from the
 * file. A catalog usually knows *more* than the file does.
 */
data class OpdsEntry(
    val id: String?,
    val title: String,
    val authors: List<String> = emptyList(),
    val summary: String? = null,
    val published: String? = null,
    val updated: String? = null,
    val categories: List<String> = emptyList(),
    val language: String? = null,
    val publisher: String? = null,
    val identifiers: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Float? = null,
    val links: List<OpdsLink> = emptyList()
) {

    val author: String? get() = authors.takeIf { it.isNotEmpty() }?.joinToString(", ")

    /** Direct downloads, best format first — what a "Download" button should offer. */
    val downloads: List<OpdsLink>
        get() = links.filter { it.kind.isDownload }.sortedBy { it.format.ordinal }

    val borrowLinks: List<OpdsLink> get() = links.filter { it.kind == OpdsLinkKind.BORROW }
    val buyLinks: List<OpdsLink> get() = links.filter { it.kind == OpdsLinkKind.BUY }
    val sampleLinks: List<OpdsLink> get() = links.filter { it.kind == OpdsLinkKind.SAMPLE }

    /**
     * The one download to take when the user just says "get it": the first format Citation can
     * actually read, preferring EPUB (reflowable, annotatable) over PDF (fixed pages), and falling
     * back to whatever is offered so an unusual catalog still works.
     */
    val preferredDownload: OpdsLink?
        get() = downloads.firstOrNull { it.format == OpdsFormat.EPUB }
            ?: downloads.firstOrNull { it.format == OpdsFormat.PDF }
            ?: downloads.firstOrNull()

    val thumbnail: String?
        get() = links.firstOrNull { it.kind == OpdsLinkKind.THUMBNAIL }?.href
            ?: links.firstOrNull { it.kind == OpdsLinkKind.IMAGE }?.href

    val cover: String?
        get() = links.firstOrNull { it.kind == OpdsLinkKind.IMAGE }?.href
            ?: links.firstOrNull { it.kind == OpdsLinkKind.THUMBNAIL }?.href

    /** Where following this entry leads, when it is a folder rather than a book. */
    val navigationHref: String?
        get() = links.firstOrNull { it.kind == OpdsLinkKind.NAVIGATION }?.href

    /**
     * A book is anything you can obtain — download, borrow, buy or sample. An entry with only a
     * feed link is a folder. Deliberately not decided by the feed's declared `kind`, because plenty
     * of real catalogs mislabel that and only the links are reliable.
     */
    val isPublication: Boolean
        get() = links.any {
            it.kind.isDownload || it.kind == OpdsLinkKind.BORROW ||
                it.kind == OpdsLinkKind.BUY || it.kind == OpdsLinkKind.SAMPLE ||
                it.kind == OpdsLinkKind.SUBSCRIBE
        }

    /** The ISBN among [identifiers], if the catalog states one — the strongest dedup evidence. */
    val isbn: String?
        get() = identifiers
            .map { it.substringAfterLast(':').filter { c -> c.isLetterOrDigit() } }
            .firstOrNull { it.length == 10 || it.length == 13 }

    /** "The Expanse #1", or null. */
    val seriesLabel: String?
        get() = series?.let { name ->
            val index = seriesIndex ?: return@let name
            val trimmed = if (index == index.toInt().toFloat()) index.toInt().toString() else index.toString()
            "$name #$trimmed"
        }
}
