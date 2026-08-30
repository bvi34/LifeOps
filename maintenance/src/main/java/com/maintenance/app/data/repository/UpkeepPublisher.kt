package com.maintenance.app.data.repository

import com.maintenance.app.logic.UpkeepRound
import java.time.ZoneId

/**
 * The Android side of the round: it finds the week planner, and runs [UpkeepRound] against it.
 *
 * The round itself is pure and lives in `logic/` — this exists because *finding* LifeOps is an
 * Android question. It is resolved per round rather than injected once, since a round can be asked
 * for before the host Application has installed LifeOps (a restore, a test host); null then means
 * "no week planner in this process", and the round becomes a no-op rather than a crash. Maintenance
 * keeps its schedules and its docket either way; it simply stops putting them on a week that isn't
 * there.
 */
class UpkeepPublisher(
    private val repository: MaintenanceRepository,
    private val week: () -> LifeOpsTasks? = { LifeOpsTasks.createOrNull() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val onPublished: () -> Unit = {}
) {

    /** Reconcile every plan with the week. Idempotent: safe to call after anything, and often. */
    suspend fun round(now: Long = System.currentTimeMillis()): UpkeepRound.Report {
        val planner = week() ?: return UpkeepRound.Report()
        return UpkeepRound(planner, repository, zone, onPublished).run(now)
    }

    /**
     * Take specific tasks off the week — used when the plan or the asset behind them is deleted, so
     * the round can never see them again to work it out for itself.
     */
    suspend fun retire(taskIds: List<String>) {
        if (taskIds.isEmpty()) return
        val planner = week() ?: return
        taskIds.forEach { planner.retire(it) }
    }
}
