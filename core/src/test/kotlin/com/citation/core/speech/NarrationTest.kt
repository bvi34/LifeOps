package com.citation.core.speech

import com.citation.core.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions between sentences — chapter ends, skip edges, and the sleep timer over both. */
class NarrationTest {

    private fun planFor(ordinal: Int, text: String = "One. Two. Three.") =
        SpeechPlanner.plan(Chapter(ordinal, "c", "ref", text))

    private val settings = SpeechSettings()

    @Test
    fun `play starts at the utterance under the reader's position`() {
        val plan = planFor(0)
        assertEquals(NarrationStep.Speak(1), Narration.start(plan, 5))
    }

    @Test
    fun `play past the end of a chapter has nothing to say`() {
        assertEquals(NarrationStep.Stop, Narration.start(planFor(0), 500))
    }

    @Test
    fun `the middle of a chapter simply advances`() {
        assertEquals(NarrationStep.Speak(1), Narration.after(planFor(0), 0, 5, settings))
    }

    @Test
    fun `the end of a chapter rolls into the next one`() {
        val plan = planFor(1)
        assertEquals(
            NarrationStep.ChangeChapter(2, fromEnd = false),
            Narration.after(plan, plan.size - 1, chapterCount = 5, settings = settings)
        )
    }

    @Test
    fun `the end of the last chapter stops`() {
        val plan = planFor(4)
        assertEquals(NarrationStep.Stop, Narration.after(plan, plan.size - 1, 5, settings))
    }

    @Test
    fun `auto-advance off stops at the chapter end`() {
        val plan = planFor(1)
        val step = Narration.after(plan, plan.size - 1, 5, settings.copy(autoAdvanceChapter = false))
        assertEquals(NarrationStep.Stop, step)
    }

    @Test
    fun `an expired sleep timer stops mid-chapter`() {
        val armed = SleepTimer.arm(SleepMode.MINUTES_5, now = 0L)
        val step = Narration.after(planFor(0), 0, 5, settings, armed, now = 6 * 60_000L)
        assertEquals(NarrationStep.Stop, step)
    }

    @Test
    fun `a sleep timer that has not run out does not interrupt`() {
        val armed = SleepTimer.arm(SleepMode.MINUTES_30, now = 0L)
        val step = Narration.after(planFor(0), 0, 5, settings, armed, now = 60_000L)
        assertEquals(NarrationStep.Speak(1), step)
    }

    @Test
    fun `end-of-chapter sleep lets the chapter finish and refuses the next`() {
        val plan = planFor(1)
        val armed = SleepTimer.arm(SleepMode.END_OF_CHAPTER, now = 0L)
        // Mid-chapter it changes nothing...
        assertEquals(NarrationStep.Speak(1), Narration.after(plan, 0, 5, settings, armed, now = 10_000L))
        // ...and at the end it stops instead of advancing.
        assertEquals(
            NarrationStep.Stop,
            Narration.after(plan, plan.size - 1, 5, settings, armed, now = 10_000L)
        )
    }

    @Test
    fun `skipping forward off the end continues into the next chapter`() {
        val plan = planFor(0)
        val step = Narration.skip(plan, plan.size - 1, SkipGranularity.SENTENCE, forward = true, chapterCount = 3)
        assertEquals(NarrationStep.ChangeChapter(1, fromEnd = false), step)
    }

    @Test
    fun `skipping back off the top continues into the end of the previous chapter`() {
        val plan = planFor(2)
        val step = Narration.skip(plan, 0, SkipGranularity.PARAGRAPH, forward = false, chapterCount = 3)
        assertEquals(NarrationStep.ChangeChapter(1, fromEnd = true), step)
    }

    @Test
    fun `skipping back at the very start of the book restarts the first sentence`() {
        val step = Narration.skip(planFor(0), 0, SkipGranularity.PARAGRAPH, forward = false, chapterCount = 3)
        assertEquals(NarrationStep.Speak(0), step)
    }

    @Test
    fun `narration state reports where to write the reading position back`() {
        val state = NarrationState(
            status = NarrationStatus.SPEAKING,
            utteranceIndex = 2,
            utteranceRange = 40..55
        )
        assertEquals(40, state.charOffset)
        assertTrue(state.isActive)
        assertTrue(NarrationState().charOffset == null)
    }
}
