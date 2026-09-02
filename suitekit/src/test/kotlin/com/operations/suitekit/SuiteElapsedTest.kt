package com.operations.suitekit

import org.junit.Assert.assertEquals
import org.junit.Test

/** The suite's one wording for a gap of time. Health's dose windows read these strings aloud. */
class SuiteElapsedTest {

    @Test
    fun `durations read the way a countdown should`() {
        assertEquals("now", SuiteElapsed.formatDuration(0))
        assertEquals("now", SuiteElapsed.formatDuration(-5_000))
        assertEquals("45m", SuiteElapsed.formatDuration(45 * 60 * 1000L))
        assertEquals("2h", SuiteElapsed.formatDuration(2 * 60 * 60 * 1000L))
        assertEquals("3h 20m", SuiteElapsed.formatDuration(3 * 60 * 60 * 1000L + 20 * 60 * 1000L))
    }

    @Test
    fun `a gap under a minute is named, not rounded to zero`() {
        // Rounded up, so a countdown never sits on "0m" looking broken.
        assertEquals("1m", SuiteElapsed.formatDuration(1_000))
        assertEquals("just now", SuiteElapsed.formatAgo(5_000))
        assertEquals("1h ago", SuiteElapsed.formatAgo(60 * 60 * 1000L))
    }
}
