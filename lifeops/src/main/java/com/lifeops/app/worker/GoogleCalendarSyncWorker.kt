package com.lifeops.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lifeops.app.LifeOpsApp
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Opt-in periodic Google Calendar sync (see GoogleCalendarSyncRepository), scheduled from
 * LifeOpsApp.start() only when the user has both enabled sync and picked a calendar. Runs a
 * push-then-pull each cycle; a permission that's since been revoked or a missing calendar just
 * ends the run quietly rather than retrying forever.
 */
class GoogleCalendarSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = LifeOpsApp.getOrNull() ?: return Result.success()
        val prefs = app.preferencesRepository
        if (!prefs.googleCalendarSyncEnabled) return Result.success()
        val calendarId = prefs.googleCalendarId ?: return Result.success()
        val result = app.googleCalendarSyncRepository.sync(calendarId)
        if (result.error != null) return Result.retry()
        prefs.googleCalendarLastSyncedAt = Instant.now().toString()
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK = "google_calendar_sync_periodic"
        private const val PERIODIC_HOURS = 3L

        /** Enqueue the periodic sync. KEEP so relaunching the app doesn't reset the cycle. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<GoogleCalendarSyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
                .addTag(PERIODIC_WORK)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /** Cancel the periodic sync (toggled off from the Calendar Sync screen). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }
    }
}
