package com.citation.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingMeterTest {

    private val min = 60_000L

    /** A meter with a 5-minute idle timeout and a hand-driven clock (we always pass `now`). */
    private fun meter() = ReadingMeter(idleTimeoutMillis = 5 * min, clock = { 0L })

    @Test
    fun activePageDwellWithinTimeoutCountsInFull() {
        val m = meter()
        m.resume(now = 0)
        // Read one page for 4 minutes, then turn — the whole 4 minutes counts.
        assertEquals(4 * min, m.engagedMillis(now = 4 * min))
    }

    @Test
    fun idleDwellPastTimeoutCreditsNothing() {
        val m = meter()
        m.resume(now = 0)
        // Left open on one page for 30 minutes with no progress → the clock stopped, credit zero.
        assertEquals(0, m.engagedMillis(now = 30 * min))
    }

    @Test
    fun boundaryExactlyAtTimeoutCountsButPastItVoids() {
        meter().let { m ->
            m.resume(now = 0)
            assertEquals(5 * min, m.engagedMillis(now = 5 * min)) // exactly the timeout still counts
        }
        meter().let { m ->
            m.resume(now = 0)
            assertEquals(0, m.engagedMillis(now = 5 * min + 1)) // one ms past → voided
        }
    }

    @Test
    fun progressBanksTheIntervalAndRestartsIt() {
        val m = meter()
        m.resume(now = 0)
        m.progress(now = 3 * min)   // banked 3
        m.progress(now = 5 * min)   // banked another 2 → 5 total
        assertEquals(5 * min, m.engagedMillis(now = 5 * min))
    }

    @Test
    fun pauseStopsAccrualUntilResume() {
        val m = meter()
        m.resume(now = 0)
        m.pause(now = 2 * min)                 // banked 2
        assertFalse(m.isActive)
        // Time passes while backgrounded — nothing accrues.
        assertEquals(2 * min, m.engagedMillis(now = 20 * min))
        m.resume(now = 20 * min)
        assertEquals(3 * min, m.engagedMillis(now = 21 * min)) // 2 + 1
    }

    @Test
    fun longIdleGapsCreditNothing() {
        val m = meter()
        m.resume(now = 0)
        // A page turn after a 30-minute silence: you were away, so that gap voids entirely.
        m.progress(now = 30 * min)
        assertEquals(0, m.engagedMillis(now = 30 * min))
        // Another long idle stretch to the next signal — still nothing.
        assertEquals(0, m.engagedMillis(now = 60 * min))
    }

    @Test
    fun genuineReadingPaceCountsEveryInterval() {
        val m = meter()
        m.resume(now = 0)
        // Turning pages every couple of minutes — each gap is within the timeout, all of it counts.
        m.progress(now = 2 * min)
        m.progress(now = 4 * min)
        assertEquals(5 * min, m.engagedMillis(now = 5 * min)) // 2 + 2 + 1
    }

    @Test
    fun flushDrainsAndKeepsCountingWhenActive() {
        val m = meter()
        m.resume(now = 0)
        assertEquals(3 * min, m.flushMillis(now = 3 * min)) // drains 3
        assertEquals(0, m.engagedMillis(now = 3 * min))     // reset
        assertEquals(2 * min, m.engagedMillis(now = 5 * min)) // fresh interval keeps going
        assertTrue(m.isActive)
    }

    @Test
    fun flushWhilePausedDrainsAndStaysPaused() {
        val m = meter()
        m.resume(now = 0)
        m.pause(now = 4 * min)
        assertEquals(4 * min, m.flushMillis(now = 10 * min))
        assertFalse(m.isActive)
        assertEquals(0, m.flushMillis(now = 20 * min))
    }

    @Test
    fun clockSkewNeverProducesNegativeTime() {
        val m = meter()
        m.resume(now = 10 * min)
        assertEquals(0, m.engagedMillis(now = 5 * min)) // now < anchor
    }

    @Test
    fun resumeIsIdempotentWhileActive() {
        val m = meter()
        m.resume(now = 0)
        m.resume(now = 2 * min) // must not reset the anchor
        assertEquals(3 * min, m.engagedMillis(now = 3 * min))
    }
}
