package com.citation.core.speech

import com.citation.core.doc.BlockKind
import com.citation.core.doc.DocumentBlock
import com.citation.core.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seeking: the bridge between where the eye is and where the voice starts. */
class SpeechPlanTest {

    private val source = "Alpha one. Alpha two.\n\nBeta one. Beta two.\n\nGamma one."

    private val plan = SpeechPlanner.plan(
        Chapter(ordinal = 3, title = "Three", sourceRef = "ch3", text = source)
    )

    @Test
    fun `finds the utterance containing an offset`() {
        assertEquals(0, plan.indexAt(0))
        assertEquals(0, plan.indexAt(5))
        assertEquals(1, plan.indexAt(11))
        assertEquals(4, plan.indexAt(source.length - 1))
    }

    @Test
    fun `an offset in the gap between paragraphs starts the next utterance, never repeats one`() {
        val gap = source.indexOf("\n\n")
        assertEquals("Beta one.", plan[plan.indexAt(gap)]!!.spoken)
    }

    @Test
    fun `an offset past the end is past the end`() {
        assertEquals(plan.size, plan.indexAt(source.length + 100))
    }

    @Test
    fun `an empty plan seeks to zero rather than failing`() {
        assertEquals(0, SpeechPlan.empty(0).indexAt(42))
        assertEquals(0, SpeechPlan.empty(0).offsetAt(3))
    }

    @Test
    fun `sentence skip moves one utterance`() {
        assertEquals(2, plan.skip(1, SkipGranularity.SENTENCE, forward = true))
        assertEquals(0, plan.skip(1, SkipGranularity.SENTENCE, forward = false))
    }

    @Test
    fun `paragraph skip forward lands on the next block`() {
        assertEquals("Beta one.", plan[plan.skip(0, SkipGranularity.PARAGRAPH, forward = true)]!!.spoken)
    }

    @Test
    fun `paragraph skip back restarts the paragraph before it leaves it`() {
        // Mid-paragraph: back means the top of this one, the way a track-back button works.
        assertEquals(2, plan.skip(3, SkipGranularity.PARAGRAPH, forward = false))
        // Already at the top: back means the previous paragraph.
        assertEquals(0, plan.skip(2, SkipGranularity.PARAGRAPH, forward = false))
    }

    @Test
    fun `skipping off either end reports it rather than clamping`() {
        assertEquals(plan.size, plan.skip(plan.size - 1, SkipGranularity.PARAGRAPH, forward = true))
        assertEquals(-1, plan.skip(0, SkipGranularity.PARAGRAPH, forward = false))
    }

    @Test
    fun `offsets come back canonical`() {
        assertEquals(0, plan.offsetAt(0))
        assertEquals(source.indexOf("Beta one."), plan.offsetAt(2))
        assertTrue(plan.lastOffset <= source.length)
    }

    @Test
    fun `a plan of only skipped material is empty and seekable`() {
        val code = Chapter(
            ordinal = 0, title = "", sourceRef = "", text = "val x = 1",
            blocks = listOf(DocumentBlock.Text(0, 9, BlockKind.CODE))
        )
        val empty = SpeechPlanner.plan(code)
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.indexAt(4))
    }
}
