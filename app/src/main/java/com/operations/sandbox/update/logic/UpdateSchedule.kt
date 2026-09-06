package com.operations.sandbox.update.logic

/**
 * When the shell is allowed to ask GitHub on its own.
 *
 * A predicate rather than a timer: nothing schedules anything, the launch path simply asks whether
 * a check is due and skips it if not. That keeps the updater free of WorkManager, alarms and a
 * background process — the app finds out about a release the next time it is opened, which for a
 * sideloaded suite is soon enough, and costs nothing when it isn't open.
 *
 * Pure arithmetic, kept here so the awkward cases have tests: a phone whose clock has been moved
 * backwards must not disable the updater until the skew passes, and a first run with no recorded
 * check must not wait an interval before its first one.
 */
object UpdateSchedule {

    /** Six hours. Frequent enough to notice a release the same day, rare enough to be invisible. */
    const val DEFAULT_INTERVAL_MS: Long = 6L * 60 * 60 * 1000

    fun isCheckDue(
        enabled: Boolean,
        lastCheckedAt: Long,
        now: Long,
        intervalMs: Long = DEFAULT_INTERVAL_MS
    ): Boolean {
        if (!enabled) return false
        // Never checked.
        if (lastCheckedAt <= 0L) return true
        // The clock moved backwards (a manually-set date, or a reboot before NTP). Treat the stored
        // stamp as unusable rather than waiting for `now` to catch back up to it.
        if (now < lastCheckedAt) return true
        return now - lastCheckedAt >= intervalMs
    }
}
