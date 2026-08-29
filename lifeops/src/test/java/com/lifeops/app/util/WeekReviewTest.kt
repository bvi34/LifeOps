package com.lifeops.app.util

import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.AspectHistoryEntry
import com.lifeops.app.data.model.WeekSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekReviewTest {

    private fun aspect(id: String, name: String = id) =
        Aspect(id = id, name = name, color = "#336699", icon = "star")

    private fun snap(
        createdAt: String,
        completed: Int,
        incomplete: Int = 0,
        carried: Int = 0,
        hdHit: Int = 0,
        hdExpired: Int = 0,
        aspectHistory: Map<String, AspectHistoryEntry> = emptyMap(),
        commitmentTotal: Int = 0,
        commitmentCompleted: Int = 0
    ) = WeekSnapshot(
        id = "s-$createdAt",
        weekId = "w-$createdAt",
        completedCount = completed,
        incompleteCount = incomplete,
        expiredCount = 0,
        skippedCount = 0,
        carriedForwardCount = carried,
        unsuccessfulCount = 0,
        totalResourcesEarned = 0,
        aspectBreakdown = emptyMap(),
        categoryBreakdown = emptyMap(),
        categorySlipBreakdown = emptyMap(),
        categoryTotalBreakdown = emptyMap(),
        hardDeadlineCompletedCount = hdHit,
        hardDeadlineExpiredCount = hdExpired,
        createdAt = createdAt,
        aspectHistory = aspectHistory,
        commitmentTotal = commitmentTotal,
        commitmentCompleted = commitmentCompleted
    )

    private fun hist(minutes: Int, name: String = "x") = AspectHistoryEntry(minutes, name, "#336699")

    private val closing = ClosingWeekStats(
        completed = 7,
        totalRelevant = 10,
        carried = 1,
        totalMinutes = 300,
        hardDeadlineHit = 2,
        hardDeadlineExpired = 0,
        minutesByAspect = mapOf("body" to 120, "craft" to 180),
        estimatedActuals = emptyList()
    )

    @Test
    fun firstCloseHasNoDeltasButStillReports() {
        val review = WeekReviewBuilder.build(closing, emptyList(), listOf(aspect("body"), aspect("craft")))
        assertFalse(review.hasHistory)
        val completion = review.headline.first { it.label == "Completion" }
        assertEquals("70%", completion.value)
        assertNull(completion.delta)
        assertEquals(0.7f, review.completionRate!!, 0.001f)
    }

    @Test
    fun readingMetricPreviewsCumulativePointsWhenAnyReadingLogged() {
        // Six 10-minute sittings = 60 engaged minutes summed across the week → +5 pts (the
        // week-close mint), shown before close so short sessions are visibly cumulative.
        val withReading = closing.copy(readingMinutes = 60, readingPoints = 5)
        val review = WeekReviewBuilder.build(withReading, emptyList(), listOf(aspect("body")))
        val reading = review.headline.first { it.label == "Reading" }
        assertEquals("+5 pts · 1h", reading.value)
        assertNull(reading.delta)
    }

    @Test
    fun readingMetricShowsSubThresholdMinutesAtZeroPoints() {
        // 40 min @5/hr floors to 3 pts; even below a point it would still surface the minutes so the
        // running total is visible. Here it's 3 pts — confirms minutes + points render together.
        val withReading = closing.copy(readingMinutes = 40, readingPoints = 3)
        val reading = WeekReviewBuilder.build(withReading, emptyList(), listOf(aspect("body")))
            .headline.first { it.label == "Reading" }
        assertEquals("+3 pts · 40m", reading.value)
    }

    @Test
    fun noReadingMetricWhenNothingRead() {
        val review = WeekReviewBuilder.build(closing, emptyList(), listOf(aspect("body")))
        assertTrue(review.headline.none { it.label == "Reading" })
    }

    @Test
    fun completionDeltaComparesToLastWeekInPercentagePoints() {
        // Last week: 5/10 = 50%; this week 70% → +20pp, UP.
        val last = snap("2026-07-20", completed = 5, incomplete = 5)
        val review = WeekReviewBuilder.build(closing, listOf(last), listOf(aspect("body")))
        val completion = review.headline.first { it.label == "Completion" }
        assertEquals("+20pp", completion.delta)
        assertEquals(Trend.UP, completion.trend)
        assertTrue(review.hasHistory)
    }

    @Test
    fun timeLoggedComparesToTrailingAverage() {
        // Two prior weeks averaging 100 min; this week 300 → +3h 20m.
        val h = mapOf("body" to hist(100))
        val past = listOf(snap("2026-07-13", 3, aspectHistory = h), snap("2026-07-20", 3, aspectHistory = h))
        val review = WeekReviewBuilder.build(closing, past, listOf(aspect("body")))
        val time = review.headline.first { it.label == "Time logged" }
        assertEquals("5h", time.value)
        assertEquals("+3h 20m", time.delta)
        assertEquals(Trend.UP, time.trend)
    }

    @Test
    fun greyStreakCountsConsecutiveZeroWeeksIncludingThisOne() {
        // "mind" logged nothing this week and nothing in the two prior weeks → streak 3.
        val past = listOf(
            snap("2026-07-13", 1, aspectHistory = mapOf("mind" to hist(0), "body" to hist(60))),
            snap("2026-07-20", 1, aspectHistory = mapOf("body" to hist(60))) // mind absent = grey
        )
        val closingNoMind = closing.copy(minutesByAspect = mapOf("body" to 120))
        val review = WeekReviewBuilder.build(closingNoMind, past, listOf(aspect("mind"), aspect("body")))
        val mind = review.aspectBalance.first { it.aspectId == "mind" }
        assertEquals(3, mind.greyStreak)
        val body = review.aspectBalance.first { it.aspectId == "body" }
        assertEquals(0, body.greyStreak)
        assertTrue(review.observations.any { it.contains("mind") && it.contains("grey scar 3") })
    }

    @Test
    fun estimateBiasObservationFiresWhenMostTasksRanOver() {
        val ran = ClosingWeekStats(
            completed = 4, totalRelevant = 4, carried = 0, totalMinutes = 400,
            hardDeadlineHit = 0, hardDeadlineExpired = 0, minutesByAspect = emptyMap(),
            estimatedActuals = listOf(30 to 60, 30 to 70, 30 to 80, 30 to 35) // 3/4 ran long
        )
        val review = WeekReviewBuilder.build(ran, emptyList(), emptyList())
        assertTrue(review.observations.any { it.contains("underestimating") })
        val est = review.headline.first { it.label == "Estimates" }
        assertEquals("1/4 on target", est.value)
    }

    @Test
    fun carryPileObservationWhenCarriedOutweighsDone() {
        val piled = closing.copy(completed = 1, carried = 4)
        val review = WeekReviewBuilder.build(piled, emptyList(), emptyList())
        assertTrue(review.observations.any { it.contains("pile's winning") })
    }

    @Test
    fun noReceiptsObservationWhenTimeIsZeroButThingsCompleted() {
        val noTime = closing.copy(totalMinutes = 0, minutesByAspect = emptyMap())
        val review = WeekReviewBuilder.build(noTime, emptyList(), emptyList())
        assertTrue(review.observations.any { it.contains("without receipts") })
    }

    @Test
    fun observationsCapAtThree() {
        val messy = ClosingWeekStats(
            completed = 0, totalRelevant = 10, carried = 5, totalMinutes = 0,
            hardDeadlineHit = 0, hardDeadlineExpired = 0, minutesByAspect = emptyMap(),
            estimatedActuals = emptyList()
        )
        val greyHistory = mapOf("a" to hist(0), "b" to hist(0), "c" to hist(0))
        val past = List(4) { snap("2026-07-0$it", 5, incomplete = 5, aspectHistory = greyHistory) }
        val review = WeekReviewBuilder.build(
            messy, past,
            listOf(aspect("a"), aspect("b"), aspect("c"))
        )
        assertTrue(review.observations.size <= 3)
    }

    // --- The week's commitment ------------------------------------------------------------------

    @Test
    fun noBarSetMeansNoVerdictAndNoCommitmentMetric() {
        // A week with nothing marked hasn't missed its bar — it never set one. `null`, not `false`.
        val review = WeekReviewBuilder.build(closing, emptyList(), emptyList())
        assertNull(review.commitmentMet)
        assertTrue(review.headline.none { it.label == "Commitment" })
        assertTrue(review.observations.none { it.contains("bar you set") })
    }

    @Test
    fun aClearedBarIsTheWeeksResultNotAnObservation() {
        // The completion rate is still only 70% — the point of the bar is that this week is
        // nonetheless done, and the verdict says so where the three-observation cap can't bury it.
        val met = closing.copy(commitmentTotal = 4, commitmentCompleted = 4)
        val review = WeekReviewBuilder.build(met, emptyList(), emptyList())
        assertEquals(true, review.commitmentMet)
        assertEquals("4/4", review.headline.first { it.label == "Commitment" }.value)
        assertTrue(review.observations.none { it.contains("didn't happen") })
    }

    @Test
    fun aMissedBarIsStatedPlainlyInTheObservations() {
        val missed = closing.copy(commitmentTotal = 5, commitmentCompleted = 3)
        val review = WeekReviewBuilder.build(missed, emptyList(), emptyList())
        assertEquals(false, review.commitmentMet)
        assertTrue(review.observations.any { it.contains("3/5 on the bar you set") })
        assertTrue(review.observations.any { it.contains("2 you called essential didn't happen") })
    }

    @Test
    fun theCommitmentMetricLeadsTheHeadline() {
        val met = closing.copy(commitmentTotal = 3, commitmentCompleted = 3)
        val review = WeekReviewBuilder.build(met, emptyList(), emptyList())
        assertEquals("Commitment", review.headline.first().label)
    }

    @Test
    fun commitmentDeltaComparesBarToBarNotToCompletionRate() {
        // Last week: 2/4 = 50% of its bar. This week 3/4 = 75% → +25pp.
        val last = snap("2026-07-20", completed = 9, commitmentTotal = 4, commitmentCompleted = 2)
        val thisWeek = closing.copy(commitmentTotal = 4, commitmentCompleted = 3)
        val review = WeekReviewBuilder.build(thisWeek, listOf(last), emptyList())
        val metric = review.headline.first { it.label == "Commitment" }
        assertEquals("+25pp", metric.delta)
        assertEquals(Trend.UP, metric.trend)
    }

    @Test
    fun noDeltaAgainstAWeekThatSetNoBar() {
        // A week closed before commitments existed seals 0/0. Comparing against it would read as a
        // collapse from a bar that was never set, so there's simply no delta.
        val preFeature = snap("2026-07-20", completed = 9)
        val thisWeek = closing.copy(commitmentTotal = 4, commitmentCompleted = 2)
        val review = WeekReviewBuilder.build(thisWeek, listOf(preFeature), emptyList())
        assertNull(review.headline.first { it.label == "Commitment" }.delta)
    }

    @Test
    fun markingMostOfTheListAsEssentialIsCalledOut() {
        // 8 of 10 marked: the flag has stopped selecting anything, which is said *before* the
        // hit/miss line because it changes what that line is worth.
        val overMarked = closing.copy(totalRelevant = 10, commitmentTotal = 8, commitmentCompleted = 8)
        val review = WeekReviewBuilder.build(overMarked, emptyList(), emptyList())
        val first = review.observations.first()
        assertTrue(first.contains("8 of 10 tasks marked essential"))
        assertTrue(first.contains("that's the list"))
    }

    @Test
    fun aSmallWeekIsNotAccusedOfOverMarking() {
        // 3 of 4 is a light week, not an over-marked one — the ratio needs a real week to mean
        // anything, so the line stays silent below the size floor.
        val small = closing.copy(totalRelevant = 4, commitmentTotal = 3, commitmentCompleted = 3)
        val review = WeekReviewBuilder.build(small, emptyList(), emptyList())
        assertTrue(review.observations.none { it.contains("that's the list") })
    }

    @Test
    fun aMissedBarSuppressesTheDryNodForAClimbingCompletionRate() {
        // Completion climbed 30% → 70%, which alone earns the nod. It doesn't get one: essential
        // work went undone, and congratulating the climb would be the mirror flattering.
        val last = snap("2026-07-20", completed = 3, incomplete = 7)
        val missed = closing.copy(commitmentTotal = 3, commitmentCompleted = 1)
        val review = WeekReviewBuilder.build(missed, listOf(last), emptyList())
        assertTrue(review.observations.none { it.contains("Keep it") })
        assertTrue(review.observations.any { it.contains("didn't happen") })
    }

    @Test
    fun archivedAspectsAreExcludedFromBalance() {
        val review = WeekReviewBuilder.build(
            closing, emptyList(),
            listOf(aspect("body"), aspect("old").copy(isArchived = true))
        )
        assertTrue(review.aspectBalance.none { it.aspectId == "old" })
    }
}
