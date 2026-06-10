package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test

class ScoringUtilsTest {

    @Test
    fun `no estimate returns 1_0x`() {
        assertEquals(1.0, ScoringUtils.accuracyMultiplier(null, 30), 0.001)
    }

    @Test
    fun `estimate set but no time logged returns 0_5x`() {
        assertEquals(0.5, ScoringUtils.accuracyMultiplier(60, null), 0.001)
        assertEquals(0.5, ScoringUtils.accuracyMultiplier(60, 0), 0.001)
    }

    @Test
    fun `actual within 15 min of estimate returns 2_0x`() {
        assertEquals(2.0, ScoringUtils.accuracyMultiplier(60, 60), 0.001)
        assertEquals(2.0, ScoringUtils.accuracyMultiplier(60, 50), 0.001)
        assertEquals(2.0, ScoringUtils.accuracyMultiplier(60, 75), 0.001)
    }

    @Test
    fun `actual less than estimate minus 15 returns 0_9x`() {
        assertEquals(0.9, ScoringUtils.accuracyMultiplier(60, 40), 0.001)
        assertEquals(0.9, ScoringUtils.accuracyMultiplier(120, 60), 0.001)
    }

    @Test
    fun `actual more than estimate plus 15 returns 0_75x`() {
        assertEquals(0.75, ScoringUtils.accuracyMultiplier(30, 60), 0.001)
        assertEquals(0.75, ScoringUtils.accuracyMultiplier(60, 90), 0.001)
    }
}
