package com.citation.core.rr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateBudgetTest {

    private val hour = 60L * 60 * 1000

    /** A mutable clock so we can drive time deterministically. */
    private class FakeClock(var now: Long) { fun read(): Long = now }

    @Test
    fun enforcesPerHourCeiling() {
        val clock = FakeClock(0)
        val budget = RateBudget(maxPerHour = 5, maxPerDay = 100, clock = clock::read)
        repeat(5) { assertTrue(budget.tryAcquire()) }
        assertFalse("6th within the hour must be blocked", budget.tryAcquire())
        assertEquals(5, budget.usedThisHour())
    }

    @Test
    fun hourSlotFreesUpAsHitsAgeOut() {
        val clock = FakeClock(0)
        val budget = RateBudget(maxPerHour = 2, maxPerDay = 100, clock = clock::read)
        assertTrue(budget.tryAcquire()) // t=0
        clock.now = 10 * 60 * 1000
        assertTrue(budget.tryAcquire()) // t=10m
        assertFalse(budget.tryAcquire()) // blocked: 2 in the last hour
        // Advance just past the first hit's hour window → one slot frees.
        clock.now = hour + 1
        assertTrue(budget.tryAcquire())
    }

    @Test
    fun enforcesDailyBackstopEvenWhenHourlyWouldAllow() {
        val clock = FakeClock(0)
        val budget = RateBudget(maxPerHour = 100, maxPerDay = 3, clock = clock::read)
        // Spread across hours so the hourly ceiling never bites, but the daily cap does.
        assertTrue(budget.tryAcquire()); clock.now += 2 * hour
        assertTrue(budget.tryAcquire()); clock.now += 2 * hour
        assertTrue(budget.tryAcquire()); clock.now += 2 * hour
        assertFalse("daily backstop reached", budget.tryAcquire())
    }

    @Test
    fun nextAvailablePointsToWhenAnHourSlotOpens() {
        val clock = FakeClock(0)
        val budget = RateBudget(maxPerHour = 1, maxPerDay = 100, clock = clock::read)
        assertTrue(budget.tryAcquire()) // t=0
        assertEquals(hour, budget.nextAvailable()) // the single hit ages out at t=hour
    }

    @Test
    fun feedsGetALooserBudgetThanScrapes() {
        val clock = FakeClock(0)
        val feeds = RateBudget(maxPerHour = 60, maxPerDay = 500, clock = clock::read)
        val scrapes = RateBudget(maxPerHour = 6, maxPerDay = 60, clock = clock::read)
        repeat(6) { assertTrue(scrapes.tryAcquire()) }
        assertFalse(scrapes.tryAcquire())        // scrapes throttled at 6/hr
        repeat(20) { assertTrue(feeds.tryAcquire()) } // feeds stay loose
    }
}
