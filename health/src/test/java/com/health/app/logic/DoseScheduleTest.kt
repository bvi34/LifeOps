package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseScheduleTest {

    private val now = 1_700_000_000_000L
    private fun hoursAgo(h: Double) = now - (h * 60 * 60 * 1000).toLong()

    private val childParacetamol = MedicationRule(
        name = "Paracetamol",
        doseAmount = 5.0,
        doseUnit = "mL",
        minIntervalHours = 4.0,
        maxDosesPer24h = 4
    )

    @Test
    fun `no history means ready, and says so plainly`() {
        val window = DoseSchedule.evaluate(childParacetamol, emptyList(), now)
        assertEquals(DoseStatus.READY, window.status)
        assertNull(window.nextAllowedAtMillis)
        assertNull(window.lastDoseAtMillis)
        assertEquals("No dose recorded yet.", window.reason)
    }

    @Test
    fun `inside the interval it waits, and reports when`() {
        val window = DoseSchedule.evaluate(childParacetamol, listOf(DoseRecord(hoursAgo(1.5), 5.0)), now)
        assertEquals(DoseStatus.WAIT, window.status)
        assertEquals(hoursAgo(1.5) + 4 * 60 * 60 * 1000, window.nextAllowedAtMillis)
        assertEquals(2 * 60 * 60 * 1000L + 30 * 60 * 1000, window.waitMillis(now))
        assertEquals(3, window.dosesRemaining)
    }

    @Test
    fun `once the interval has elapsed it is ready again`() {
        val window = DoseSchedule.evaluate(childParacetamol, listOf(DoseRecord(hoursAgo(4.5), 5.0)), now)
        assertEquals(DoseStatus.READY, window.status)
        assertEquals("Due now.", window.reason)
    }

    @Test
    fun `a spent daily allowance blocks even when the interval has elapsed`() {
        // Four doses, the last one five hours ago: spacing is fine, the day's allowance is not.
        val history = listOf(
            DoseRecord(hoursAgo(20.0), 5.0),
            DoseRecord(hoursAgo(15.0), 5.0),
            DoseRecord(hoursAgo(10.0), 5.0),
            DoseRecord(hoursAgo(5.0), 5.0)
        )
        val window = DoseSchedule.evaluate(childParacetamol, history, now)
        assertEquals(DoseStatus.LIMIT_REACHED, window.status)
        assertEquals(0, window.dosesRemaining)
        // Released only when the oldest of the four leaves the rolling window.
        assertEquals(hoursAgo(20.0) + DoseSchedule.WINDOW_MS, window.nextAllowedAtMillis)
        assertTrue(window.reason.contains("4 of 4"))
    }

    @Test
    fun `the rolling window is a rolling window, not since-midnight`() {
        // Three doses inside 24h and one that has just aged out: the old one must not be counted.
        val history = listOf(
            DoseRecord(hoursAgo(25.0), 5.0),
            DoseRecord(hoursAgo(18.0), 5.0),
            DoseRecord(hoursAgo(12.0), 5.0),
            DoseRecord(hoursAgo(6.0), 5.0)
        )
        val window = DoseSchedule.evaluate(childParacetamol, history, now)
        assertEquals(DoseStatus.READY, window.status)
        assertEquals(3, window.dosesInWindow)
        assertEquals(1, window.dosesRemaining)
    }

    @Test
    fun `an amount cap blocks the dose that would breach it`() {
        val ibuprofen = MedicationRule(
            name = "Ibuprofen",
            doseAmount = 400.0,
            doseUnit = "mg",
            minIntervalHours = 6.0,
            maxAmountPer24h = 1200.0
        )
        val history = listOf(
            DoseRecord(hoursAgo(20.0), 400.0),
            DoseRecord(hoursAgo(13.0), 400.0),
            DoseRecord(hoursAgo(7.0), 400.0)
        )
        val window = DoseSchedule.evaluate(ibuprofen, history, now)
        assertEquals(DoseStatus.LIMIT_REACHED, window.status)
        assertEquals(1200.0, window.amountInWindow, 0.001)
        // The next 400 fits as soon as the first 400 ages out.
        assertEquals(hoursAgo(20.0) + DoseSchedule.WINDOW_MS, window.nextAllowedAtMillis)
        assertTrue(window.reason.contains("1200"))
    }

    @Test
    fun `the later of the two gates wins`() {
        // Allowance frees in 4h; the interval frees in 5.5h. The answer is 5.5h.
        val rule = MedicationRule(
            name = "Paracetamol",
            doseAmount = 5.0,
            doseUnit = "mL",
            minIntervalHours = 6.0,
            maxDosesPer24h = 2
        )
        val history = listOf(DoseRecord(hoursAgo(20.0), 5.0), DoseRecord(hoursAgo(0.5), 5.0))
        val window = DoseSchedule.evaluate(rule, history, now)
        assertEquals(hoursAgo(0.5) + 6 * 60 * 60 * 1000, window.nextAllowedAtMillis)
        assertEquals(DoseStatus.LIMIT_REACHED, window.status)
    }

    @Test
    fun `a rule with no limits never blocks`() {
        val vitamin = MedicationRule(name = "Vitamin D", doseAmount = 1.0, doseUnit = "drop")
        val window = DoseSchedule.evaluate(vitamin, listOf(DoseRecord(hoursAgo(0.1), 1.0)), now)
        assertEquals(DoseStatus.READY, window.status)
        assertNull(window.dosesRemaining)
    }

    @Test
    fun `a future-dated row is not treated as a dose already given`() {
        val history = listOf(DoseRecord(now + 60 * 60 * 1000, 5.0))
        val window = DoseSchedule.evaluate(childParacetamol, history, now)
        assertEquals(DoseStatus.READY, window.status)
        assertEquals(0, window.dosesInWindow)
    }

    @Test
    fun `countdowns read the way a person would say them`() {
        assertEquals("now", DoseSchedule.formatDuration(0))
        assertEquals("45m", DoseSchedule.formatDuration(45 * 60 * 1000))
        assertEquals("2h", DoseSchedule.formatDuration(2 * 60 * 60 * 1000))
        assertEquals("3h 20m", DoseSchedule.formatDuration(3 * 60 * 60 * 1000 + 20 * 60 * 1000))
        assertEquals("just now", DoseSchedule.formatAgo(5_000))
    }
}
