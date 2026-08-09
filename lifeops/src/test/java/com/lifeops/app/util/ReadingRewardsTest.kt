package com.lifeops.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingRewardsTest {

    @Test
    fun royalRoadIsFunEverythingElseIsLearning() {
        assertEquals(ReadingCategory.FUN, ReadingRewards.defaultCategory("ROYAL_ROAD"))
        assertEquals(ReadingCategory.FUN, ReadingRewards.defaultCategory("AO3"))
        assertEquals(ReadingCategory.LEARNING, ReadingRewards.defaultCategory("OREILLY"))
        assertEquals(ReadingCategory.LEARNING, ReadingRewards.defaultCategory("EPUB"))
        assertEquals(ReadingCategory.LEARNING, ReadingRewards.defaultCategory("PDF"))
    }

    @Test
    fun categoryMatchIsCaseAndWhitespaceInsensitive() {
        assertEquals(ReadingCategory.FUN, ReadingRewards.defaultCategory("  royal_road "))
    }

    @Test
    fun unknownSourceDefaultsToLearning() {
        assertEquals(ReadingCategory.LEARNING, ReadingRewards.defaultCategory("something_new"))
    }

    @Test
    fun pointsAreFlatFivePerHourByDefault() {
        assertEquals(5, ReadingRewards.points(60))
        assertEquals(10, ReadingRewards.points(120))
    }

    @Test
    fun pointsFloorFractionalHours() {
        // 30 min @5/hr = 2.5 → 2; 250 min @5/hr = 20.83 → 20.
        assertEquals(2, ReadingRewards.points(30))
        assertEquals(20, ReadingRewards.points(250))
    }

    @Test
    fun subRateReadingEarnsZeroUntilItAddsUp() {
        // 11 min @5/hr = 0.9 pts → 0. The remainder isn't lost because callers pass weekly totals.
        assertEquals(0, ReadingRewards.points(11))
        assertEquals(1, ReadingRewards.points(12)) // 12 * 5 / 60 = 1
    }

    @Test
    fun readingIsCumulativeNotSingleSession() {
        // The intent: six 10-minute sittings earn exactly what one unbroken hour does — points key
        // off the week's summed engaged minutes, not any single session's length. Callers sum first
        // (BookDao.sumReadingMinutesBetween) then floor once here, so no sitting is wasted.
        val sixTenMinuteSessions = List(6) { 10 }
        assertEquals(5, ReadingRewards.points(sixTenMinuteSessions.sum())) // 60 min → 5 pts
        assertEquals(ReadingRewards.points(60), ReadingRewards.points(sixTenMinuteSessions.sum()))
        // Flooring per-session instead would have wasted every sub-12-minute sitting → 0 pts.
        assertEquals(0, sixTenMinuteSessions.sumOf { ReadingRewards.points(it) })
    }

    @Test
    fun customRateApplies() {
        assertEquals(20, ReadingRewards.points(60, pointsPerHour = 20))
    }

    @Test
    fun nonPositiveInputsEarnNothing() {
        assertEquals(0, ReadingRewards.points(0))
        assertEquals(0, ReadingRewards.points(-30))
        assertEquals(0, ReadingRewards.points(60, pointsPerHour = 0))
    }
}
