package com.lifeops.app.util

import com.lifeops.app.data.model.AspectHistoryEntry
import com.lifeops.app.data.model.WeekSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekCapacityTest {

    /** A sealed week whose only content is [minutes] of logged time — the baseline's sole input. */
    private fun snap(createdAt: String, minutes: Int) = WeekSnapshot(
        id = "s-$createdAt",
        weekId = "w-$createdAt",
        completedCount = 0,
        incompleteCount = 0,
        expiredCount = 0,
        skippedCount = 0,
        carriedForwardCount = 0,
        unsuccessfulCount = 0,
        totalResourcesEarned = 0,
        aspectBreakdown = emptyMap(),
        categoryBreakdown = emptyMap(),
        categorySlipBreakdown = emptyMap(),
        categoryTotalBreakdown = emptyMap(),
        hardDeadlineCompletedCount = 0,
        hardDeadlineExpiredCount = 0,
        createdAt = createdAt,
        aspectHistory = mapOf("body" to AspectHistoryEntry(minutes, "Body", "#336699"))
    )

    /** Weeks that each logged [minutes], newest last — enough of them to clear MIN_HISTORY. */
    private fun history(vararg minutes: Int): List<WeekSnapshot> =
        minutes.mapIndexed { i, m -> snap("2026-01-%02d".format(i + 1), m) }

    // --- No baseline / nothing to weigh ---------------------------------------------------------

    @Test
    fun withoutEnoughHistoryItSaysNothingRatherThanGuessing() {
        val capacity = WeekCapacityBuilder.build(
            plannedMinutes = 2400, estimatedTasks = 6, unestimatedTasks = 0,
            history = history(600, 600)
        )
        assertEquals(CapacityVerdict.NO_BASELINE, capacity.verdict)
        assertNull(capacity.typicalMinutes)
        assertNull(capacity.ratio)
        // The whole point: a plan this size is alarming, but with no baseline there's nothing
        // honest to compare it to, so the header stays quiet.
        assertNull(capacity.headline)
        assertFalse(capacity.isWarning)
    }

    @Test
    fun weeksWithZeroLoggedTimeDoNotCountTowardsTheBaseline() {
        // Four sealed weeks, but only two of them ever had time logged — below MIN_HISTORY.
        val capacity = WeekCapacityBuilder.build(
            plannedMinutes = 900, estimatedTasks = 3, unestimatedTasks = 0,
            history = history(0, 600, 0, 600)
        )
        assertEquals(CapacityVerdict.NO_BASELINE, capacity.verdict)
    }

    @Test
    fun noEstimatesMeansNothingToWeigh() {
        val capacity = WeekCapacityBuilder.build(
            plannedMinutes = 0, estimatedTasks = 0, unestimatedTasks = 9,
            history = history(600, 700, 800, 900)
        )
        assertEquals(CapacityVerdict.NO_ESTIMATES, capacity.verdict)
        assertNull(capacity.headline)
        // The baseline is still reported — it exists, there's just nothing to hold against it.
        assertEquals(700, capacity.typicalMinutes)
    }

    // --- The baseline itself --------------------------------------------------------------------

    @Test
    fun baselineIsTheMedianSoOneCrunchWeekCannotRaiseTheBar() {
        // Four ordinary ~10h weeks and one 40h outlier. A mean would read ~16h and quietly license
        // the next crunch; the median holds the bar at what a week of yours actually looks like.
        val h = history(600, 600, 660, 540, 2400)
        val capacity = WeekCapacityBuilder.build(
            plannedMinutes = 900, estimatedTasks = 4, unestimatedTasks = 0, history = h
        )
        assertEquals(600, capacity.typicalMinutes)
    }

    @Test
    fun onlyTheTrailingWindowIsRead() {
        // Twelve weeks; the eight most recent (by createdAt) are all 600, the older ones huge.
        val old = (1..4).map { snap("2025-12-%02d".format(it), 3000) }
        val recent = (1..8).map { snap("2026-01-%02d".format(it), 600) }
        val capacity = WeekCapacityBuilder.build(
            plannedMinutes = 600, estimatedTasks = 2, unestimatedTasks = 0, history = old + recent
        )
        assertEquals(600, capacity.typicalMinutes)
        assertEquals(CapacityVerdict.REALISTIC, capacity.verdict)
    }

    // --- Verdict bands --------------------------------------------------------------------------

    @Test
    fun aPlanWellBeyondAnyWeekYouHaveHadIsCalledOvercommitted() {
        val capacity = WeekCapacityBuilder.build(
            plannedMinutes = 2400, estimatedTasks = 8, unestimatedTasks = 0,
            history = history(600, 600, 600, 600)
        )
        assertEquals(CapacityVerdict.OVERCOMMITTED, capacity.verdict)
        assertEquals(4f, capacity.ratio!!, 0.001f)
        assertTrue(capacity.isWarning)
        assertTrue(capacity.headline!!.contains("doesn't fit"))
    }

    @Test
    fun justOverATypicalWeekIsStretchedNotOvercommitted() {
        val capacity = WeekCapacityBuilder.build(
            plannedMinutes = 780, estimatedTasks = 5, unestimatedTasks = 0,
            history = history(600, 600, 600)
        )
        assertEquals(CapacityVerdict.STRETCHED, capacity.verdict)
        assertTrue(capacity.isWarning)
    }

    @Test
    fun aNormalWeekIsRealisticAndNotAWarning() {
        val capacity = WeekCapacityBuilder.build(
            plannedMinutes = 600, estimatedTasks = 5, unestimatedTasks = 0,
            history = history(600, 600, 600)
        )
        assertEquals(CapacityVerdict.REALISTIC, capacity.verdict)
        assertFalse(capacity.isWarning)
        // Still says the number — a realistic plan is worth confirming, just not in alarm colours.
        assertTrue(capacity.headline!!.contains("normal week"))
    }

    @Test
    fun aLightWeekReportsRoomToSpare() {
        val capacity = WeekCapacityBuilder.build(
            plannedMinutes = 180, estimatedTasks = 2, unestimatedTasks = 0,
            history = history(600, 600, 600)
        )
        assertEquals(CapacityVerdict.ROOM, capacity.verdict)
        assertFalse(capacity.isWarning)
    }

    // --- Honesty about what the number leaves out -----------------------------------------------

    @Test
    fun unestimatedTasksMakeThePlannedTotalAFloorAndTheHeadlineSaysSo() {
        val capacity = WeekCapacityBuilder.build(
            plannedMinutes = 600, estimatedTasks = 3, unestimatedTasks = 7,
            history = history(600, 600, 600)
        )
        // Reads "realistic" on the estimates alone, but seven tasks aren't in that number at all —
        // a partial total presented as the whole plan is the same over-commitment wearing a badge.
        assertEquals(CapacityVerdict.REALISTIC, capacity.verdict)
        assertTrue(capacity.headline!!.contains("7 tasks unestimated"))
        assertTrue(capacity.headline!!.contains("floor"))
    }

    @Test
    fun oneUnestimatedTaskIsSingular() {
        val capacity = WeekCapacityBuilder.build(
            plannedMinutes = 600, estimatedTasks = 3, unestimatedTasks = 1,
            history = history(600, 600, 600)
        )
        assertTrue(capacity.headline!!.contains("1 task unestimated"))
    }

    @Test
    fun aFullyEstimatedPlanCarriesNoFloorCaveat() {
        val capacity = WeekCapacityBuilder.build(
            plannedMinutes = 600, estimatedTasks = 4, unestimatedTasks = 0,
            history = history(600, 600, 600)
        )
        assertFalse(capacity.headline!!.contains("floor"))
    }

    // --- Formatting -----------------------------------------------------------------------------

    @Test
    fun hoursReadAtThePrecisionTheEstimatesActuallyHad() {
        fun headline(planned: Int) = WeekCapacityBuilder.build(
            plannedMinutes = planned, estimatedTasks = 1, unestimatedTasks = 0,
            history = history(600, 600, 600)
        ).headline!!

        assertTrue(headline(45).startsWith("45m"))          // under an hour stays in minutes
        assertTrue(headline(390).startsWith("6.5h"))        // half-hour precision below ten hours
        assertTrue(headline(360).startsWith("6h"))          // no trailing ".0"
        assertTrue(headline(1404).startsWith("23h"))        // whole hours above ten — never "23.4h"
    }
}
