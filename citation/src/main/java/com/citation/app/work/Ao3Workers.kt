package com.citation.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.citation.app.CitationApplication
import java.util.concurrent.TimeUnit

/**
 * Background Archive of Our Own jobs — the AO3 mirror of the Royal Road workers. Each is a thin
 * `CoroutineWorker` that awaits the app's AO3 coordinator and calls one of its methods; all the policy
 * (rates, priority, the 7-day rule) lives in `:core`.
 *
 * Three jobs, matching the design's lanes and cadences:
 *  - [Ao3FavoritesPollWorker] — re-read favourites' catalogs and enqueue new chapters (AO3 has no
 *    feed, so this is a scrape, hence the longer cadence than Royal Road's feed poll).
 *  - [Ao3BackfillWorker] — advance favourites' slow backfill within the scrape budget.
 *  - [Ao3EvictionWorker] — reclaim borrowed cache for stale non-favourites (7-day rule).
 */
class Ao3FavoritesPollWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = CitationApplication.getOrNull() ?: return Result.success()
        return try {
            app.repository.await().ao3.pollFavorites()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class Ao3BackfillWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = CitationApplication.getOrNull() ?: return Result.success()
        return try {
            app.repository.await().ao3.drainQueue()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class Ao3EvictionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = CitationApplication.getOrNull() ?: return Result.success()
        return try {
            app.repository.await().ao3.runEviction()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/** Registers the periodic AO3 jobs. Called once from CitationApplication.start(). */
object Ao3Scheduler {
    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)

        // AO3 has no cheap feed — polling is a catalog scrape — so poll favourites less often than RR.
        wm.enqueueUniquePeriodicWork(
            "ao3_favorites_poll",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Ao3FavoritesPollWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraint).build()
        )
        wm.enqueueUniquePeriodicWork(
            "ao3_backfill",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Ao3BackfillWorker>(1, TimeUnit.HOURS)
                .setConstraints(networkConstraint).build()
        )
        // Eviction needs no network; a daily sweep is plenty for a 7-day retention rule.
        wm.enqueueUniquePeriodicWork(
            "ao3_eviction",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Ao3EvictionWorker>(1, TimeUnit.DAYS).build()
        )
    }
}
