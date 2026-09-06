package com.citation.core.speech

/**
 * A chapter, laid out as the ordered units a narrator will speak.
 *
 * The plan is **derived data**, exactly like the block structure it is built from: it is recovered
 * by re-planning the chapter, is never persisted, and never edits a character of text. Two things
 * follow, and both matter. Planning can change — a better splitter, a new pause — without migrating
 * anything or invalidating a single note. And the only position ever written down stays the
 * canonical character offset every other part of Citation already uses, so a book you listened to
 * resumes correctly in the reader, and a book you read resumes correctly in the ear.
 *
 * @property chapterOrdinal the chapter this plan covers, matching [com.citation.core.model.Chapter].
 * @property utterances every speakable unit, in order, tiling the chapter's speakable text.
 */
class SpeechPlan(
    val chapterOrdinal: Int,
    val utterances: List<Utterance>
) {

    val size: Int get() = utterances.size

    val isEmpty: Boolean get() = utterances.isEmpty()

    operator fun get(index: Int): Utterance? = utterances.getOrNull(index)

    /** Where speaking this chapter ends up, in canonical characters. */
    val lastOffset: Int get() = utterances.lastOrNull()?.end ?: 0

    /**
     * The unit to start speaking at for a reader sitting at [charOffset] — the bridge from "where
     * the eye is" to "where the voice starts", crossed every time somebody hits play.
     *
     * Picks the unit containing the offset, or the next one when the offset falls in a gap (a
     * skipped table, the whitespace between paragraphs), so pressing play never re-reads a sentence
     * already finished nor silently skips one. Past the end of the chapter it returns [size].
     */
    fun indexAt(charOffset: Int): Int {
        if (utterances.isEmpty()) return 0
        var low = 0
        var high = utterances.size - 1
        var answer = utterances.size
        while (low <= high) {
            val mid = (low + high) / 2
            val utterance = utterances[mid]
            if (utterance.end > charOffset || (utterance.isMarker && utterance.start >= charOffset)) {
                answer = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return answer
    }

    /** The canonical offset speaking [index] starts at — what gets written back as progress. */
    fun offsetAt(index: Int): Int =
        utterances.getOrNull(index)?.start ?: lastOffset

    /**
     * Where a skip control lands, from unit [index].
     *
     * Sentence granularity is the neighbouring unit. Paragraph granularity moves between units that
     * begin a block, which is what a reader means by "skip this paragraph" — and, going back, means
     * the top of the current paragraph rather than the previous one, the same way a track-back
     * button restarts the track before it skips off it.
     *
     * Returns an index that may be `-1` (before the chapter) or [size] (past its end); the narrator
     * turns those into a chapter change.
     */
    fun skip(index: Int, granularity: SkipGranularity, forward: Boolean): Int = when {
        granularity == SkipGranularity.SENTENCE -> if (forward) index + 1 else index - 1
        forward -> ((index + 1)..utterances.lastIndex).firstOrNull { utterances[it].startsBlock } ?: size
        else -> {
            val current = utterances.getOrNull(index)
            if (current != null && !current.startsBlock) {
                (index downTo 0).firstOrNull { utterances[it].startsBlock } ?: 0
            } else {
                (index - 1 downTo 0).firstOrNull { utterances[it].startsBlock } ?: -1
            }
        }
    }

    companion object {
        /** A chapter with nothing to say — an image plate, or one whose text failed to load. */
        fun empty(chapterOrdinal: Int) = SpeechPlan(chapterOrdinal, emptyList())
    }
}

/** What a skip button moves by. */
enum class SkipGranularity {
    /** One unit — a sentence, a line of verse, a heading. */
    SENTENCE,

    /** One block — a whole paragraph, list item or table. */
    PARAGRAPH
}
