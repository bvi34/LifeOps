package com.lifeops.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sensory half of the relative check-in. Same arithmetic as [WellnessTrendTest], run the other
 * way round: the sensory scale climbs into overload (1 calm → 10 overloaded), so "better" walks the
 * number *down*. Getting that backwards would quietly invert every sensory average in the reports,
 * which is exactly the kind of sign error a test is for.
 */
class SensoryTrendTest {

    @Test
    fun `better is calmer, so it steps down the overload scale`() {
        assertEquals(5, SensoryTrend.BETTER.sensoryFrom(6))
        assertEquals(6, SensoryTrend.NEUTRAL.sensoryFrom(6))
        assertEquals(7, SensoryTrend.WORSE.sensoryFrom(6))
    }

    @Test
    fun `clamps to the ends of the scale`() {
        assertEquals(1, SensoryTrend.BETTER.sensoryFrom(1))
        assertEquals(10, SensoryTrend.WORSE.sensoryFrom(10))
    }

    @Test
    fun `falls back to the middle of the scale with no previous reading`() {
        assertEquals(4, SensoryTrend.BETTER.sensoryFrom(null))
        assertEquals(5, SensoryTrend.NEUTRAL.sensoryFrom(null))
        assertEquals(6, SensoryTrend.WORSE.sensoryFrom(null))
    }

    @Test
    fun `chains, so a run of worse walks the load up`() {
        var sensory = 4
        repeat(3) { sensory = SensoryTrend.WORSE.sensoryFrom(sensory) }
        assertEquals(7, sensory)
    }

    @Test
    fun `parses stored values and rejects anything else`() {
        assertEquals(SensoryTrend.NEUTRAL, SensoryTrend.from("NEUTRAL"))
        assertNull(SensoryTrend.from("neutral"))
        assertNull(SensoryTrend.from(null))
        // "SAME" is WellnessTrend's middle answer, not this one's — the two must not cross-parse.
        assertNull(SensoryTrend.from("SAME"))
    }
}
