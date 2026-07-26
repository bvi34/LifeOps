package com.lifeops.app.data.repository

/**
 * Port for queuing/cancelling a habit's daily reminder, letting [CounterRepository] drive reminders
 * without depending on Android's WorkManager (or a [android.content.Context]) directly. The
 * production implementation is WorkManager-backed (see the app's WorkManagerHabitReminderScheduler);
 * unit tests substitute a fake, which is what makes the repository testable off-device.
 */
interface HabitReminderScheduler {
    /** Enqueue [counterId]'s reminder at [hour] (0-23), replacing any pending one. */
    fun schedule(counterId: String, name: String, hour: Int)

    /** Cancel [counterId]'s pending reminder. */
    fun cancel(counterId: String)

    /** Cancel every queued habit reminder, e.g. before a full re-sync. */
    fun cancelAll()
}

/** A do-nothing scheduler — the default when a caller (or test) doesn't care about reminders. */
object NoopHabitReminderScheduler : HabitReminderScheduler {
    override fun schedule(counterId: String, name: String, hour: Int) {}
    override fun cancel(counterId: String) {}
    override fun cancelAll() {}
}
