package com.lifeops.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lifeops.app.LifeOpsApp
import java.util.concurrent.TimeUnit

/**
 * Phase 2 background refresh: keeps the local weather cache warm so the app never waits on the
 * network to show conditions. Runs on a ~2-hour periodic schedule (the roadmap's "every 1–3
 * hours"), and — when alerts are active — chains a shorter one-time follow-up so severe weather
 * gets a denser cadence than WorkManager's periodic minimum would allow.
 *
 * Work done each run: refresh every tracked location, drop expired alerts, and if anything is
 * still under an alert, schedule the follow-up. A network failure returns retry() so WorkManager
 * backs off and tries again rather than skipping the cycle.
 */
class WeatherRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? LifeOpsApp ?: return Result.success()
        val repo = app.weatherRepository
        return try {
            repo.refreshAll()
            repo.pruneExpiredAlerts()
            if (repo.hasActiveAlerts()) {
                scheduleAlertFollowUp(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK = "weather_refresh_periodic"
        private const val ALERT_FOLLOWUP_WORK = "weather_refresh_alert_followup"

        private const val PERIODIC_HOURS = 2L
        private const val ALERT_FOLLOWUP_MINUTES = 30L

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Enqueue the periodic refresh. KEEP so relaunching the app doesn't reset the cycle;
         * safe to call on every startup. A no-op-cheap run when no locations are tracked.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeatherRefreshWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
                .setConstraints(networkConstraint)
                .addTag(PERIODIC_WORK)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /** One denser follow-up while alerts are active. REPLACE collapses repeats into one. */
        fun scheduleAlertFollowUp(context: Context) {
            val request = OneTimeWorkRequestBuilder<WeatherRefreshWorker>()
                .setInitialDelay(ALERT_FOLLOWUP_MINUTES, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .addTag(ALERT_FOLLOWUP_WORK)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ALERT_FOLLOWUP_WORK, ExistingWorkPolicy.REPLACE, request
            )
        }
    }
}
