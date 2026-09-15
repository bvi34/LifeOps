package com.citation.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.citation.app.CitationApplication
import com.citation.app.data.sync
import java.util.concurrent.TimeUnit

/**
 * Periodically runs a sync round with LifeOps: drains the outbox (telemetry + notes) into the
 * file-drop mailbox and applies any acquire intents LifeOps has left for us. No network constraint —
 * the transport is a local shared folder, so sync works fully offline.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = CitationApplication.getOrNull() ?: return Result.success()
        return try {
            app.repository.await().sync()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "citation_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).build()
            )
        }
    }
}
