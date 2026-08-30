package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VinTest {

    /** A real, well-formed VIN: a 2003 Honda Accord's, check digit and all. */
    private val honda = "1HGCM82633A004352"

    @Test
    fun `a well-formed VIN passes and keeps its own check digit`() {
        assertNull(Vin.problem(honda))
        assertTrue(Vin.isValid(honda))
        assertEquals('3', Vin.checkDigit(honda))
        assertEquals("1HG", Vin.wmi(honda))
    }

    @Test
    fun `it is read the way it is written out loud - spaces, hyphens and lower case`() {
        assertEquals(honda, Vin.normalise("1hg cm826-33a 004352"))
        assertTrue(Vin.isValid("1hg cm826-33a 004352"))
    }

    @Test
    fun `blank is not a complaint`() {
        assertNull(Vin.problem(""))
        assertNull(Vin.problem("   "))
    }

    @Test
    fun `the two typos a VIN can catch by itself`() {
        assertEquals(Vin.Problem.LENGTH, Vin.problem("1HGCM82633A00435"))
        assertEquals(Vin.Problem.ILLEGAL_CHARACTER, Vin.problem("1HGCM82633A0O4352"))
    }

    @Test
    fun `a check digit that disagrees is reported, not rejected`() {
        // Well-formed and 17 long, but the ninth character isn't what the other sixteen imply.
        val mistyped = "5YJ3E1EA7JF006588"

        assertEquals(Vin.Problem.CHECK_DIGIT, Vin.problem(mistyped))
        assertFalse(Vin.isValid(mistyped))
        // Still a VIN the app will store and show — the point of grading the problem rather than
        // refusing the value.
        assertEquals('X', Vin.checkDigit(mistyped))
    }

    @Test
    fun `the model year resolves against the year you are standing in`() {
        assertEquals(2003, Vin.modelYear(honda, currentYear = 2026))
        // 'K' is 1989 and 2019; the most recent one that isn't in the future wins.
        assertEquals(2019, Vin.modelYear("1M8GDM9AXKP042788", currentYear = 2026))
        // Next year's models are sold this year, so one year of lead is allowed.
        assertEquals(2019, Vin.modelYear("1M8GDM9AXKP042788", currentYear = 2018))
        assertEquals(1989, Vin.modelYear("1M8GDM9AXKP042788", currentYear = 2017))
    }

    @Test
    fun `nothing is decoded from a VIN that is not one`() {
        assertNull(Vin.modelYear("TOO-SHORT", currentYear = 2026))
        assertNull(Vin.wmi("TOO-SHORT"))
        assertNull(Vin.checkDigit("TOO-SHORT"))
    }
}
