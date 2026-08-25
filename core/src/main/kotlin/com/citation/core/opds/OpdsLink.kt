package com.citation.core.opds

/**
 * One link in an OPDS catalog, and — the part that matters — **what kind of thing it is**.
 *
 * OPDS says everything through links. Whether an entry is a book or a folder, whether you can
 * download it or only borrow it, where its cover lives, how to page forward, how to search: all of
 * it is a `rel` and a `type` on a link. So classification is the whole job, and it is done once,
 * here, as pure data — the UI never pattern-matches on rel strings.
 */
data class OpdsLink(
    val href: String,
    val rel: String? = null,
    val type: String? = null,
    val title: String? = null,
    /** OPDS facets group related filters ("Sort by", "Language") under one heading. */
    val facetGroup: String? = null,
    /** Whether this facet is the one currently applied. */
    val activeFacet: Boolean = false,
    /** `thr:count` — how many entries a facet or subsection holds, when the server says. */
    val count: Int? = null
) {

    val kind: OpdsLinkKind get() = OpdsLinkKind.of(rel, type)

    /** The file format behind an acquisition link, inferred from its media type then its href. */
    val format: OpdsFormat get() = OpdsFormat.of(type, href)

    /** A human label for a download button: "EPUB", "PDF", or the raw subtype. */
    val formatLabel: String
        get() = when (format) {
            OpdsFormat.EPUB -> "EPUB"
            OpdsFormat.PDF -> "PDF"
            OpdsFormat.UNKNOWN -> type?.substringAfterLast('/')?.substringBefore(';')?.uppercase()
                ?.takeIf { it.isNotBlank() && it.length <= 12 }
                ?: "FILE"
        }
}

/** What a link is *for*. */
enum class OpdsLinkKind {
    /** A direct download of the work. */
    ACQUISITION,

    /** A direct download that is explicitly free — public domain, CC, the publisher's gift. */
    OPEN_ACCESS,

    /** A library loan: reachable, but through a lending flow Citation does not drive. */
    BORROW,

    /** A purchase page. */
    BUY,

    /** An excerpt. */
    SAMPLE,

    /** A subscription-gated acquisition. */
    SUBSCRIBE,

    /** The work's cover art. */
    IMAGE,

    /** A small cover, cheap enough to load a whole shelf of. */
    THUMBNAIL,

    /** Another feed: a subsection, a related list, the catalog root. */
    NAVIGATION,

    /** An OpenSearch description, or a search feed template. */
    SEARCH,

    /** Paging and position within a paged feed. */
    NEXT, PREVIOUS, FIRST, LAST, SELF, UP, START,

    /** Anything else — kept rather than dropped, so an unusual catalog still shows its links. */
    OTHER;

    /** Whether following this link yields a file to store rather than a page to show. */
    val isDownload: Boolean get() = this == ACQUISITION || this == OPEN_ACCESS

    companion object {
        private const val SPEC = "http://opds-spec.org/"

        fun of(rel: String?, type: String?): OpdsLinkKind {
            val r = rel?.trim()?.lowercase().orEmpty()
            val t = type?.trim()?.lowercase().orEmpty()
            return when {
                r == SPEC + "acquisition/open-access" -> OPEN_ACCESS
                r == SPEC + "acquisition/borrow" -> BORROW
                r == SPEC + "acquisition/buy" -> BUY
                r == SPEC + "acquisition/sample" || r == SPEC + "acquisition/preview" -> SAMPLE
                r == SPEC + "acquisition/subscribe" -> SUBSCRIBE
                r == SPEC + "acquisition" || r == "http://opds-spec.org/acquisition/" -> ACQUISITION
                r == SPEC + "image" || r == "x-stanza-cover-image" || r == "cover" -> IMAGE
                r == SPEC + "image/thumbnail" || r == "x-stanza-cover-image-thumbnail" ||
                    r == "http://opds-spec.org/thumbnail" -> THUMBNAIL
                r == "search" -> SEARCH
                r == "next" -> NEXT
                r == "previous" || r == "prev" -> PREVIOUS
                r == "first" -> FIRST
                r == "last" -> LAST
                r == "self" -> SELF
                r == "up" -> UP
                r == "start" -> START
                r == "subsection" || r == "http://opds-spec.org/sort/new" ||
                    r == "http://opds-spec.org/sort/popular" || r == "http://opds-spec.org/featured" ||
                    r == "http://opds-spec.org/recommended" || r == "http://opds-spec.org/crawlable" -> NAVIGATION
                // An untyped `alternate`/`related` pointing at a catalog feed is navigation.
                t.contains("opds-catalog") -> NAVIGATION
                t.startsWith("image/") -> IMAGE
                // A bare acquisition-ish rel we don't know, but a readable type: treat as a download.
                r.startsWith(SPEC + "acquisition") -> ACQUISITION
                else -> OTHER
            }
        }
    }
}

/** The formats Citation's own readers can open, plus everything else. */
enum class OpdsFormat {
    EPUB, PDF, UNKNOWN;

    /** Whether Citation can read this itself, rather than only storing or handing it off. */
    val isReadable: Boolean get() = this != UNKNOWN

    companion object {
        fun of(type: String?, href: String = ""): OpdsFormat {
            val t = type?.lowercase().orEmpty()
            val h = href.lowercase().substringBefore('?')
            return when {
                t.contains("epub") || h.endsWith(".epub") -> EPUB
                t.contains("pdf") || h.endsWith(".pdf") -> PDF
                else -> UNKNOWN
            }
        }
    }
}
