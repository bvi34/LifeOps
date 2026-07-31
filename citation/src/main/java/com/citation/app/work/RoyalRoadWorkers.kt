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
 * Background Royal Road jobs. Each is a thin `CoroutineWorker` that awaits the app's RR coordinator
 * and calls one of its methods — all the policy (rates, priority, the 7-day rule) lives in `:core`,
 * so these workers only supply the schedule and the network constraint.
 *
 * Three jobs, matching the design's lanes and cadences:
 *  - [RrFavoritesPollWorker] — poll favourites' feeds (loose budget) and enqueue new chapters.
 *  - [RrBackfillWorker] — advance favourites' slow backfill within the scrape budget.
 *  - [RrEvictionWorker] — reclaim borrowed cache for stale non-favourites (7-day rule).
 */
class RrFavoritesPollWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = CitationApplication.getOrNull() ?: return Result.success()
        return try {
            app.repository.await().royalRoad.pollFavorites()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class RrBackfillWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = CitationApplication.getOrNull() ?: return Result.success()
        return try {
            // Draining respects the scrape budget internally; each run makes bounded progress and
            // the plan is recomputed from cache next time (resumable).
            app.repository.await().royalRoad.drainQueue()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class RrEvictionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = CitationApplication.getOrNull() ?: return Result.success()
        return try {
            app.repository.await().royalRoad.runEviction()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/** Registers the periodic RR jobs. Called once from CitationApplication.start(). */
object RoyalRoadScheduler {
    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)

        wm.enqueueUniquePeriodicWork(
            "rr_favorites_poll",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RrFavoritesPollWorker>(3, TimeUnit.HOURS)
                .setConstraints(networkConstraint).build()
        )
        wm.enqueueUniquePeriodicWork(
            "rr_backfill",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RrBackfillWorker>(1, TimeUnit.HOURS)
                .setConstraints(networkConstraint).build()
        )
        // Eviction needs no network; a daily sweep is plenty for a 7-day retention rule.
        wm.enqueueUniquePeriodicWork(
            "rr_eviction",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RrEvictionWorker>(1, TimeUnit.DAYS).build()
        )
    }
}
