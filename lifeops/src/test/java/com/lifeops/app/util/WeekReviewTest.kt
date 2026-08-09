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
        aspectHistory: Map<String, AspectHistoryEntry> = emptyMap()
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
        aspectHistory = aspectHistory
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

    @Test
    fun archivedAspectsAreExcludedFromBalance() {
        val review = WeekReviewBuilder.build(
            closing, emptyList(),
            listOf(aspect("body"), aspect("old").copy(isArchived = true))
        )
        assertTrue(review.aspectBalance.none { it.aspectId == "old" })
    }
}
