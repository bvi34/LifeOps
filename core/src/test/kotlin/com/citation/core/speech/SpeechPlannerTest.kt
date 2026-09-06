package com.citation.core.speech

import com.citation.core.doc.BlockKind
import com.citation.core.doc.DocumentBlock
import com.citation.core.doc.InlineSpan
import com.citation.core.doc.InlineStyle
import com.citation.core.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner is where a book becomes a performance, and where the anchoring contract is most at
 * risk — every fix that makes a chapter *sound* right is a fix that edits words. These hold it to
 * the two promises: the ear gets what it needs, and the offsets never move.
 */
class SpeechPlannerTest {

    private fun chapter(text: String, blocks: List<DocumentBlock> = emptyList()) =
        Chapter(ordinal = 0, title = "One", sourceRef = "ch1", text = text, blocks = blocks)

    private fun text(plan: SpeechPlan, source: String) =
        plan.utterances.map { source.substring(it.start, it.end) }

    @Test
    fun `plans an unstructured chapter as paragraphs of sentences`() {
        val source = "First one. Second one.\n\nA new paragraph."
        val plan = SpeechPlanner.plan(chapter(source))
        assertEquals(listOf("First one.", "Second one.", "A new paragraph."), text(plan, source))
        assertEquals(listOf(true, false, true), plan.utterances.map { it.startsBlock })
    }

    @Test
    fun `speaks a sentence broken across source lines as one line`() {
        val source = "The sentence was\nbroken across\nthree lines."
        val plan = SpeechPlanner.plan(chapter(source))
        assertEquals(1, plan.size)
        assertEquals("The sentence was broken across three lines.", plan[0]!!.spoken)
        // The canonical range still covers the newlines it was made from.
        assertEquals(0, plan[0]!!.start)
        assertEquals(source.length, plan[0]!!.end)
    }

    @Test
    fun `every utterance range is a true slice of the chapter, in order and without overlap`() {
        val source = "Heading here\nProse follows. More prose.\nA final line."
        val blocks = listOf(
            DocumentBlock.Text(0, 12, BlockKind.HEADING, level = 1),
            DocumentBlock.Text(13, 39),
            DocumentBlock.Text(40, source.length)
        )
        val plan = SpeechPlanner.plan(chapter(source, blocks))
        var previousEnd = -1
        plan.utterances.forEach {
            assertTrue(it.start >= previousEnd)
            assertTrue(it.end <= source.length)
            previousEnd = it.end
        }
    }

    @Test
    fun `a heading is spoken whole and rests longer than a paragraph`() {
        val source = "Chapter Two: The Long Road Home\nThe road began at the gate."
        val blocks = listOf(
            DocumentBlock.Text(0, 31, BlockKind.HEADING, level = 1),
            DocumentBlock.Text(32, source.length)
        )
        val plan = SpeechPlanner.plan(chapter(source, blocks))
        assertEquals(UtteranceKind.HEADING, plan[0]!!.kind)
        // A colon inside a heading must not cut it in two.
        assertEquals("Chapter Two: The Long Road Home", plan[0]!!.spoken)
        assertTrue(plan[0]!!.pauseAfterMillis > SpeechPauses().betweenParagraphs)
    }

    @Test
    fun `verse is spoken a line at a time`() {
        val source = "Tyger Tyger, burning bright,\nIn the forests of the night"
        val blocks = listOf(DocumentBlock.Text(0, source.length, BlockKind.VERSE))
        val plan = SpeechPlanner.plan(chapter(source, blocks))
        assertEquals(2, plan.size)
        assertEquals("Tyger Tyger, burning bright,", plan[0]!!.spoken)
        assertEquals(UtteranceKind.VERSE_LINE, plan[1]!!.kind)
    }

    @Test
    fun `a footnote marker is not read aloud but keeps its place in the text`() {
        val source = "The claim was contested12 for a decade."
        val blocks = listOf(
            DocumentBlock.Text(
                0, source.length,
                spans = listOf(InlineSpan(23, 25, InlineStyle.FOOTNOTE_REF, href = "#n12"))
            )
        )
        val plan = SpeechPlanner.plan(chapter(source, blocks))
        assertEquals("The claim was contested for a decade.", plan[0]!!.spoken)
        // Nothing moved: the utterance still covers the whole sentence, marker included.
        assertEquals(0, plan[0]!!.start)
        assertEquals(source.length, plan[0]!!.end)
    }

    @Test
    fun `a footnote marker is read when the reader asks for it`() {
        val source = "The claim was contested12 for a decade."
        val blocks = listOf(
            DocumentBlock.Text(
                0, source.length,
                spans = listOf(InlineSpan(23, 25, InlineStyle.FOOTNOTE_REF))
            )
        )
        val plan = SpeechPlanner.plan(chapter(source, blocks), SpeechOptions(speakFootnoteMarkers = true))
        assertEquals(source, plan[0]!!.spoken)
    }

    @Test
    fun `code is skipped by default and spoken line by line on request`() {
        val source = "val x = 1\nval y = 2"
        val blocks = listOf(DocumentBlock.Text(0, source.length, BlockKind.CODE))
        assertTrue(SpeechPlanner.plan(chapter(source, blocks)).isEmpty)
        val spoken = SpeechPlanner.plan(chapter(source, blocks), SpeechOptions(speakCode = true))
        assertEquals(2, spoken.size)
        assertEquals(UtteranceKind.CODE_LINE, spoken[0]!!.kind)
    }

    @Test
    fun `a table is skipped by default and gains spoken separators on request`() {
        // The canonical reduction runs cells together with no separator; that is what is stored.
        val source = "WidgetsTwelve4.99"
        val table = DocumentBlock.Table(
            0, source.length,
            rows = listOf(DocumentBlock.Table.Row(0, source.length, listOf(0..6, 7..12, 13..16)))
        )
        assertTrue(SpeechPlanner.plan(chapter(source, listOf(table))).isEmpty)

        val plan = SpeechPlanner.plan(chapter(source, listOf(table)), SpeechOptions(speakTables = true))
        assertEquals("Widgets, Twelve, 4.99", plan[0]!!.spoken)
        // And the mapping still lands on the real characters despite the invented commas.
        assertEquals(7..12, plan[0]!!.canonicalRange(9, 15))
    }

    @Test
    fun `an illustration is silent by default and a marker when spoken`() {
        val source = "Before the plate. After it."
        val blocks = listOf(
            DocumentBlock.Text(0, 17),
            DocumentBlock.Image(17, src = "plate.jpg", alt = "A map of the coast"),
            DocumentBlock.Text(18, source.length)
        )
        assertEquals(2, SpeechPlanner.plan(chapter(source, blocks)).size)

        val plan = SpeechPlanner.plan(chapter(source, blocks), SpeechOptions(speakImageAlt = true))
        val image = plan.utterances.first { it.kind == UtteranceKind.IMAGE_ALT }
        assertEquals("A map of the coast", image.spoken)
        assertTrue(image.isMarker)
        assertNull(image.canonicalRange(0, 5))
    }

    @Test
    fun `a scene break becomes the longest silence in the chapter`() {
        val source = "The door closed.\nA year passed."
        val blocks = listOf(
            DocumentBlock.Text(0, 16),
            DocumentBlock.Rule(17),
            DocumentBlock.Text(17, source.length)
        )
        val plan = SpeechPlanner.plan(chapter(source, blocks))
        assertEquals(SpeechPauses().atSceneBreak, plan[0]!!.pauseAfterMillis)
    }

    @Test
    fun `text a source left uncovered by any block is still spoken`() {
        // Degrade, don't crash: a gap in the structure must not silence the words inside it.
        val source = "Covered start. Orphaned middle. Covered end."
        val blocks = listOf(
            DocumentBlock.Text(0, 14),
            DocumentBlock.Text(32, source.length)
        )
        val plan = SpeechPlanner.plan(chapter(source, blocks))
        assertTrue(text(plan, source).any { it.contains("Orphaned middle") })
    }

    @Test
    fun `the last utterance of a chapter rests the longest`() {
        val plan = SpeechPlanner.plan(chapter("One. Two."))
        assertEquals(SpeechPauses().betweenChapters, plan.utterances.last().pauseAfterMillis)
    }

    @Test
    fun `an empty chapter plans to nothing rather than failing`() {
        assertTrue(SpeechPlanner.plan(chapter("")).isEmpty)
        assertTrue(SpeechPlanner.plan(chapter("   \n\n  ")).isEmpty)
    }

    @Test
    fun `the spoken string maps back onto the words it came from`() {
        val source = "The  sentence   had   uneven spacing."
        val plan = SpeechPlanner.plan(chapter(source))
        val utterance = plan[0]!!
        assertEquals("The sentence had uneven spacing.", utterance.spoken)
        val word = utterance.canonicalRange(4, 12)
        assertNotNull(word)
        assertEquals("sentence", source.substring(word!!.first, word.last + 1))
    }
}
