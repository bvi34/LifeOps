package com.citation.core.rr

/**
 * A reference to one Royal Road chapter within a fiction's catalog: enough to fetch it and to place
 * it in reading order, without its body.
 *
 * The `chapterId` is Royal Road's own stable id (from the chapter URL) — it is the authoritative
 * per-chapter identity that survives title edits and reorders, so the update detector and the
 * fuzzy-anchor layer both key off it rather than off ordinal or title.
 *
 * @property chapterId Royal Road's chapter id (from `/chapter/{id}/…`).
 * @property ordinal 0-based reading order within the fiction.
 * @property title chapter title as listed.
 * @property url absolute or site-relative chapter URL.
 */
data class RrChapterRef(
    val chapterId: Long,
    val ordinal: Int,
    val title: String,
    val url: String
)

/**
 * A fiction's known chapter list — the *skim/catalog* view, produced from the fiction page (never
 * the reader). It is the spine the buffer, backfill, and update-poll all reason against: "how many
 * chapters exist" and "which ordinal is which chapter id".
 *
 * @property fictionId Royal Road fiction id — authoritative identity for the serial (see
 *   [com.citation.core.identity.IdentityKey.RoyalRoadId]).
 * @property title fiction title.
 * @property chapters ordered chapter references (index == ordinal).
 */
data class FictionCatalog(
    val fictionId: Long,
    val title: String,
    val chapters: List<RrChapterRef>
) {
    /** The latest published chapter count — what a favourite's backfill aims to reach. */
    val expectedCount: Int get() = chapters.size

    /** The set of chapter ids already known to the catalog, for new-chapter detection. */
    val knownChapterIds: Set<Long> get() = chapters.map { it.chapterId }.toSet()

    fun ordinalOf(chapterId: Long): Int? = chapters.firstOrNull { it.chapterId == chapterId }?.ordinal
}
