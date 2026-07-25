package com.citation.core.note

import com.citation.core.anchor.FuzzyAnchor
import com.citation.core.anchor.TextAnchor
import com.citation.core.model.SourceType

/**
 * Resolves a note's frozen references against the *current* state of their source, producing the
 * degradation state the UI shows and the jump target it uses.
 *
 * This is where "a note **outlives its source**" becomes concrete. A note always carries its own
 * frozen snapshot, so it is never blank — but whether you can *jump back to live context* depends on
 * what's still there:
 *  - the source is present and the passage still matches → [State.RESOLVED] (jump reliably);
 *  - present but the passage was edited → [State.FUZZY] (probable jump, flagged);
 *  - present but the passage is gone → [State.ORPHANED] (intact note, no jump);
 *  - the source itself is unavailable (evicted borrowed cache, unresolved book) →
 *    [State.SOURCE_UNAVAILABLE] (read the snapshot, can't jump).
 *
 * Only the last two are "degraded", and both keep the note fully readable — degraded, not lost.
 */
object NoteResolver {

    /** The resolution state of one reference against its current source. */
    enum class State {
        RESOLVED,
        FUZZY,
        ORPHANED,
        SOURCE_UNAVAILABLE;

        /** True when the reader can offer a jump (exact or probable). */
        val canJump: Boolean get() = this == RESOLVED || this == FUZZY

        /** True when the passage's frozen snapshot is all that remains (still fully readable). */
        val isDegraded: Boolean get() = this == ORPHANED || this == SOURCE_UNAVAILABLE
    }

    /**
     * How trustworthy a *successful* jump is for a given source kind. Owned/internal content is
     * stable, so an exact match is reliable; borrowed/licensed content (Royal Road, O'Reilly) can
     * shift under us, so even a match is best-effort.
     */
    enum class Reliability { RELIABLE, BEST_EFFORT }

    /**
     * The outcome of resolving one [PassageReference].
     * @property chapterOrdinal set for flowing anchors (the chapter to open), else `null`.
     * @property page set for PDF anchors (the page to open), else `null`.
     * @property range the resolved character span within the source text, or `null` if not jumpable.
     * @property frozenSnapshot always present — the note's self-sufficient context.
     */
    data class RefResolution(
        val state: State,
        val chapterOrdinal: Int?,
        val page: Int?,
        val range: IntRange?,
        val score: Double,
        val frozenSnapshot: String,
        val reliability: Reliability
    )

    /**
     * Resolve a single reference. [sourceText] is the current text of the anchor's chapter (flowing)
     * or page (PDF); pass `null` when the source is unavailable. [sourceType] tags jump reliability.
     */
    fun resolveReference(
        ref: PassageReference,
        sourceText: String?,
        sourceType: SourceType,
        minFuzzyScore: Double = 0.72
    ): RefResolution {
        val reliability = reliabilityOf(sourceType)
        val anchor = ref.anchor
        if (sourceText == null) {
            return RefResolution(
                State.SOURCE_UNAVAILABLE, anchorChapter(anchor), anchorPage(anchor),
                null, 0.0, ref.quotedSnapshot, reliability
            )
        }
        // Reuse the fuzzy resolver for both anchor kinds: a PDF anchor's quote is matched as flowing
        // text on the page (positioned-glyph page/quads still drive the actual on-screen jump).
        val flowing = when (anchor) {
            is TextAnchor.Flowing -> anchor
            is TextAnchor.Pdf -> TextAnchor.Flowing(0, 0, anchor.quote)
        }
        val r = FuzzyAnchor.resolve(flowing, sourceText, minFuzzyScore)
        val state = when (r.confidence) {
            FuzzyAnchor.Confidence.EXACT -> State.RESOLVED
            FuzzyAnchor.Confidence.FUZZY -> State.FUZZY
            FuzzyAnchor.Confidence.NONE -> State.ORPHANED
        }
        return RefResolution(
            state = state,
            chapterOrdinal = anchorChapter(anchor),
            page = anchorPage(anchor),
            range = r.matchedRange,
            score = r.score,
            frozenSnapshot = ref.quotedSnapshot,
            reliability = reliability
        )
    }

    /**
     * Resolve every reference of [note]. [sourceTextFor] returns the current text for a given anchor,
     * or `null` when unavailable — the caller supplies it (loading owned chapter text, reading RR
     * cache, or returning null for an evicted/absent source).
     */
    fun resolveNote(
        note: Note,
        sourceTextFor: (TextAnchor) -> String?
    ): List<RefResolution> =
        note.references.map { ref ->
            resolveReference(ref, sourceTextFor(ref.anchor), note.source.sourceType)
        }

    /**
     * The note's overall state — the **best** across its references, because a synthesis note that
     * still resolves even one of several passages is more usable than its worst reference suggests.
     * A note with no references (a pure synthesis jotting) is [State.RESOLVED] (nothing to lose).
     */
    fun overallState(resolutions: List<RefResolution>): State =
        resolutions.minByOrNull { it.state.ordinal }?.state ?: State.RESOLVED

    private fun reliabilityOf(sourceType: SourceType): Reliability = when (sourceType) {
        SourceType.EPUB, SourceType.PDF, SourceType.INTERNAL -> Reliability.RELIABLE
        SourceType.ROYAL_ROAD, SourceType.OREILLY -> Reliability.BEST_EFFORT
    }

    private fun anchorChapter(anchor: TextAnchor): Int? =
        (anchor as? TextAnchor.Flowing)?.chapterOrdinal

    private fun anchorPage(anchor: TextAnchor): Int? =
        (anchor as? TextAnchor.Pdf)?.page
}
