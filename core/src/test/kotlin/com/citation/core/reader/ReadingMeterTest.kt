package com.citation.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingMeterTest {

    private val min = 60_000L

    /** A meter with a 5-minute cap and a hand-driven clock (we always pass `now` explicitly). */
    private fun meter() = ReadingMeter(maxGapMillis = 5 * min, clock = { 0L })

    @Test
    fun activePageDwellCountsUpToTheCap() {
        val m = meter()
        m.resume(now = 0)
        // Read one page for 4 minutes, then turn — the whole 4 minutes counts.
        assertEquals(4 * min, m.engagedMillis(now = 4 * min))
    }

    @Test
    fun idleDwellIsCappedNotInflated() {
        val m = meter()
        m.resume(now = 0)
        // Left open on one page for 30 minutes with no progress → capped at 5.
        assertEquals(5 * min, m.engagedMillis(now = 30 * min))
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
    fun eachCappedGapIsIndependent() {
        val m = meter()
        m.resume(now = 0)
        // Two long idle gaps, each capped at 5 → 10, not 60.
        m.progress(now = 30 * min) // gap capped to 5
        assertEquals(5 * min, m.engagedMillis(now = 30 * min))
        val later = 60 * min
        assertEquals(10 * min, m.engagedMillis(now = later)) // + another capped 5
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
