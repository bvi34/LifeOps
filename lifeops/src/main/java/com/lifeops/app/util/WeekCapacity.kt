package com.lifeops.app.util

import com.lifeops.app.data.model.WeekSnapshot
import kotlin.math.roundToInt

/**
 * The plan-time counterpart to [WeekReviewBuilder]: what you have just committed the week to,
 * held against what your weeks have actually held. Pure and Android-free (like [GrowthRings],
 * [BestTime], [ScoringUtils]), so it is unit-testable on the JVM.
 *
 * LifeOps' one honest mirror already runs at close, which is the moment it can change nothing.
 * A week you over-committed on Monday is a week you cannot rest at the end of, and the app has
 * every number it needs to have said so on Monday: the estimates you typed, and the eight sealed
 * weeks behind you. This says it while the week is still yours to shape.
 *
 * It never blocks and never re-plans for you. It states two numbers and their ratio, and shuts up
 * when it hasn't earned the right to speak — with no history there is no baseline, and a guess
 * dressed as a baseline is worse than silence.
 */

/** Where the week's planned hours sit against a typical week of yours. */
enum class CapacityVerdict {
    /** Fewer than [WeekCapacityBuilder.MIN_HISTORY] sealed weeks with logged time — no baseline yet. */
    NO_BASELINE,

    /** Nothing carries an estimate, so there is nothing to weigh. */
    NO_ESTIMATES,

    /** Comfortably inside a typical week. */
    ROOM,

    /** About a normal week's work. */
    REALISTIC,

    /** Above a typical week, but not absurdly. */
    STRETCHED,

    /** Well beyond any week you have actually had. */
    OVERCOMMITTED
}

/**
 * @property plannedMinutes summed estimates across the week's live tasks.
 * @property estimatedTasks how many tasks carried an estimate.
 * @property unestimatedTasks how many did not — [plannedMinutes] is a floor, not a total, and the
 *   headline says so whenever this is non-zero. Hiding it would turn a partial number into a
 *   false reassurance, which is exactly the failure this feature exists to prevent.
 * @property typicalMinutes the median logged minutes of the trailing sealed weeks, or null when
 *   there isn't enough history.
 * @property ratio [plannedMinutes] / [typicalMinutes], or null without a baseline.
 * @property headline the one line the week header shows, already in the app's voice.
 */
data class WeekCapacity(
    val plannedMinutes: Int,
    val estimatedTasks: Int,
    val unestimatedTasks: Int,
    val typicalMinutes: Int?,
    val ratio: Float?,
    val verdict: CapacityVerdict,
    val headline: String?
) {
    /** True when the plan is worth interrupting for — the header stays quiet otherwise. */
    val isWarning: Boolean
        get() = verdict == CapacityVerdict.STRETCHED || verdict == CapacityVerdict.OVERCOMMITTED
}

object WeekCapacityBuilder {

    /** Sealed weeks read for the baseline — the same window [WeekReviewBuilder] compares against. */
    const val TRAILING = 8

    /**
     * Weeks with logged time needed before a baseline is claimed. Three is the smallest number from
     * which a median means anything; below it the app says it doesn't know yet.
     */
    const val MIN_HISTORY = 3

    private const val ROOM_CEILING = 0.6f
    private const val REALISTIC_CEILING = 1.1f
    private const val STRETCHED_CEILING = 1.5f

    /**
     * @param plannedMinutes total estimated minutes across the week's live tasks.
     * @param estimatedTasks / [unestimatedTasks] the split behind that total.
     * @param history every sealed snapshot; only the most recent [TRAILING] with any logged time
     *   are used.
     */
    fun build(
        plannedMinutes: Int,
        estimatedTasks: Int,
        unestimatedTasks: Int,
        history: List<WeekSnapshot>
    ): WeekCapacity {
        // Median, not mean: one 40-hour crunch week should not license the next one. A mean lets a
        // single outlier quietly raise the bar it is supposed to be measured against.
        val typical = history
            .sortedByDescending { it.createdAt }
            .map { snap -> snap.aspectHistory.values.sumOf { it.minutes } }
            .filter { it > 0 }
            .take(TRAILING)
            .takeIf { it.size >= MIN_HISTORY }
            ?.let { median(it) }

        if (estimatedTasks == 0) {
            return WeekCapacity(
                plannedMinutes = 0,
                estimatedTasks = 0,
                unestimatedTasks = unestimatedTasks,
                typicalMinutes = typical,
                ratio = null,
                verdict = CapacityVerdict.NO_ESTIMATES,
                headline = null
            )
        }
        if (typical == null) {
            return WeekCapacity(
                plannedMinutes = plannedMinutes,
                estimatedTasks = estimatedTasks,
                unestimatedTasks = unestimatedTasks,
                typicalMinutes = null,
                ratio = null,
                verdict = CapacityVerdict.NO_BASELINE,
                headline = null
            )
        }

        val ratio = plannedMinutes.toFloat() / typical
        val verdict = when {
            ratio <= ROOM_CEILING -> CapacityVerdict.ROOM
            ratio <= REALISTIC_CEILING -> CapacityVerdict.REALISTIC
            ratio <= STRETCHED_CEILING -> CapacityVerdict.STRETCHED
            else -> CapacityVerdict.OVERCOMMITTED
        }
        return WeekCapacity(
            plannedMinutes = plannedMinutes,
            estimatedTasks = estimatedTasks,
            unestimatedTasks = unestimatedTasks,
            typicalMinutes = typical,
            ratio = ratio,
            verdict = verdict,
            headline = headlineFor(verdict, plannedMinutes, typical, unestimatedTasks)
        )
    }

    private fun headlineFor(
        verdict: CapacityVerdict,
        planned: Int,
        typical: Int,
        unestimated: Int
    ): String {
        val planStr = fmtHours(planned)
        val typicalStr = fmtHours(typical)
        val base = when (verdict) {
            CapacityVerdict.OVERCOMMITTED ->
                "$planStr planned. Your weeks hold about $typicalStr. This one doesn't fit."
            CapacityVerdict.STRETCHED ->
                "$planStr planned against a usual $typicalStr. Tight."
            CapacityVerdict.REALISTIC ->
                "$planStr planned — about a normal week ($typicalStr)."
            CapacityVerdict.ROOM ->
                "$planStr planned against a usual $typicalStr. Room to spare."
            else -> ""
        }
        // Say the number is a floor whenever it is one. A capacity read built from half the tasks
        // that presents itself as the whole plan is the same over-commitment, wearing a badge.
        return if (unestimated > 0) {
            "$base $unestimated task${if (unestimated == 1) "" else "s"} unestimated, so that's a floor."
        } else base
    }

    /** Lower median for an even count — the conservative half, on the same principle as the median itself. */
    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }

    private fun fmtHours(minutes: Int): String {
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60f
        // One decimal below ten hours ("6.5h"), whole hours above it — nobody plans to the
        // six-minute mark, and "23.4h" reads as a precision the estimates never had.
        return if (hours < 10f) {
            val rounded = (hours * 2).roundToInt() / 2.0
            if (rounded % 1.0 == 0.0) "${rounded.toInt()}h" else "${rounded}h"
        } else {
            "${hours.roundToInt()}h"
        }
    }
}
