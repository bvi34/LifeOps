package com.lifeops.app.util

import com.lifeops.app.data.model.PhoneActivityEvent
import com.lifeops.app.data.model.PhoneActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepInferenceTest {

    // windowStart stands in for "18:00 the previous evening"; all times below are offsets from it.
    private val windowStart = 1_700_000_000_000L
    private fun at(hours: Double): Long = windowStart + (hours * 60L * 60L * 1000L).toLong()
    private fun min(m: Double): Long = (m * 60L * 1000L).toLong()

    private var seq = 0
    private fun ev(type: PhoneActivityType, at: Long) =
        PhoneActivityEvent(id = "e${seq++}", type = type, occurredAt = at, dayKey = "d")

    private fun on(hours: Double) = ev(PhoneActivityType.SCREEN_ON, at(hours))
    private fun off(hours: Double) = ev(PhoneActivityType.SCREEN_OFF, at(hours))
    private fun reconstruct(events: List<PhoneActivityEvent>, nowHours: Double) =
        SleepInferenceService.reconstruct(events, windowStart, at(nowHours))

    @Test
    fun `clean night maps screen-off span to sleep`() {
        // Screen off at 23:00 (h=5), back on at 07:00 (h=13). No interruptions.
        val events = listOf(off(5.0), on(13.0))
        val r = reconstruct(events, nowHours = 13.0)
        assertNotNull(r)
        r!!
        assertEquals(at(5.0), r.bedtimeMillis)
        assertEquals(at(13.0), r.wakeMillis)
        assertEquals(8 * 60, r.totalSleepMinutes)
        assertEquals(0, r.interruptions)
        assertEquals(8 * 60, r.longestSleepMinutes)
    }

    @Test
    fun `sub-minute screen blip is merged away, not counted`() {
        // A 30-second glance at 02:00 should vanish into continuous sleep.
        val events = listOf(
            off(5.0),                                   // 23:00 bed
            on(8.0),                                    // 02:00 glance on
            ev(PhoneActivityType.SCREEN_OFF, at(8.0) + min(0.5)), // 02:00:30 back off
            on(13.0)                                    // 07:00 up
        )
        val r = reconstruct(events, nowHours = 13.0)
        assertNotNull(r)
        r!!
        assertEquals(0, r.interruptions)
        assertEquals(at(5.0), r.bedtimeMillis)
        assertEquals(at(13.0), r.wakeMillis)
        // Full 8h span is sleep (the blip is absorbed).
        assertEquals(8 * 60, r.totalSleepMinutes)
        assertEquals(8 * 60, r.longestSleepMinutes)
    }

    @Test
    fun `a real 10-minute wake-up counts as one interruption`() {
        val events = listOf(
            off(5.0),   // 23:00 bed
            on(9.0),    // 03:00 up
            off(9.0 + 10.0 / 60.0), // 03:10 back to bed (10 min awake)
            on(13.0)    // 07:00 up for good
        )
        val r = reconstruct(events, nowHours = 13.0)
        assertNotNull(r)
        r!!
        assertEquals(1, r.interruptions)
        assertEquals(at(5.0), r.bedtimeMillis)
        assertEquals(at(13.0), r.wakeMillis)
        // 8h span minus the 10-minute interruption.
        assertEquals(8 * 60 - 10, r.totalSleepMinutes)
        // Longest uninterrupted is the first block, 23:00 -> 03:00 = 4h.
        assertEquals(4 * 60, r.longestSleepMinutes)
    }

    @Test
    fun `evening phone use is not absorbed into the night`() {
        // Brief use 20:30 -> 21:00 then a 2h awake gap before real bedtime at 23:00.
        val events = listOf(
            off(2.5),   // 20:30 down
            on(3.0),    // 21:00 up (evening use)
            off(5.0),   // 23:00 bed
            on(13.0)    // 07:00 up
        )
        val r = reconstruct(events, nowHours = 13.0)
        assertNotNull(r)
        r!!
        // The 2h gap exceeds the max interruption, so bedtime stays at 23:00.
        assertEquals(at(5.0), r.bedtimeMillis)
        assertEquals(0, r.interruptions)
        assertEquals(8 * 60, r.totalSleepMinutes)
    }

    @Test
    fun `no events yields null so caller can fall back`() {
        assertNull(reconstruct(emptyList(), nowHours = 13.0))
    }

    @Test
    fun `a too-short off span is not treated as a night`() {
        // Only a 30-minute screen-off block — below the 2h minimum session.
        val events = listOf(off(5.0), on(5.5))
        assertNull(reconstruct(events, nowHours = 13.0))
    }

    @Test
    fun `still-asleep window (no morning screen-on yet) reconstructs up to now`() {
        // Bed at 23:00, opened the app / screen on isn't recorded — trailing off runs to now (06:00).
        val events = listOf(off(5.0))
        val r = reconstruct(events, nowHours = 12.0)
        assertNotNull(r)
        r!!
        assertEquals(at(5.0), r.bedtimeMillis)
        assertEquals(at(12.0), r.wakeMillis)
        assertEquals(7 * 60, r.totalSleepMinutes)
    }

    @Test
    fun `two interruptions are both counted and longest stretch is the biggest gap`() {
        val events = listOf(
            off(5.0),                       // 23:00 bed
            on(7.0), off(7.0 + 5.0 / 60.0), // 01:00 up 5 min
            on(10.0), off(10.0 + 5.0 / 60.0), // 04:00 up 5 min
            on(13.0)                        // 07:00 up
        )
        val r = reconstruct(events, nowHours = 13.0)
        assertNotNull(r)
        r!!
        assertEquals(2, r.interruptions)
        assertEquals(at(5.0), r.bedtimeMillis)
        assertEquals(at(13.0), r.wakeMillis)
        // Longest uninterrupted block is 01:05 -> 04:00 ~= 2h55m.
        assertTrue(r.longestSleepMinutes in (170..180))
        // Total ~= 8h span minus two 5-minute wakes.
        assertEquals(8 * 60 - 10, r.totalSleepMinutes)
    }
}
