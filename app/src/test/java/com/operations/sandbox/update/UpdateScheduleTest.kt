package com.operations.sandbox.update

import com.operations.sandbox.update.logic.UpdateSchedule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The throttle on the launch check.
 *
 * Two failure modes matter and neither is visible by using the app: checking on every single launch
 * (which GitHub rate-limits, and which is rude), and a stored timestamp that quietly wedges the
 * updater off forever.
 */
class UpdateScheduleTest {

    private val hour = 60L * 60 * 1000
    private val now = 1_700_000_000_000L

    @Test
    fun `the first launch always checks`() {
        assertTrue(UpdateSchedule.isCheckDue(enabled = true, lastCheckedAt = 0L, now = now))
    }

    @Test
    fun `a recent check is not repeated`() {
        assertFalse(UpdateSchedule.isCheckDue(enabled = true, lastCheckedAt = now - hour, now = now))
        assertFalse(
            UpdateSchedule.isCheckDue(enabled = true, lastCheckedAt = now - 5 * hour, now = now)
        )
    }

    @Test
    fun `the interval elapsing makes one due`() {
        assertTrue(UpdateSchedule.isCheckDue(enabled = true, lastCheckedAt = now - 6 * hour, now = now))
        assertTrue(UpdateSchedule.isCheckDue(enabled = true, lastCheckedAt = now - 48 * hour, now = now))
    }

    @Test
    fun `a clock that moved backwards does not wedge the updater off`() {
        // The phone's date was set forward and then corrected; the stored stamp is in the future.
        // Waiting six hours from *that* would disable checking for as long as the skew lasts.
        assertTrue(UpdateSchedule.isCheckDue(enabled = true, lastCheckedAt = now + 30 * hour, now = now))
    }

    @Test
    fun `the switch wins over everything`() {
        assertFalse(UpdateSchedule.isCheckDue(enabled = false, lastCheckedAt = 0L, now = now))
        assertFalse(
            UpdateSchedule.isCheckDue(enabled = false, lastCheckedAt = now - 48 * hour, now = now)
        )
    }
}
