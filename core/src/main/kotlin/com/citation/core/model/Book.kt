package com.citation.core.model

import com.citation.core.key.EntityKey

/**
 * The **normalized internal representation** — the single model everything in Citation hangs off.
 *
 * Every ingestion source (EPUB, PDF, Royal Road, O'Reilly, …) produces a [Book]; the reader only
 * ever consumes one. Formats differ wildly on the way in, but by the time content reaches the
 * reader it is always the same shape: an ordered list of [Chapter]s of flowing text, plus
 * [BookMetadata]. This is what makes the reader *format-blind* — it renders a `Book`, never an
 * EPUB or an HTML page.
 *
 * A `Book` is a value object: parsing produces it, the reader displays it, notes anchor into it.
 * It carries no storage or lifecycle concern — those live on the record that wraps it
 * (see the sync package's book lifecycle state machines).
 *
 * @property key the entity's provenance-baked key (e.g. `ER-Book-3`); `null` before minting.
 * @property metadata title / author / identity — enough to recognise and dedup the work.
 * @property chapters reading order. Index in this list *is* the chapter ordinal.
 */
data class Book(
    val key: EntityKey?,
    val metadata: BookMetadata,
    val chapters: List<Chapter>
) {
    /** Total character length across all chapters — the cheap "how big is the text" measure. */
    val characterCount: Int get() = chapters.sumOf { it.text.length }

    /** Look a chapter up by its ordinal (0-based), or `null` if out of range. */
    fun chapterAt(ordinal: Int): Chapter? = chapters.getOrNull(ordinal)
}

/**
 * One chapter of a [Book]: a run of flowing text with a title and a stable within-book identity.
 *
 * The reader reflows [text] to the viewport; it never depends on source layout. [html] is retained
 * (nullable) for renderers that want light structure (headings, emphasis), but `text` is the
 * canonical content that notes anchor against and that snapshots are frozen from — so an anchor
 * resolves identically whether or not a renderer used the HTML.
 *
 * @property ordinal 0-based position in reading order; matches the chapter's index in [Book.chapters].
 * @property sourceRef the source's own handle for this chapter (EPUB spine href, RR chapter URL,
 *   PDF page range). Opaque to the reader; used by ingestion for refetch/reconcile.
 * @property text plain flowing text — the canonical anchoring surface.
 * @property html optional lightly-structured HTML for richer rendering; must reduce to [text].
 */
data class Chapter(
    val ordinal: Int,
    val title: String,
    val sourceRef: String,
    val text: String,
    val html: String? = null
) {
    /** Byte cost of this chapter's text (UTF-8) — feeds the integrity manifest's size accounting. */
    val byteSize: Long get() = text.toByteArray(Charsets.UTF_8).size.toLong()
}

/**
 * Recognition + identity metadata for a work. Kept separate from the content so two records of the
 * "same book" can be compared without loading chapters.
 *
 * @property title human title (may be fuzzy for a center-authored, not-yet-resolved book).
 * @property author human author (likewise may be fuzzy).
 * @property source where this representation was produced from.
 * @property language BCP-47 tag when known.
 */
data class BookMetadata(
    val title: String,
    val author: String?,
    val source: SourceType,
    val language: String? = null
)

/**
 * The kind of place a [Book] came from. Drives ingestion strategy, storage ownership (borrowed vs
 * owned), and how legible a note's frozen context is once detached from its source.
 *
 * - [EPUB] / [PDF] — **owned** files you hold; jump-to-context is reliable.
 * - [ROYAL_ROAD] — **borrowed** web serial; cacheable but evictable, authors edit chapters.
 * - [AO3] — Archive of Our Own; **borrowed** web serial, like [ROYAL_ROAD] (cacheable, evictable,
 *   authors edit works). Distinguished from RR only by how the producer reads it: AO3 has no
 *   per-work syndication feed, so update-detection re-reads the work's chapter index instead.
 * - [OREILLY] — **licensed**, read-in-place; no local content cache, only your annotations.
 * - [KINDLE] — **licensed**, read-in-place on `read.amazon.com`; like [OREILLY], no local content
 *   cache. The reader also suppresses text selection, so a Kindle note cites the reader's *location*
 *   ("Location 156 of 3866") rather than the passage words.
 * - [INTERNAL] — a LifeOps center-authored placeholder (a "wanted" book) not yet bound to an
 *   artifact.
 * - [CAPTURE] — a fragment handed to Citation from *another app* (a browser selection, shared text,
 *   a typed quick-note). Its provenance is a URL / filename / app package / timestamp rather than one
 *   of the reader's own tracks, so jump-back is best-effort and the note may start life *provisional*
 *   (no bound book) until a hard identity later promotes it. See [com.citation.core.capture].
 */
enum class SourceType {
    EPUB,
    PDF,
    ROYAL_ROAD,
    AO3,
    OREILLY,
    KINDLE,
    INTERNAL,
    CAPTURE;

    /**
     * Whether content from this source is *borrowed* (safe to auto-evict; refetchable but not
     * guaranteed) versus *owned/licensed* content that eviction must never structurally reach.
     * Royal Road and Archive of Our Own chapters are the borrowed, cacheable bodies; O'Reilly is
     * licensed but keeps no local body at all, so nothing there is evictable.
     */
    val isBorrowedCache: Boolean get() = this == ROYAL_ROAD || this == AO3
}
