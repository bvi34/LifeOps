package com.operations.backupkit.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The awkward cases of "is an archive owed", which are the same ones the updater's schedule has. */
class CloudBackupScheduleTest {

    private val day = CloudBackupFrequency.DAILY.intervalMs
    private val now = 1_760_000_000_000L

    private fun due(enabled: Boolean = true, lastRunAt: Long, at: Long = now, interval: Long = day) =
        CloudBackupSchedule.isDue(enabled, lastRunAt, at, interval)

    @Test
    fun `switched off is never due`() {
        assertFalse(due(enabled = false, lastRunAt = 0L))
        assertFalse(due(enabled = false, lastRunAt = now - 10 * day))
    }

    @Test
    fun `the first archive is owed immediately`() {
        assertTrue(due(lastRunAt = 0L))
    }

    @Test
    fun `an interval that has not elapsed is not due`() {
        assertFalse(due(lastRunAt = now - day / 2))
    }

    @Test
    fun `a wake-up a few minutes early still counts as this interval's run`() {
        assertTrue(due(lastRunAt = now - day + CloudBackupSchedule.EARLY_TOLERANCE_MS))
        assertFalse(due(lastRunAt = now - day + CloudBackupSchedule.EARLY_TOLERANCE_MS + 1))
    }

    @Test
    fun `a clock moved backwards does not suspend backups until it catches up`() {
        assertTrue(due(lastRunAt = now + 30 * day))
    }

    @Test
    fun `the next run is one interval after the last one`() {
        assertEquals(now - day / 2 + day, CloudBackupSchedule.nextRunAt(now - day / 2, now, day))
        // Never run, or a stamp from the future: the answer is "now", not a date in 1970 or 2030.
        assertEquals(now, CloudBackupSchedule.nextRunAt(0L, now, day))
        assertEquals(now, CloudBackupSchedule.nextRunAt(now + day, now, day))
    }

    @Test
    fun `frequencies are stored by key so a stored setting survives a reorder`() {
        assertEquals(CloudBackupFrequency.WEEKLY, CloudBackupFrequency.fromKey("weekly"))
        assertEquals(CloudBackupFrequency.EVERY_THREE_DAYS, CloudBackupFrequency.fromKey("every-3-days"))
        assertEquals(CloudBackupFrequency.DAILY, CloudBackupFrequency.fromKey(null))
        assertEquals(CloudBackupFrequency.DAILY, CloudBackupFrequency.fromKey("fortnightly"))
        assertEquals(7 * day, CloudBackupFrequency.WEEKLY.intervalMs)
    }
}
