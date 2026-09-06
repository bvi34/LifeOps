package com.citation.core.speech

import com.citation.core.doc.BlockKind
import com.citation.core.doc.DocumentBlock
import com.citation.core.doc.InlineStyle
import com.citation.core.model.Chapter

/**
 * Turns a chapter into a [SpeechPlan] — the one place that decides what a book *sounds* like.
 *
 * It runs on the normalized [Chapter], so it is written once and covers every track Citation owns:
 * an EPUB, a PDF's reflowed text, a Royal Road chapter and an AO3 snapshot all arrive here as the
 * same flowing text plus the same block ranges. Nothing in this file knows what a spine or a serial
 * is, and no engine below it does either — which is what makes the voice swappable.
 *
 * Two invariants hold for every plan it produces, and the tests hold it to both:
 *
 * - **Order and coverage.** Units come out in reading order and every speakable stretch of text is
 *   in exactly one of them, so listening straight through a chapter reads the whole chapter and
 *   reads nothing twice.
 * - **Canonical offsets, untouched.** Text is normalized for the ear only inside [Utterance.spoken];
 *   the ranges are slices of the chapter exactly as stored. A book annotated before speech existed
 *   is spoken without a single anchor moving.
 */
object SpeechPlanner {

    /** Plan a chapter. */
    fun plan(chapter: Chapter, options: SpeechOptions = SpeechOptions()): SpeechPlan =
        SpeechPlan(chapter.ordinal, utterances(chapter.text, chapter.blocks, options))

    /** Plan raw text and structure, for sources that hold a chapter in pieces. */
    fun plan(
        chapterOrdinal: Int,
        text: String,
        blocks: List<DocumentBlock>,
        options: SpeechOptions = SpeechOptions()
    ): SpeechPlan = SpeechPlan(chapterOrdinal, utterances(text, blocks, options))

    private fun utterances(
        text: String,
        blocks: List<DocumentBlock>,
        options: SpeechOptions
    ): List<Utterance> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<Utterance>()
        if (blocks.isEmpty()) {
            // No structure was ever recovered for this chapter. The reader sets it as flat
            // paragraphs and so does the narrator: blank lines are the only structure available,
            // and a chapter that has none is one long paragraph, which speaks perfectly well.
            paragraphsOf(text).forEach { emitProse(out, text, it, BlockKind.PARAGRAPH, options) }
            return out.finish(options)
        }

        var covered = 0
        blocks.sortedBy { it.start }.forEach { block ->
            // Structure is meant to tile the text, but a source that produced a gap must not lose
            // the words in it — degrade, don't crash. Anything left uncovered is spoken as prose.
            if (block.start > covered && text.substring(covered, block.start).isNotBlank()) {
                paragraphsOf(text, covered, block.start)
                    .forEach { emitProse(out, text, it, BlockKind.PARAGRAPH, options) }
            }
            emitBlock(out, text, block, options)
            covered = maxOf(covered, block.end)
        }
        if (covered < text.length && text.substring(covered, text.length).isNotBlank()) {
            paragraphsOf(text, covered, text.length)
                .forEach { emitProse(out, text, it, BlockKind.PARAGRAPH, options) }
        }
        return out.finish(options)
    }

    private fun emitBlock(
        out: MutableList<Utterance>,
        text: String,
        block: DocumentBlock,
        options: SpeechOptions
    ) {
        when (block) {
            is DocumentBlock.Rule ->
                // A scene break has no words. It is heard as the silence after the line before it.
                out.lengthenLastPause(options.pauses.atSceneBreak)

            is DocumentBlock.Image ->
                if (options.speakImageAlt) {
                    val alt = block.alt?.trim().orEmpty()
                    if (alt.isNotEmpty()) {
                        val builder = SpokenTextBuilder().apply { synthetic(alt) }
                        val (spoken, runs) = builder.build()
                        out += Utterance(
                            start = block.start,
                            end = block.start,
                            spoken = spoken,
                            kind = UtteranceKind.IMAGE_ALT,
                            pauseAfterMillis = options.pauses.afterCaption,
                            startsBlock = true,
                            runs = runs
                        )
                    }
                }

            is DocumentBlock.Table ->
                if (options.speakTables) emitTable(out, text, block, options)

            is DocumentBlock.Text -> when (block.kind) {
                BlockKind.HEADING ->
                    if (options.speakHeadings) emitWhole(out, text, block, UtteranceKind.HEADING, options.pauses.afterHeading, options)
                BlockKind.CAPTION ->
                    if (options.speakCaptions) emitProse(out, text, block.start until block.end, block.kind, options, block)
                BlockKind.CODE ->
                    if (options.speakCode) emitLines(out, text, block, UtteranceKind.CODE_LINE, options.pauses.betweenVerseLines, options)
                BlockKind.VERSE ->
                    emitLines(out, text, block, UtteranceKind.VERSE_LINE, options.pauses.betweenVerseLines, options)
                else ->
                    emitProse(out, text, block.start until block.end, block.kind, options, block)
            }
        }
    }

    /** Sentence-split a run of prose and add it, marking the first unit as starting the block. */
    private fun emitProse(
        out: MutableList<Utterance>,
        text: String,
        range: IntRange,
        kind: BlockKind,
        options: SpeechOptions,
        block: DocumentBlock.Text? = null
    ) {
        val exclude = excludedSpans(block, options)
        val sentences = SentenceSplitter.split(text, range.first, range.last + 1, options.maxUtteranceCharacters)
        val utteranceKind = kind.asUtteranceKind()
        val trailing = when (kind) {
            BlockKind.CAPTION -> options.pauses.afterCaption
            BlockKind.LIST_ITEM -> options.pauses.betweenListItems
            else -> options.pauses.betweenParagraphs
        }
        sentences.forEachIndexed { index, sentence ->
            val builder = SpokenTextBuilder()
            builder.real(text, sentence.first, sentence.last + 1, exclude)
            val (spoken, runs) = builder.build()
            if (spoken.isBlank()) return@forEachIndexed
            out += Utterance(
                start = sentence.first,
                end = sentence.last + 1,
                spoken = spoken,
                kind = utteranceKind,
                pauseAfterMillis = if (index == sentences.lastIndex) trailing else options.pauses.betweenSentences,
                startsBlock = index == 0,
                runs = runs
            )
        }
    }

    /** Speak a block as one unit however long it runs — a heading is never cut in two. */
    private fun emitWhole(
        out: MutableList<Utterance>,
        text: String,
        block: DocumentBlock.Text,
        kind: UtteranceKind,
        pause: Int,
        options: SpeechOptions
    ) {
        val builder = SpokenTextBuilder()
        builder.real(text, block.start, block.end, excludedSpans(block, options))
        val (spoken, runs) = builder.build()
        if (spoken.isBlank()) return
        out += Utterance(block.start, block.end, spoken, kind, pause, startsBlock = true, runs = runs)
    }

    /**
     * One unit per line, for the two kinds whose line breaks are load-bearing.
     *
     * Verse reflowed into sentences stops being verse; code read as prose was never going to work
     * anyway. Both are spoken a line at a time with a short rest, which is how a person reads them.
     */
    private fun emitLines(
        out: MutableList<Utterance>,
        text: String,
        block: DocumentBlock.Text,
        kind: UtteranceKind,
        pause: Int,
        options: SpeechOptions
    ) {
        val exclude = excludedSpans(block, options)
        var lineStart = block.start
        var first = true
        while (lineStart < block.end) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0 || lineEnd > block.end) lineEnd = block.end
            val builder = SpokenTextBuilder()
            builder.real(text, lineStart, lineEnd, exclude)
            val (spoken, runs) = builder.build()
            if (spoken.isNotBlank()) {
                out += Utterance(lineStart, lineEnd, spoken, kind, pause, startsBlock = first, runs = runs)
                first = false
            }
            lineStart = lineEnd + 1
        }
        out.lengthenLastPause(options.pauses.betweenParagraphs)
    }

    /**
     * A table, one row per unit, with a comma spoken between cells.
     *
     * This is the case the whole spoken-versus-canonical split was built for. The canonical
     * reduction runs cells together with no separator — `12Widgets4.99` — and it always has, so it
     * cannot be changed without moving every anchor in every table ever captured. The separator is
     * therefore *synthesized*: it exists in the ear and nowhere in the book, and
     * [Utterance.canonicalRange] maps the voice's position back across it.
     */
    private fun emitTable(
        out: MutableList<Utterance>,
        text: String,
        table: DocumentBlock.Table,
        options: SpeechOptions
    ) {
        table.rows.forEachIndexed { index, row ->
            val builder = SpokenTextBuilder()
            row.cells.forEachIndexed { cellIndex, cell ->
                if (cellIndex > 0) builder.synthetic(", ")
                builder.real(text, cell.first, cell.last + 1)
            }
            if (row.cells.isEmpty()) builder.real(text, row.start, row.end)
            val (spoken, runs) = builder.build()
            if (spoken.isBlank()) return@forEachIndexed
            out += Utterance(
                start = row.start,
                end = row.end,
                spoken = spoken,
                kind = UtteranceKind.TABLE_ROW,
                pauseAfterMillis = options.pauses.betweenTableRows,
                startsBlock = index == 0,
                runs = runs
            )
        }
        out.lengthenLastPause(options.pauses.betweenParagraphs)
    }

    /**
     * The ranges inside a block that are spoken by nobody — footnote markers, unless the reader
     * asked for them. Their characters stay exactly where they are; they are simply not said.
     */
    private fun excludedSpans(block: DocumentBlock.Text?, options: SpeechOptions): List<IntRange> {
        if (block == null || options.speakFootnoteMarkers) return emptyList()
        return block.spans
            .filter { it.style == InlineStyle.FOOTNOTE_REF }
            .map { it.start until it.end }
    }

    /** Paragraph ranges of unstructured text: blank lines if there are any, else the whole run. */
    private fun paragraphsOf(text: String, from: Int = 0, to: Int = text.length): List<IntRange> {
        val paragraphs = mutableListOf<IntRange>()
        var start = from
        var index = from
        while (index < to) {
            if (text[index] == '\n') {
                var next = index + 1
                while (next < to && text[next].isWhitespace() && text[next] != '\n') next++
                if (next < to && text[next] == '\n') {
                    if (text.substring(start, index).isNotBlank()) paragraphs += start until index
                    while (next < to && text[next].isWhitespace()) next++
                    start = next
                    index = next
                    continue
                }
            }
            index++
        }
        if (start < to && text.substring(start, to).isNotBlank()) paragraphs += start until to
        return paragraphs
    }

    /** The last unit of a chapter rests longer than any inside it. */
    private fun MutableList<Utterance>.finish(options: SpeechOptions): List<Utterance> {
        lengthenLastPause(options.pauses.betweenChapters)
        return toList()
    }

    /** Raise the trailing pause on whatever was said last; a no-op before anything has been. */
    private fun MutableList<Utterance>.lengthenLastPause(millis: Int) {
        val last = lastOrNull() ?: return
        if (last.pauseAfterMillis < millis) this[size - 1] = last.copy(pauseAfterMillis = millis)
    }

    private fun BlockKind.asUtteranceKind(): UtteranceKind = when (this) {
        BlockKind.HEADING -> UtteranceKind.HEADING
        BlockKind.BLOCKQUOTE -> UtteranceKind.QUOTE
        BlockKind.VERSE -> UtteranceKind.VERSE_LINE
        BlockKind.CODE -> UtteranceKind.CODE_LINE
        BlockKind.LIST_ITEM -> UtteranceKind.LIST_ITEM
        BlockKind.CAPTION -> UtteranceKind.CAPTION
        BlockKind.PARAGRAPH -> UtteranceKind.BODY
    }
}
