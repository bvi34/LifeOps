package com.lifeops.app.util

import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.WeekSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns the week you're about to close — together with the trailing sealed history — into a
 * **review**: a few headline metrics with honest deltas, an aspect-balance read (who got time, who's
 * a grey scar), and a short set of *earned* observations. Pure and Android-free (like [GrowthRings],
 * [BestTime], [ScoringUtils]), so the whole retrospective is unit-testable on the JVM.
 *
 * The point is the ritual. Closing the week is LifeOps' one mint; this makes the moment a mirror
 * instead of a rubber stamp. Every line here is derived from the numbers — nothing flatters, and an
 * observation only fires when the data earns it.
 */

/** Which way a metric moved against its baseline, and — via [ReviewMetric.higherIsBetter] — whether that's good. */
enum class Trend { UP, DOWN, FLAT }

/**
 * One headline number, optionally compared to the recent baseline.
 *
 * @property delta pre-formatted change string (`"+12pp"`, `"−40m"`), or `null` when there's no
 *   history to compare against (a first close, or a metric we don't seal historically).
 * @property higherIsBetter lets the UI colour an improvement correctly regardless of direction.
 */
data class ReviewMetric(
    val label: String,
    val value: String,
    val delta: String?,
    val trend: Trend,
    val higherIsBetter: Boolean
)

/**
 * One aspect's standing this week.
 *
 * @property greyStreak consecutive weeks at zero minutes ending this week (`1` = grey only this week);
 *   `0` when the aspect got time this week. A high streak is a scar the mirror will call out.
 */
data class AspectBalanceRow(
    val aspectId: String,
    val name: String,
    val colorHex: String,
    val minutes: Int,
    val greyStreak: Int
)

/** The assembled retrospective handed to the close dialog. */
data class WeekReview(
    val headline: List<ReviewMetric>,
    val aspectBalance: List<AspectBalanceRow>,
    val observations: List<String>,
    /** This week's completion rate (0..1), or `null` if nothing was relevant — for the live self-rating mirror. */
    val completionRate: Float?,
    /**
     * Whether the week's bar was cleared: `true` every commitment done, `false` some left, `null`
     * when no bar was set. The one line that answers "can I actually rest" — which a completion
     * percentage never does, because a percentage has no idea which of the tasks mattered.
     */
    val commitmentMet: Boolean? = null
) {
    /** True once at least one metric has a baseline to compare against (i.e. there's history). */
    val hasHistory: Boolean get() = headline.any { it.delta != null }
}

/** The just-finished week's live figures, aggregated by the caller from its current tasks + time. */
data class ClosingWeekStats(
    val completed: Int,
    val totalRelevant: Int,
    val carried: Int,
    val totalMinutes: Int,
    val hardDeadlineHit: Int,
    val hardDeadlineExpired: Int,
    val minutesByAspect: Map<String, Int>,
    /** `(estimatedMinutes, actualMinutes)` for completed tasks that carried an estimate. */
    val estimatedActuals: List<Pair<Int, Int>>,
    val selfRating: Int? = null,
    /**
     * Engaged reading minutes logged across the whole week window — every session summed, not one
     * sitting. Zero when reading rewards are off (no reading aspect chosen). Paired with
     * [readingPoints] to preview what closing will mint from reading.
     */
    val readingMinutes: Int = 0,
    /** Points those [readingMinutes] mint at close, floored once over the weekly total (see `ReadingRewards`). */
    val readingPoints: Int = 0,
    /** Tasks marked as this week's bar (carried-forward and queued ones excluded, as everywhere else). */
    val commitmentTotal: Int = 0,
    /** How many of those were completed. */
    val commitmentCompleted: Int = 0
)

object WeekReviewBuilder {

    private const val TRAILING = 8
    /** Match [ScoringUtils]' accuracy window so "on target" here means the same thing it does at scoring. */
    private const val ESTIMATE_TOLERANCE = 15
    private const val MAX_OBSERVATIONS = 3
    /** Above this share of the week's tasks, a "commitment" has stopped selecting anything. */
    private const val BAR_RATIO_PCT = 60
    /** Weeks smaller than this make the ratio meaningless — 2 of 3 is a light week, not over-marking. */
    private const val MIN_WEEK_FOR_BAR_RATIO = 5

    fun build(
        closing: ClosingWeekStats,
        history: List<WeekSnapshot>,
        aspects: List<Aspect>
    ): WeekReview {
        val past = history.sortedByDescending { it.createdAt }
        val lastWeek = past.firstOrNull()
        val trailing = past.take(TRAILING)

        val balance = buildAspectBalance(closing, past, aspects)
        val thisRate = rate(closing.completed, closing.totalRelevant)
        return WeekReview(
            headline = buildHeadline(closing, thisRate, lastWeek, trailing),
            aspectBalance = balance,
            observations = buildObservations(closing, thisRate, lastWeek, balance),
            completionRate = thisRate,
            commitmentMet = if (closing.commitmentTotal > 0)
                closing.commitmentCompleted >= closing.commitmentTotal else null
        )
    }

    // --- Headline metrics ----------------------------------------------------------------------

    private fun buildHeadline(
        closing: ClosingWeekStats,
        thisRate: Float?,
        lastWeek: WeekSnapshot?,
        trailing: List<WeekSnapshot>
    ): List<ReviewMetric> {
        val metrics = mutableListOf<ReviewMetric>()

        // The week's bar leads, when one was set: it's the metric that decides whether the week
        // succeeded, and burying it under a completion percentage would put the number that can't
        // answer that question above the one that can. Compared against last week's *bar*, not its
        // completion rate — and only when last week actually set one.
        if (closing.commitmentTotal > 0) {
            val thisCommit = rate(closing.commitmentCompleted, closing.commitmentTotal)
            val lastCommit = lastWeek
                ?.takeIf { it.commitmentTotal > 0 }
                ?.let { rate(it.commitmentCompleted, it.commitmentTotal) }
            metrics += ReviewMetric(
                label = "Commitment",
                value = "${closing.commitmentCompleted}/${closing.commitmentTotal}",
                delta = if (thisCommit != null && lastCommit != null)
                    "${signed(((thisCommit - lastCommit) * 100).roundToInt())}pp" else null,
                trend = trendOf(thisCommit, lastCommit),
                higherIsBetter = true
            )
        }

        // Completion rate vs last week (in percentage points).
        val lastRate = lastWeek?.let { rate(it.completedCount, relevantOf(it)) }
        metrics += ReviewMetric(
            label = "Completion",
            value = thisRate?.let { "${(it * 100).roundToInt()}%" } ?: "—",
            delta = if (thisRate != null && lastRate != null) {
                val pp = ((thisRate - lastRate) * 100).roundToInt()
                "${signed(pp)}pp"
            } else null,
            trend = trendOf(thisRate, lastRate),
            higherIsBetter = true
        )

        // Time logged vs the trailing average.
        val avgMinutes = trailing.map { totalMinutesOf(it) }.takeIf { it.isNotEmpty() }?.average()
        metrics += ReviewMetric(
            label = "Time logged",
            value = fmtMinutes(closing.totalMinutes),
            delta = avgMinutes?.let { fmtDeltaMinutes(closing.totalMinutes - it.roundToInt()) },
            trend = avgMinutes?.let { trendOf(closing.totalMinutes.toFloat(), it.toFloat()) } ?: Trend.FLAT,
            higherIsBetter = true
        )

        // Hard-deadline hit rate — only when there were any this week.
        val hdTotal = closing.hardDeadlineHit + closing.hardDeadlineExpired
        if (hdTotal > 0) {
            val thisHd = rate(closing.hardDeadlineHit, hdTotal)
            val lastHd = lastWeek
                ?.takeIf { it.hardDeadlineCompletedCount + it.hardDeadlineExpiredCount > 0 }
                ?.let { rate(it.hardDeadlineCompletedCount, it.hardDeadlineCompletedCount + it.hardDeadlineExpiredCount) }
            metrics += ReviewMetric(
                label = "Hard deadlines",
                value = thisHd?.let { "${(it * 100).roundToInt()}% hit" } ?: "—",
                delta = if (thisHd != null && lastHd != null) "${signed(((thisHd - lastHd) * 100).roundToInt())}pp" else null,
                trend = trendOf(thisHd, lastHd),
                higherIsBetter = true
            )
        }

        // Reading reward already banked this week — cumulative across every session, minted at
        // close. Shown whenever any reading was logged (rewards on), so the running total is visible
        // before the mint and it's clear that short sittings add up rather than needing one full hour.
        if (closing.readingMinutes > 0) {
            metrics += ReviewMetric(
                label = "Reading",
                value = "+${closing.readingPoints} pts · ${fmtMinutes(closing.readingMinutes)}",
                delta = null,
                trend = Trend.FLAT,
                higherIsBetter = true
            )
        }

        // Estimate accuracy — no sealed baseline, so value-only.
        val ea = closing.estimatedActuals
        if (ea.isNotEmpty()) {
            val onTarget = ea.count { abs(it.second - it.first) <= ESTIMATE_TOLERANCE }
            metrics += ReviewMetric(
                label = "Estimates",
                value = "$onTarget/${ea.size} on target",
                delta = null,
                trend = Trend.FLAT,
                higherIsBetter = true
            )
        }
        return metrics
    }

    // --- Aspect balance ------------------------------------------------------------------------

    private fun buildAspectBalance(
        closing: ClosingWeekStats,
        past: List<WeekSnapshot>,
        aspects: List<Aspect>
    ): List<AspectBalanceRow> =
        aspects.filter { !it.isArchived }
            .map { a ->
                val mins = closing.minutesByAspect[a.id] ?: 0
                AspectBalanceRow(
                    aspectId = a.id,
                    name = a.name,
                    colorHex = a.color,
                    minutes = mins,
                    greyStreak = if (mins > 0) 0 else 1 + priorGreyRun(a.id, past)
                )
            }
            // Active aspects first (most time on top); greys sink, longest scar last.
            .sortedWith(compareByDescending<AspectBalanceRow> { it.minutes }.thenBy { it.greyStreak })

    /** Consecutive most-recent snapshots in which this aspect logged zero minutes (absent counts as zero). */
    private fun priorGreyRun(aspectId: String, past: List<WeekSnapshot>): Int {
        var n = 0
        for (s in past) {
            if ((s.aspectHistory[aspectId]?.minutes ?: 0) == 0) n++ else break
        }
        return n
    }

    // --- Observations (the honest mirror) ------------------------------------------------------

    private fun buildObservations(
        closing: ClosingWeekStats,
        thisRate: Float?,
        lastWeek: WeekSnapshot?,
        balance: List<AspectBalanceRow>
    ): List<String> {
        val out = mutableListOf<String>()

        // The bar first, when one was set — it's the week's actual verdict, so nothing outranks it.
        // Only the *sharp* readings live here: the clean pass is not an observation, it's the
        // week's result, and it reaches the UI as `WeekReview.commitmentMet` rather than competing
        // with a grey scar for one of three slots.
        if (closing.commitmentTotal > 0) {
            // A bar that covers most of the list isn't a bar. Said before the hit/miss line,
            // because it changes what that line is worth. Only on weeks big enough for the ratio
            // to mean anything — 2 of 3 tasks is a small week, not an over-marked one.
            if (closing.totalRelevant >= MIN_WEEK_FOR_BAR_RATIO &&
                closing.commitmentTotal * 100 >= closing.totalRelevant * BAR_RATIO_PCT
            ) {
                out += "${closing.commitmentTotal} of ${closing.totalRelevant} tasks marked essential. " +
                    "That's not a bar, that's the list."
            }
            val missed = closing.commitmentTotal - closing.commitmentCompleted
            if (missed > 0) {
                out += "${closing.commitmentCompleted}/${closing.commitmentTotal} on the bar you set. " +
                    "$missed you called essential didn't happen."
            }
        }

        // Then grey scars — the neglected areas the Growth Record will mark.
        balance.filter { it.greyStreak >= 3 }
            .sortedByDescending { it.greyStreak }
            .take(2)
            .forEach { out += "${it.name} has been a grey scar ${it.greyStreak} weeks running." }

        // Completion slide against last week.
        val lastRate = lastWeek?.let { rate(it.completedCount, relevantOf(it)) }
        if (thisRate != null && lastRate != null) {
            val drop = lastRate - thisRate
            if (drop >= 0.15f) {
                out += "Completion slid ${pctInt(lastRate)}% → ${pctInt(thisRate)}%."
            }
        }

        // Completions without receipts.
        if (closing.totalMinutes == 0 && closing.completed > 0) {
            out += "${closing.completed} done, zero minutes logged — completions without receipts."
        }

        // Estimate bias.
        val ea = closing.estimatedActuals
        if (ea.size >= 3) {
            val ranOver = ea.count { it.second > it.first + ESTIMATE_TOLERANCE }
            if (ranOver * 5 >= ea.size * 3) { // ≥60% ran long
                out += "You keep underestimating — most tasks ran over."
            }
        }

        // The carry pile winning.
        if (closing.carried > 0 && closing.carried >= closing.completed) {
            out += "${closing.carried} carried, ${closing.completed} done — the pile's winning."
        }

        // A dry nod up — only if nothing sharper needed saying. (`out.isEmpty()` already rules out
        // a missed bar, which is exactly the week that shouldn't be congratulated for a climb.)
        if (out.isEmpty() && thisRate != null && lastRate != null && thisRate - lastRate >= 0.15f) {
            out += "Completion climbed ${pctInt(lastRate)}% → ${pctInt(thisRate)}%. Keep it."
        }

        return out.take(MAX_OBSERVATIONS)
    }

    // --- Helpers -------------------------------------------------------------------------------

    /** Non-carried, non-queued denominator sealed in a snapshot (pending → incomplete by close). */
    private fun relevantOf(s: WeekSnapshot): Int =
        s.completedCount + s.incompleteCount + s.expiredCount + s.skippedCount + s.unsuccessfulCount

    private fun totalMinutesOf(s: WeekSnapshot): Int = s.aspectHistory.values.sumOf { it.minutes }

    private fun rate(numerator: Int, denominator: Int): Float? =
        if (denominator > 0) numerator.toFloat() / denominator else null

    private fun trendOf(current: Float?, baseline: Float?): Trend = when {
        current == null || baseline == null -> Trend.FLAT
        current > baseline -> Trend.UP
        current < baseline -> Trend.DOWN
        else -> Trend.FLAT
    }

    private fun signed(n: Int): String = if (n >= 0) "+$n" else "−${abs(n)}"

    private fun pctInt(rate: Float): Int = (rate * 100).roundToInt()

    private fun fmtMinutes(m: Int): String {
        if (m <= 0) return "0m"
        val h = m / 60
        val min = m % 60
        return when {
            h > 0 && min > 0 -> "${h}h ${min}m"
            h > 0 -> "${h}h"
            else -> "${min}m"
        }
    }

    private fun fmtDeltaMinutes(delta: Int): String =
        (if (delta >= 0) "+" else "−") + fmtMinutes(abs(delta))
}
