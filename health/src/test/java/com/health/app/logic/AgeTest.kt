package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AgeTest {

    private val utc = ZoneId.of("UTC")

    /** Midnight UTC on a given day, so the assertions don't depend on where the test runs. */
    private fun at(date: String): Long =
        LocalDate.parse(date).atStartOfDay(utc).toInstant().toEpochMilli()

    @Test
    fun `months and years are whole, not rounded`() {
        assertEquals(2, Age.monthsAt("2026-06-20", at("2026-08-25"), utc))
        assertEquals(0, Age.yearsAt("2026-06-20", at("2026-08-25"), utc))
        assertEquals(34, Age.yearsAt("1992-01-04", at("2026-08-25"), utc))
    }

    @Test
    fun `an unknown or impossible birth date is null, never zero`() {
        assertNull(Age.monthsAt(null, at("2026-08-25"), utc))
        assertNull(Age.monthsAt("", at("2026-08-25"), utc))
        assertNull(Age.monthsAt("not a date", at("2026-08-25"), utc))
        // A birth date in the future is a typo, and "0 months old" would be a dangerous reading of it.
        assertNull(Age.monthsAt("2027-01-01", at("2026-08-25"), utc))
    }

    @Test
    fun `the label uses the unit people use at that age`() {
        assertEquals("12 days", Age.describe("2026-08-13", at("2026-08-25"), utc))
        assertEquals("6 mo", Age.describe("2026-02-25", at("2026-08-25"), utc))
        assertEquals("3 yr", Age.describe("2023-08-25", at("2026-08-25"), utc))
        assertNull(Age.describe(null, at("2026-08-25"), utc))
    }
}
