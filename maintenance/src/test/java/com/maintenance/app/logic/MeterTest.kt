package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeterTest {

    private val day = 86_400_000L
    private val start = 1_700_000_000_000L

    private fun reading(days: Long, value: Long) = MeterReading(start + days * day, value)

    @Test
    fun `two readings give a rate`() {
        val readings = listOf(reading(0, 10_000), reading(100, 11_000))

        assertEquals(10.0, Meter.perDay(readings)!!, 0.0001)
        assertEquals(11_000L, Meter.latest(readings)!!.value)
    }

    @Test
    fun `one reading gives no rate rather than a made-up one`() {
        assertNull(Meter.perDay(listOf(reading(0, 10_000))))
        assertNull(Meter.perDay(emptyList()))
        // Two readings on the same day cannot say anything about a day either.
        assertNull(Meter.perDay(listOf(reading(0, 10_000), reading(0, 10_050))))
    }

    @Test
    fun `a meter that went backwards is measured from where it restarted`() {
        // A cluster replaced at day 10: everything before the drop is dropped.
        val readings = listOf(reading(0, 220_000), reading(10, 0), reading(20, 400))

        assertEquals(listOf(0L, 400L), Meter.usable(readings).map { it.value })
        assertEquals(40.0, Meter.perDay(readings)!!, 0.0001)
    }

    @Test
    fun `a rate turns an interval into a date`() {
        val readings = listOf(reading(0, 10_000), reading(100, 11_000))

        assertEquals(start + 200 * day, Meter.projectDate(readings, 12_000))
        assertEquals(12_000L, Meter.projectValue(readings, start + 200 * day))
    }

    @Test
    fun `a target already passed is due now, not dated in the past`() {
        val readings = listOf(reading(0, 10_000), reading(100, 11_000))

        assertEquals(start + 100 * day, Meter.projectDate(readings, 10_500))
    }

    @Test
    fun `without a rate there is no date, but the last reading is still the truth`() {
        val single = listOf(reading(0, 10_000))

        assertNull(Meter.projectDate(single, 12_000))
        assertEquals(10_000L, Meter.projectValue(single, start + 100 * day))
    }

    @Test
    fun `distance covered in a window is what cost-per-mile divides by`() {
        val readings = listOf(reading(0, 10_000), reading(50, 10_800), reading(100, 11_000))

        assertEquals(1_000L, Meter.travelled(readings, start, start + 100 * day))
        assertEquals(800L, Meter.travelled(readings, start, start + 60 * day))
        assertNull(Meter.travelled(readings, start, start + 10 * day))
    }
}
