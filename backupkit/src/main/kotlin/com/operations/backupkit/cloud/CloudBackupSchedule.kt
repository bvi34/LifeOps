package com.operations.backupkit.cloud

/**
 * How often the sandbox archives itself into the cloud, and whether a run is owed.
 *
 * The same shape as the updater's `UpdateSchedule` and for the same reason: the arithmetic of "is
 * it due" has awkward cases that deserve tests rather than confidence — a phone whose clock has
 * been moved backwards must not sit out the skew, and a first run must not wait an interval before
 * the first archive exists.
 *
 * Unlike the updater, something *does* schedule this: WorkManager wakes the worker on its own
 * cadence, which is approximate by design (the OS batches wake-ups, and a doze window can push one
 * out by hours). The predicate here is what makes that approximation safe in both directions — an
 * early wake-up finds nothing owed and goes back to sleep without writing a duplicate archive, and
 * a late one runs immediately rather than waiting for the next tick.
 */
object CloudBackupSchedule {

    fun isDue(
        enabled: Boolean,
        lastRunAt: Long,
        now: Long,
        intervalMs: Long
    ): Boolean {
        if (!enabled) return false
        // Never archived: the first run is owed the moment it is switched on.
        if (lastRunAt <= 0L) return true
        // The clock moved backwards (a hand-set date, or a reboot before NTP). Treat the stored
        // stamp as unusable rather than refusing to back anything up until `now` catches up to it.
        if (now < lastRunAt) return true
        // A tolerance, because WorkManager's periodic wake-ups drift: a run that arrives four
        // minutes before the interval is up is the run this interval asked for, and refusing it
        // would push the archive a whole period later.
        return now - lastRunAt >= intervalMs - EARLY_TOLERANCE_MS
    }

    /** When the next archive is owed, for the "next backup: tomorrow 02:00-ish" line. */
    fun nextRunAt(lastRunAt: Long, now: Long, intervalMs: Long): Long =
        if (lastRunAt <= 0L || now < lastRunAt) now else lastRunAt + intervalMs

    /**
     * Five minutes. Smaller than any interval offered, larger than the drift a periodic worker
     * normally arrives with.
     */
    const val EARLY_TOLERANCE_MS: Long = 5L * 60 * 1000
}

/**
 * How often a scheduled backup runs.
 *
 * A closed set rather than a free-typed number of hours: every one of these is a whole archive of
 * the household's data crossing a metered connection and landing in a paid container, and the
 * shortest sensible one is still measured in hours. [key] is what is written to preferences, so it
 * must stay stable; nothing dispatches on the ordinal.
 */
enum class CloudBackupFrequency(val key: String, val label: String, val intervalMs: Long) {
    DAILY("daily", "Every day", 24L * 60 * 60 * 1000),
    EVERY_THREE_DAYS("every-3-days", "Every 3 days", 3L * 24 * 60 * 60 * 1000),
    WEEKLY("weekly", "Every week", 7L * 24 * 60 * 60 * 1000);

    companion object {

        /** The default: a day's worth of data is the most this should ever be able to lose. */
        val DEFAULT = DAILY

        /** The frequency [key] names, falling back to [DEFAULT] for an absent or unknown one. */
        fun fromKey(key: String?): CloudBackupFrequency =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
