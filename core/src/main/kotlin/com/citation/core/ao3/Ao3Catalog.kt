package com.citation.core.ao3

/**
 * A reference to one Archive of Our Own chapter within a work's catalog: enough to fetch it and to
 * place it in reading order, without its body. The AO3 analogue of
 * [com.citation.core.rr.RrChapterRef].
 *
 * The `chapterId` is AO3's own stable id (from the chapter URL `/works/{workId}/chapters/{id}`) — it
 * is the authoritative per-chapter identity that survives title edits and reorders, so the update
 * detector and the fuzzy-anchor layer both key off it rather than off ordinal or title.
 *
 * @property chapterId AO3's chapter id (from `/works/{workId}/chapters/{id}`).
 * @property ordinal 0-based reading order within the work.
 * @property title chapter title as listed (AO3's "N. Title" prefix already stripped).
 * @property url absolute or site-relative chapter URL.
 */
data class Ao3ChapterRef(
    val chapterId: Long,
    val ordinal: Int,
    val title: String,
    val url: String
)

/**
 * A work's known chapter list — the *skim/catalog* view, produced from the work page (never the
 * reader). The AO3 analogue of [com.citation.core.rr.FictionCatalog]: the spine the buffer,
 * backfill, and update-poll all reason against ("how many chapters exist", "which ordinal is which
 * chapter id").
 *
 * Unlike Royal Road, AO3 publishes no per-work syndication feed, so update-detection re-reads this
 * catalog and diffs it — see [Ao3Updates.detectNewChapters].
 *
 * @property workId AO3 work id — authoritative identity for the work (see
 *   [com.citation.core.identity.IdentityKey.Ao3Id]).
 * @property title work title.
 * @property author work author (AO3 always names one; `null` only when the page hid it).
 * @property chapters ordered chapter references (index == ordinal).
 */
data class Ao3Catalog(
    val workId: Long,
    val title: String,
    val author: String?,
    val chapters: List<Ao3ChapterRef>
) {
    /** The latest published chapter count — what a favourite's backfill aims to reach. */
    val expectedCount: Int get() = chapters.size

    /** The set of chapter ids already known to the catalog, for new-chapter detection. */
    val knownChapterIds: Set<Long> get() = chapters.map { it.chapterId }.toSet()

    fun ordinalOf(chapterId: Long): Int? = chapters.firstOrNull { it.chapterId == chapterId }?.ordinal
}
