package com.health.app.connect

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.health.app.HealthApp
import java.util.concurrent.TimeUnit

/**
 * The scheduled Health Connect import: every few hours while the import is switched on, so the
 * primary user's steps and sleep are there before anybody opens Health to look.
 *
 * Health Connect only answers a background read when the household granted "access data in the
 * background". Without it this does nothing and says so on the Health Connect screen; the import
 * still runs whenever Health is opened.
 */
class HealthConnectSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        HealthApp.get(applicationContext).connectImporter.sync(inForeground = false)
        // Every outcome is recorded on the screen and none is improved by retrying sooner than the
        // next period, so this never asks WorkManager for a retry.
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "health_connect_import"
        private const val PERIOD_HOURS = 4L

        /** Keep the schedule if it is already there; an import switched on twice is one import. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthConnectSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
