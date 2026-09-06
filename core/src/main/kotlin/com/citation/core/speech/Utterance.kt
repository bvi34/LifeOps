package com.citation.core.speech

/**
 * One **speakable unit** of a chapter — a sentence, a heading, a line of verse — carrying both the
 * string an engine is asked to say and the canonical text range it came from.
 *
 * The two are deliberately separate, and that separation is the whole reason this type exists.
 * What sounds right and what anchors right are different strings: a sentence broken across three
 * source lines must be spoken as one flowing line, a footnote marker must not be read aloud as a
 * stray number mid-clause, and a table row is gibberish unless something is said between its cells.
 * Every one of those fixes edits the words being spoken — and Citation's anchoring contract says the
 * chapter's canonical text may never change, because a note captured today has to resolve against
 * the same offsets tomorrow.
 *
 * So [spoken] is free to differ from `text.substring(start, end)`, and [runs] records exactly how
 * the two line up. That is what lets the engine's "I am on characters 40..46 of what you gave me"
 * come back as a *canonical* range the reader can light up, the note composer can quote, and
 * [com.citation.core.reader.ReadingProgress] can count — the same offsets everything else in
 * Citation already speaks.
 *
 * @property start first canonical character of this utterance in the chapter's text.
 * @property end one past its last canonical character; equal to [start] for a marker (see below).
 * @property spoken what to hand the engine. Never blank for a speakable unit.
 * @property kind what this unit *is*, which decides how it is set and how long the pause after it
 *   runs. See [UtteranceKind].
 * @property pauseAfterMillis silence to leave after this unit, on top of whatever the engine does
 *   with its own punctuation. Set by the planner from [SpeechPauses], not by the engine.
 * @property startsBlock whether this is the first utterance of its source block — what "skip
 *   paragraph" moves between, as opposed to "skip sentence".
 * @property runs the [spoken]-to-canonical mapping. Built by the planner; see [canonicalAt].
 */
data class Utterance(
    val start: Int,
    val end: Int,
    val spoken: String,
    val kind: UtteranceKind = UtteranceKind.BODY,
    val pauseAfterMillis: Int = 0,
    val startsBlock: Boolean = false,
    val runs: List<SpokenRun> = listOf(SpokenRun(0, start, spoken.length))
) {

    /** This utterance's canonical range; empty for a marker. */
    val range: IntRange get() = start until end

    /**
     * Whether this unit owns no canonical characters — an image's alt text, which is spoken but
     * exists nowhere in the chapter's text. It highlights nothing, and seeking to it is a no-op for
     * progress, exactly as [com.citation.core.doc.DocumentBlock.Image] intends.
     */
    val isMarker: Boolean get() = end == start

    /**
     * The canonical offset the engine's [spokenIndex] refers to.
     *
     * Total by construction: an index inside a run maps exactly; an index that lands on synthesized
     * text (the `", "` written between table cells, which exists in no book) maps to the start of
     * the next real run, so a highlight following the voice never jumps backwards or outside this
     * utterance. Out-of-range indices clamp to [start] / [end].
     */
    fun canonicalAt(spokenIndex: Int): Int {
        if (runs.isEmpty()) return start
        if (spokenIndex < 0) return start
        runs.forEach { run ->
            if (spokenIndex < run.spokenStart) return run.canonicalStart
            if (spokenIndex < run.spokenStart + run.length) {
                return run.canonicalStart + (spokenIndex - run.spokenStart)
            }
        }
        return end
    }

    /**
     * The canonical range for a range the engine reported into [spoken] — the word it is saying
     * right now, for the reader to light up.
     *
     * Returns `null` rather than an empty or nonsensical range when there is nothing to show: a
     * marker utterance (no canonical characters at all), or a spoken range that lies entirely in
     * synthesized text.
     */
    fun canonicalRange(spokenStart: Int, spokenEnd: Int): IntRange? {
        if (isMarker || spokenEnd <= spokenStart) return null
        val from = canonicalAt(spokenStart).coerceIn(start, end)
        val to = canonicalAt(spokenEnd - 1).coerceIn(start, end - 1)
        if (to < from) return null
        return from..to
    }
}

/**
 * A contiguous stretch of [Utterance.spoken] copied verbatim from the chapter's canonical text.
 *
 * Runs are how the mapping stays exact and cheap at the same time: the common utterance is one run
 * (spoken text *is* the slice), a whitespace-normalized paragraph is a handful, and only the rare
 * table row runs to a dozen. Nothing here stores a per-character index.
 *
 * @property spokenStart offset of this run within [Utterance.spoken].
 * @property canonicalStart offset of the same characters in the chapter's text.
 * @property length how many characters the run covers in both.
 */
data class SpokenRun(
    val spokenStart: Int,
    val canonicalStart: Int,
    val length: Int
)

/**
 * What a unit of speech *is*, which is the only thing the narrator needs in order to set it: how
 * long to rest after it, and whether the reader can skip it wholesale.
 *
 * Deliberately coarser than [com.citation.core.doc.BlockKind] — the distinction between an ordered
 * and an unordered list item matters on the page and not at all in the ear.
 */
enum class UtteranceKind {
    /** Ordinary prose. The overwhelming majority of everything spoken. */
    BODY,

    /** A heading. Worth a real rest after, since it announces what follows. */
    HEADING,

    /** A line inside a quotation. */
    QUOTE,

    /** One line of verse, whose line break is the point and so becomes a pause. */
    VERSE_LINE,

    /** One item of a list. */
    LIST_ITEM,

    /** A figure or table caption. */
    CAPTION,

    /** One row of a table, cells separated in the ear by synthesized punctuation. */
    TABLE_ROW,

    /** A line of code or preformatted text, spoken only if the reader asks for it. */
    CODE_LINE,

    /** An illustration's alt text: spoken, but owning no characters. Always a marker. */
    IMAGE_ALT
}
