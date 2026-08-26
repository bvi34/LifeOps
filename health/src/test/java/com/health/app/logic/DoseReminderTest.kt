package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a reminder next fires. The awkward cases are the point: a time that has already passed today,
 * a dose window that closed while the phone was asleep, and a medicine nobody has taken for a week.
 */
class DoseReminderTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(hour: Int, minute: Int = 0, day: Int = 10): Long =
        ZonedDateTime.of(2026, 3, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `the next set time today is the one that fires`() {
        val next = DoseReminder.nextFixedTime(
            times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
            nowMillis = at(9),
            zone = zone
        )
        assertEquals(at(20), next)
    }

    @Test
    fun `once today's times are past it rolls to tomorrow's first`() {
        val next = DoseReminder.nextFixedTime(
            times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
            nowMillis = at(21),
            zone = zone
        )
        assertEquals(at(8, day = 11), next)
    }

    @Test
    fun `a time exactly now is treated as past, not as firing twice`() {
        val next = DoseReminder.nextFixedTime(
            times = listOf(LocalTime.of(8, 0)),
            nowMillis = at(8),
            zone = zone
        )
        assertEquals(at(8, day = 11), next)
    }

    @Test
    fun `no times means nothing to schedule`() {
        assertNull(DoseReminder.nextFixedTime(emptyList(), at(9), zone))
    }

    @Test
    fun `a when-due reminder fires when the label's spacing elapses`() {
        val now = at(9)
        val window = window(lastDoseAt = at(8), nextAllowedAt = at(12))
        assertEquals(at(12), DoseReminder.nextWhenDue(window, now))
    }

    @Test
    fun `nothing is scheduled before the first dose is recorded`() {
        // No dose given means no outstanding dose to be reminded about.
        val window = window(lastDoseAt = null, nextAllowedAt = null)
        assertNull(DoseReminder.nextWhenDue(window, at(9)))
    }

    @Test
    fun `an already-due dose schedules nothing — the caller fires now instead`() {
        val window = window(lastDoseAt = at(4), nextAllowedAt = null)
        assertNull(DoseReminder.nextWhenDue(window, at(9)))
    }

    @Test
    fun `a wait past the horizon lapses rather than nagging`() {
        // A medicine last given a week ago has been stopped, not forgotten. Firing into that silence
        // teaches people to ignore the reminders that matter.
        val now = at(9)
        val window = window(lastDoseAt = at(8), nextAllowedAt = now + DoseReminder.WHEN_DUE_HORIZON_MS + 1)
        assertNull(DoseReminder.nextWhenDue(window, now))
    }

    @Test
    fun `a slightly late wake-up still notifies`() {
        val target = at(12)
        val window = window(lastDoseAt = at(8), nextAllowedAt = null, status = DoseStatus.READY)
        assertTrue(DoseReminder.shouldFireWhenDue(window, target + 20 * 60_000L, target))
    }

    @Test
    fun `a hopelessly late wake-up stays quiet`() {
        val target = at(12)
        val window = window(lastDoseAt = at(8), nextAllowedAt = null, status = DoseStatus.READY)
        assertFalse(
            DoseReminder.shouldFireWhenDue(
                window,
                target + DoseReminder.LATE_TOLERANCE_MS + 1,
                target
            )
        )
    }

    @Test
    fun `a window re-armed by someone else's dose stays quiet`() {
        // The other parent gave a dose from their own phone; by the time this worker ran, the window
        // was no longer ready. The schedule it was queued under is not the authority — the window is.
        val target = at(12)
        val window = window(lastDoseAt = at(11), nextAllowedAt = at(15), status = DoseStatus.WAIT)
        assertFalse(DoseReminder.shouldFireWhenDue(window, target, target))
    }

    @Test
    fun `times round-trip through storage in a stable, sorted form`() {
        val parsed = DoseReminder.parseTimes("20:00, 8:5,08:05")
        assertEquals(listOf(LocalTime.of(8, 5), LocalTime.of(20, 0)), parsed)
        assertEquals("08:05,20:00", DoseReminder.formatTimes(parsed))
    }

    @Test
    fun `an unparseable time is dropped rather than defaulted`() {
        // A reminder at a time nobody chose is worse than one that doesn't fire.
        assertEquals(listOf(LocalTime.of(8, 0)), DoseReminder.parseTimes("08:00,25:00,noon,,8"))
        assertEquals(emptyList<LocalTime>(), DoseReminder.parseTimes(null))
    }

    @Test
    fun `a schedule reads like speech`() {
        assertEquals("08:00 and 20:00", DoseReminder.describeTimes(DoseReminder.parseTimes("20:00,08:00")))
        assertEquals("08:00", DoseReminder.describeTimes(DoseReminder.parseTimes("08:00")))
        assertEquals(
            "06:00, 12:00 and 18:00",
            DoseReminder.describeTimes(DoseReminder.parseTimes("06:00,12:00,18:00"))
        )
        assertEquals("No times set", DoseReminder.describeTimes(emptyList()))
    }

    @Test
    fun `the mode key survives an unknown value from an older row`() {
        assertEquals(ReminderMode.WHEN_DUE, ReminderMode.fromKey("when_due"))
        assertEquals(ReminderMode.OFF, ReminderMode.fromKey("something_else"))
        assertEquals(ReminderMode.OFF, ReminderMode.fromKey(null))
    }

    private fun window(
        lastDoseAt: Long?,
        nextAllowedAt: Long?,
        status: DoseStatus = if (nextAllowedAt == null) DoseStatus.READY else DoseStatus.WAIT
    ) = DoseWindow(
        status = status,
        nextAllowedAtMillis = nextAllowedAt,
        lastDoseAtMillis = lastDoseAt,
        dosesInWindow = if (lastDoseAt == null) 0 else 1,
        amountInWindow = 0.0,
        dosesRemaining = null,
        reason = ""
    )
}
