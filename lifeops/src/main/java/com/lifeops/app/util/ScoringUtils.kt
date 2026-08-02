package com.lifeops.app.util

import kotlin.math.abs
import kotlin.math.roundToInt

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

    /**
     * The resource points a task contributes to its aspect at week-close — the single source of
     * truth for both the actual mint and the live "This Week" preview.
     *
     * Two scoring paths, keyed on whether the task was estimated:
     *
     *  - **Estimated tasks** are scored as planned value × [accuracyMultiplier] — planning is the
     *    thing being measured, so an accurate estimate is rewarded and an untracked one penalised.
     *  - **Un-estimated tasks** are scored from *actual logged effort* instead, via the same
     *    time→points curve ([ImportParser.computeResourceValue]): ≈10 pts for the first hour,
     *    +≈1 pt/hour after, then the task's own urgency / hard-deadline / manual factors. So a real
     *    ten-hour effort earns far more than the flat one-hour baseline even without a plan, while a
     *    no-estimate task with no logged time falls back to that baseline.
     *
     * [successFactor] scales the result for partial credit — 1.0 for a completed task, 0.5 for one
     * closed as unsuccessful — and the whole thing is rounded once, at the end.
     */
    fun earnedResourceValue(
        plannedResourceValue: Int,
        estimatedMinutes: Int?,
        actualMinutes: Int?,
        priorityLabel: String,
        hardDeadline: Boolean,
        isManuallyAdded: Boolean,
        successFactor: Double = 1.0
    ): Int {
        val base: Double = if (estimatedMinutes != null) {
            plannedResourceValue * accuracyMultiplier(estimatedMinutes, actualMinutes)
        } else {
            ImportParser.computeResourceValue(
                priority = priorityLabel,
                hardDeadline = hardDeadline,
                estimatedMinutes = actualMinutes?.takeIf { it > 0 },
                isManuallyAdded = isManuallyAdded
            ).toDouble()
        }
        return (base * successFactor).roundToInt()
    }

    /** Convenience overload scoring a [task]'s contribution given its [actualMinutes] logged. */
    fun earnedResourceValue(
        task: com.lifeops.app.data.model.Task,
        actualMinutes: Int?,
        successFactor: Double = 1.0
    ): Int = earnedResourceValue(
        plannedResourceValue = task.resourceValue,
        estimatedMinutes = task.estimatedMinutes,
        actualMinutes = actualMinutes,
        priorityLabel = task.priority.label,
        hardDeadline = task.hardDeadline,
        isManuallyAdded = task.isManuallyAdded,
        successFactor = successFactor
    )
}
