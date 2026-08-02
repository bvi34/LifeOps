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

    // --- earnedResourceValue: estimated tasks keep the planned-value × accuracy model ----------

    @Test
    fun `estimated task earns planned value times accuracy`() {
        // planned 10, estimate 60, actual 60 (accurate) → ×2.0 → 20
        assertEquals(20, earned(planned = 10, est = 60, act = 60))
        // estimate set but no time logged → ×0.5 → 5
        assertEquals(5, earned(planned = 10, est = 60, act = null))
    }

    @Test
    fun `unsuccessful estimated task takes the half success factor`() {
        // planned 10, accurate (×2.0) → 20, then ×0.5 for unsuccessful → 10
        assertEquals(10, earned(planned = 10, est = 60, act = 60, successFactor = 0.5))
    }

    // --- earnedResourceValue: un-estimated tasks are scored from actual logged effort ----------

    @Test
    fun `no estimate with no logged time falls back to the flat baseline`() {
        // ~10 pts for the (default) first hour, even without an estimate
        assertEquals(10, earned(planned = 999, est = null, act = null))
        // manual ad-hoc task still halves the baseline (user's chosen model)
        assertEquals(5, earned(planned = 999, est = null, act = null, manual = true))
    }

    @Test
    fun `no estimate scales with actual logged effort`() {
        // 627 min logged (~10.5h): base 10 + round((627-60)/60)=19; a manual task ×0.5 ≈ 10
        assertEquals(19, earned(planned = 5, est = null, act = 627))
        assertEquals(10, earned(planned = 5, est = null, act = 627, manual = true))
        // A real ten-hour effort dwarfs the old flat-5 result either way.
        assertTrue(earned(planned = 5, est = null, act = 627, manual = true) > 5)
    }

    @Test
    fun `no estimate ignores stored planned value and uses time instead`() {
        // planned value is irrelevant on the no-estimate path — only actual time drives it
        assertEquals(
            earned(planned = 1, est = null, act = 120),
            earned(planned = 500, est = null, act = 120)
        )
    }

    private fun earned(
        planned: Int,
        est: Int?,
        act: Int?,
        priority: String = "medium",
        hardDeadline: Boolean = false,
        manual: Boolean = false,
        successFactor: Double = 1.0
    ): Int = ScoringUtils.earnedResourceValue(
        plannedResourceValue = planned,
        estimatedMinutes = est,
        actualMinutes = act,
        priorityLabel = priority,
        hardDeadline = hardDeadline,
        isManuallyAdded = manual,
        successFactor = successFactor
    )
}
