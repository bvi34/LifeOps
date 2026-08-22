package com.lifeops.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The relative check-in's one piece of arithmetic: turning "better/same/worse" back into the 1–10
 * energy value the wellness rollups and correlations read.
 */
class WellnessTrendTest {

    @Test
    fun `steps one point either side of the previous reading`() {
        assertEquals(7, WellnessTrend.BETTER.energyFrom(6))
        assertEquals(6, WellnessTrend.SAME.energyFrom(6))
        assertEquals(5, WellnessTrend.WORSE.energyFrom(6))
    }

    @Test
    fun `clamps to the ends of the scale`() {
        assertEquals(10, WellnessTrend.BETTER.energyFrom(10))
        assertEquals(1, WellnessTrend.WORSE.energyFrom(1))
    }

    @Test
    fun `falls back to the middle of the scale with no previous reading`() {
        assertEquals(6, WellnessTrend.BETTER.energyFrom(null))
        assertEquals(5, WellnessTrend.SAME.energyFrom(null))
        assertEquals(4, WellnessTrend.WORSE.energyFrom(null))
    }

    @Test
    fun `chains, so a run of worse walks the value down`() {
        var energy = 8
        repeat(3) { energy = WellnessTrend.WORSE.energyFrom(energy) }
        assertEquals(5, energy)
    }

    @Test
    fun `parses stored values and rejects anything else`() {
        assertEquals(WellnessTrend.WORSE, WellnessTrend.from("WORSE"))
        assertNull(WellnessTrend.from("worse"))
        assertNull(WellnessTrend.from(null))

        assertEquals(Initiative.NEUTRAL, Initiative.from("NEUTRAL"))
        assertNull(Initiative.from("maybe"))
    }

    @Test
    fun `initiative scores span minus one to plus one`() {
        assertEquals(1, Initiative.YES.score)
        assertEquals(0, Initiative.NEUTRAL.score)
        assertEquals(-1, Initiative.NO.score)
    }
}
