package com.operations.sandbox.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The thing that makes the backup *scheduled*: a periodic job that wakes up, asks whether an
 * archive is owed, and takes one.
 *
 * The suite has never done anything on its own with the household's whole data set, and this is the
 * one exception — so the conditions are deliberately conservative. It runs only when switched on,
 * only when the destination is configured, by default only on an unmetered network, and only when
 * the battery isn't low. Everything it decides is decided twice: WorkManager's constraints keep it
 * from waking at a bad moment, and [CloudBackupPrefs.isDue] keeps a wake-up that happens anyway
 * from writing an archive that isn't owed.
 *
 * ## Why the interval is not the whole answer
 *
 * A periodic job is a request, not a promise. The OS batches wake-ups, doze windows push them out,
 * and a phone that was off Wi-Fi all night runs the job late. Pairing a periodic request with a
 * due-check is what makes both directions safe: an early or repeated wake-up finds nothing owed and
 * costs nothing, and a late one archives immediately rather than waiting for the next tick.
 */
class ScheduledCloudBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = CloudBackupPrefs(applicationContext)
        // Switched off between the job being enqueued and it running — which is the normal case for
        // one wake-up after somebody turns the feature off, since cancellation isn't instantaneous.
        if (!prefs.enabled) return Result.success()
        if (!prefs.isDue()) return Result.success()

        return when (val outcome = CloudBackupRunner(applicationContext, prefs).run()) {
            is CloudBackupRunner.Outcome.Uploaded -> Result.success()
            is CloudBackupRunner.Outcome.Skipped -> Result.success()
            is CloudBackupRunner.Outcome.Failed ->
                // A wrong container name will still be wrong in fifteen minutes; retrying it with
                // backoff until the phone is replaced is how a broken setting becomes a battery
                // complaint. Only the failures that can pass on their own are retried — and the
                // periodic schedule tries everything else again tomorrow regardless.
                if (outcome.transient) Result.retry() else Result.failure()
        }
    }

    companion object {

        private const val WORK_NAME = "sandbox_cloud_backup"

        /**
         * Bring the schedule in line with the settings: enqueue it, update it, or cancel it.
         *
         * Called at startup and after every settings change, so there is exactly one place that
         * knows how the job is registered. [ExistingPeriodicWorkPolicy.UPDATE] rather than KEEP
         * because a changed frequency or a flipped "Wi-Fi only" has to take effect without waiting
         * for the next run — UPDATE rewrites the request in place and keeps the period's progress,
         * so a daily backup does not restart its clock every time the app is opened.
         */
        fun sync(context: Context) {
            val prefs = CloudBackupPrefs(context)
            val work = WorkManager.getInstance(context)
            if (!prefs.enabled) {
                work.cancelUniqueWork(WORK_NAME)
                return
            }
            val interval = prefs.frequency.intervalMs
            val request = PeriodicWorkRequestBuilder<ScheduledCloudBackupWorker>(
                interval, TimeUnit.MILLISECONDS,
                // A flex window: the job may run in the last quarter of each period rather than at
                // an exact minute, which lets the OS batch it with whatever else it is waking for.
                interval / 4, TimeUnit.MILLISECONDS
            )
                .setConstraints(constraints(prefs))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .addTag(WORK_NAME)
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * When to let it run: a network of the kind the household agreed to, a battery that isn't
         * low, and room on the disk — the archive is staged there before it is sent, and a job that
         * fills the last of somebody's storage to back it up has got the order wrong.
         */
        private fun constraints(prefs: CloudBackupPrefs): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        /** Half an hour, doubling — a throttled account or a flaky uplink, not a wrong setting. */
        private const val BACKOFF_MINUTES = 30L
    }
}
