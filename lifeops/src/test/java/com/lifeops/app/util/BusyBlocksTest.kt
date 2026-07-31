package com.lifeops.app.util

import com.lifeops.app.data.model.BusyBlock
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class BusyBlocksTest {

    private fun weekly(days: Int, start: Int, end: Int) =
        BusyBlock(id = "w", title = "Work", startMinutes = start, endMinutes = end, daysMask = days, createdAt = "")

    private fun oneOff(date: String, start: Int, end: Int) =
        BusyBlock(id = "o", title = "Appt", startMinutes = start, endMinutes = end, daysMask = 0, specificDate = date, createdAt = "")

    // bit 0 = Monday … bit 6 = Sunday
    private val MON = 1 shl 0
    private val FRI = 1 shl 4
    private val WEEKDAYS = (0..4).fold(0) { acc, d -> acc or (1 shl d) }

    @Test
    fun `weekly block occurs only on its weekdays`() {
        val block = weekly(WEEKDAYS, 540, 1020) // Mon–Fri 09:00–17:00
        assertTrue(BusyBlocks.occursOn(block, LocalDate.parse("2026-07-20"))) // Monday
        assertTrue(BusyBlocks.occursOn(block, LocalDate.parse("2026-07-24"))) // Friday
        assertFalse(BusyBlocks.occursOn(block, LocalDate.parse("2026-07-25"))) // Saturday
    }

    @Test
    fun `single-day mask matches only that weekday`() {
        val block = weekly(MON, 540, 600)
        assertTrue(BusyBlocks.occursOn(block, LocalDate.parse("2026-07-20"))) // Monday
        assertFalse(BusyBlocks.occursOn(block, LocalDate.parse("2026-07-21"))) // Tuesday
        assertFalse(BusyBlocks.occursOn(weekly(FRI, 540, 600), LocalDate.parse("2026-07-20")))
    }

    @Test
    fun `one-off block matches only its date`() {
        val block = oneOff("2026-07-22", 600, 660)
        assertTrue(BusyBlocks.occursOn(block, LocalDate.parse("2026-07-22")))
        assertFalse(BusyBlocks.occursOn(block, LocalDate.parse("2026-07-23")))
    }

    @Test
    fun `isBusy detects an overlapping window and clears a disjoint one`() {
        val work = listOf(weekly(WEEKDAYS, 540, 1020)) // 09:00–17:00
        // Monday 10:00–11:00 → inside work hours.
        assertTrue(
            BusyBlocks.isBusy(work, LocalDateTime.parse("2026-07-20T10:00"), LocalDateTime.parse("2026-07-20T11:00"))
        )
        // Monday 18:00–19:00 → after work.
        assertFalse(
            BusyBlocks.isBusy(work, LocalDateTime.parse("2026-07-20T18:00"), LocalDateTime.parse("2026-07-20T19:00"))
        )
        // Saturday 10:00–11:00 → not a work day.
        assertFalse(
            BusyBlocks.isBusy(work, LocalDateTime.parse("2026-07-25T10:00"), LocalDateTime.parse("2026-07-25T11:00"))
        )
    }

    @Test
    fun `touching-at-the-boundary is not a conflict`() {
        val work = listOf(weekly(WEEKDAYS, 540, 1020))
        // Window starts exactly at 17:00 when work ends → half-open, no overlap.
        assertFalse(
            BusyBlocks.isBusy(work, LocalDateTime.parse("2026-07-20T17:00"), LocalDateTime.parse("2026-07-20T18:00"))
        )
    }
}
