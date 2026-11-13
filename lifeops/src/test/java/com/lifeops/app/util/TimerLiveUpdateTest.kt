package com.lifeops.app.util

import com.lifeops.app.data.repository.ActiveTimer
import com.lifeops.app.data.repository.TimerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerLiveUpdateTest {

    private val start = 1_700_000_000_000L

    @Test
    fun `open timer counts up from its start`() {
        val shown = TimerLiveUpdate.display(ActiveTimer("t1", start), "Mow the lawn", 12 * 60 + 30)

        assertEquals("Mow the lawn", shown.title)
        assertEquals(start, shown.chronometerBaseMillis)
        assertFalse(shown.countsDown)
        assertNull("no end, so no progress bar", shown.progress)
        assertEquals("12 min", shown.short)
        assertEquals("Timing · stop to log 12 minutes", shown.text)
    }

    @Test
    fun `pomodoro counts down to its end with minutes as progress`() {
        val shown = TimerLiveUpdate.display(ActiveTimer("t1", start, isPomodoro = true), "Write", 10 * 60 + 59)

        assertTrue(shown.countsDown)
        assertEquals(start + TimerController.POMODORO_SECONDS * 1000L, shown.chronometerBaseMillis)
        assertEquals(10, shown.progress)
        assertEquals(25, shown.progressMax)
        assertEquals("15 min left", shown.short)
        assertEquals("Pomodoro · 10 of 25 min", shown.text)
    }

    @Test
    fun `pomodoro progress never passes its end`() {
        val shown = TimerLiveUpdate.display(ActiveTimer("t1", start, isPomodoro = true), "Write", 26 * 60)
        assertEquals(25, shown.progress)
        assertEquals("0 min left", shown.short)
    }

    @Test
    fun `missing or blank title falls back`() {
        assertEquals(TimerLiveUpdate.UNTITLED, TimerLiveUpdate.display(ActiveTimer("t1", start), null, 0).title)
        assertEquals(TimerLiveUpdate.UNTITLED, TimerLiveUpdate.display(ActiveTimer("t1", start), "  ", 0).title)
    }

    @Test
    fun `first minute and one minute read naturally`() {
        assertEquals("Timing · stop to log the time", TimerLiveUpdate.display(ActiveTimer("t", start), "A", 30).text)
        assertEquals("Timing · stop to log 1 minute", TimerLiveUpdate.display(ActiveTimer("t", start), "A", 61).text)
    }
}
