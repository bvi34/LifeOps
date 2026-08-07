package com.citation.core.ao3

/**
 * The Archive of Our Own **update detector**.
 *
 * Royal Road has a cheap per-fiction syndication feed that answers "did new chapters appear?"
 * without a page scrape (see [com.citation.core.rr.RoyalRoadFeed]). AO3 publishes no equivalent
 * per-work feed, so the only signal is the work's own chapter index — which the catalog scrape
 * already yields. This layer is therefore the diff, not a separate fetch: given a freshly-read
 * catalog and the chapter ids already known, it returns just the genuinely-new chapter references,
 * oldest-first (reading order), so the body puller fetches them in order.
 *
 * Keeping it a pure function (rather than folding it into the coordinator) matches how the RR
 * detector is isolated and unit-tested — the "did it change?" decision stays provable off fixtures.
 */
object Ao3Updates {

    /**
     * Given the current [catalog] and the chapter ids already known, return the chapter references
     * whose chapter is genuinely new, in ascending reading order (the catalog's own order). Returns
     * an empty list when nothing is new — the caller then scrapes nothing.
     */
    fun detectNewChapters(catalog: Ao3Catalog, knownChapterIds: Set<Long>): List<Ao3ChapterRef> =
        catalog.chapters
            .filter { it.chapterId !in knownChapterIds }
            .sortedBy { it.ordinal }
}
