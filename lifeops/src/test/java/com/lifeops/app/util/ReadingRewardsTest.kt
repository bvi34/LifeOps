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
