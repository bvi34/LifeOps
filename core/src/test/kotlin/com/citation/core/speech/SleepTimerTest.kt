package com.citation.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A control nobody is awake to correct, so its arithmetic is checked here instead. */
class SleepTimerTest {

    @Test
    fun `arming a duration sets a deadline`() {
        val state = SleepTimer.arm(SleepMode.MINUTES_30, now = 1_000L)
        assertEquals(1_000L + 30 * 60_000L, state.expiresAt)
        assertTrue(state.isArmed)
    }

    @Test
    fun `off and end-of-chapter never count down`() {
        assertNull(SleepTimer.arm(SleepMode.OFF, now = 1_000L).expiresAt)
        assertNull(SleepTimer.arm(SleepMode.END_OF_CHAPTER, now = 1_000L).expiresAt)
        assertFalse(SleepTimer.expired(SleepTimer.arm(SleepMode.END_OF_CHAPTER, 0L), now = Long.MAX_VALUE))
        assertTrue(SleepTimer.stopsAtChapterEnd(SleepTimer.arm(SleepMode.END_OF_CHAPTER, 0L)))
    }

    @Test
    fun `remaining counts down and floors at zero`() {
        val state = SleepTimer.arm(SleepMode.MINUTES_5, now = 0L)
        assertEquals(4 * 60_000L, SleepTimer.remaining(state, now = 60_000L))
        assertEquals(0L, SleepTimer.remaining(state, now = 10 * 60_000L))
        assertNull(SleepTimer.remaining(SleepTimerState(), now = 0L))
    }

    @Test
    fun `expires at its deadline`() {
        val state = SleepTimer.arm(SleepMode.MINUTES_15, now = 0L)
        assertFalse(SleepTimer.expired(state, now = 15 * 60_000L - 1))
        assertTrue(SleepTimer.expired(state, now = 15 * 60_000L))
    }

    @Test
    fun `extending a live timer pushes the deadline out`() {
        val state = SleepTimer.arm(SleepMode.MINUTES_5, now = 0L)
        val extended = SleepTimer.extend(state, 5 * 60_000L, now = 60_000L)
        assertEquals(10 * 60_000L, extended.expiresAt)
    }

    @Test
    fun `extending a lapsed timer gives the whole extension from now`() {
        // Reaching for the button a minute late should not cost a minute.
        val state = SleepTimer.arm(SleepMode.MINUTES_5, now = 0L)
        val extended = SleepTimer.extend(state, 10 * 60_000L, now = 6 * 60_000L)
        assertEquals(16 * 60_000L, extended.expiresAt)
        assertFalse(SleepTimer.expired(extended, now = 6 * 60_000L))
    }

    @Test
    fun `extending an unarmed timer leaves it unarmed`() {
        assertEquals(SleepTimerState(), SleepTimer.extend(SleepTimerState(), 60_000L, now = 0L))
    }

    @Test
    fun `the voice fades out over the last seconds rather than cutting off`() {
        val state = SleepTimer.arm(SleepMode.MINUTES_5, now = 0L)
        assertEquals(1f, SleepTimer.volumeScale(state, now = 0L), 0.001f)
        assertEquals(1f, SleepTimer.volumeScale(state, now = 5 * 60_000L - 20_000L), 0.001f)
        assertEquals(0.5f, SleepTimer.volumeScale(state, now = 5 * 60_000L - 10_000L), 0.01f)
        assertEquals(0f, SleepTimer.volumeScale(state, now = 5 * 60_000L), 0.001f)
    }

    @Test
    fun `an unarmed timer never fades`() {
        assertEquals(1f, SleepTimer.volumeScale(SleepTimerState(), now = 0L), 0.001f)
    }

    @Test
    fun `labels round to the unit a sleepy reader reads`() {
        assertEquals("30 min", SleepTimer.label(30 * 60_000L))
        assertEquals("2 min", SleepTimer.label(105_000L))
        assertEquals("45 sec", SleepTimer.label(45_000L))
        assertNull(SleepTimer.label(0L))
        assertNull(SleepTimer.label(null))
    }

    @Test
    fun `modes label themselves`() {
        assertEquals("Off", SleepMode.OFF.label)
        assertEquals("End of chapter", SleepMode.END_OF_CHAPTER.label)
        assertEquals("45 min", SleepMode.MINUTES_45.label)
    }
}
