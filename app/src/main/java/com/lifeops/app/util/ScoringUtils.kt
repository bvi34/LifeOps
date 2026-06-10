package com.lifeops.app.util

import kotlin.math.abs

object ScoringUtils {
    /**
     * Returns the accuracy multiplier applied to a task's resourceValue at week-close time.
     *
     *   No estimate set                     → 1.0x (nothing to measure against)
     *   Estimate set, but no time logged    → 0.5x (planned but execution untracked)
     *   Actual within ±15 min of estimate   → 2.0x (rewarded for accurate planning)
     *   Actual < estimated − 15             → 0.9x (over-allocated, finished faster)
     *   Actual > estimated + 15             → 0.75x (underestimated, ran over)
     */
    fun accuracyMultiplier(estimatedMinutes: Int?, actualMinutes: Int?): Double {
        val estimated = estimatedMinutes ?: return 1.0
        val actual = actualMinutes?.takeIf { it > 0 } ?: return 0.5
        return when {
            abs(actual - estimated) <= 15 -> 2.0
            actual < estimated -> 0.9
            else -> 0.75
        }
    }
}
