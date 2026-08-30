package com.maintenance.app.data.repository

import com.maintenance.app.logic.UpkeepRound
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Reconcile every plan with the week. Idempotent: safe to call after anything, and often.
     *
     * **One at a time.** Rounds are kicked off from three unrelated places — the app coming to the
     * foreground, a completion announced by LifeOps, and every edit to a schedule — and any two of
     * those can land together. Two rounds reading "no task yet" would both publish one, leaving an
     * orphan on the week that nothing points at; two rounds reading the same completed task would
     * both log the service. The round is idempotent when it is the only one running, and this is
     * what makes that true.
     */
    suspend fun round(now: Long = System.currentTimeMillis()): UpkeepRound.Report = gate.withLock {
        val planner = week() ?: return@withLock UpkeepRound.Report()
        UpkeepRound(planner, repository, zone, onPublished).run(now)
    }

    /**
     * Tick specific tasks off the week — used when a meter reading satisfies the prompt that asked
     * for it. Outside the gate deliberately: it is a completion, not a decision about what should be
     * on the week, and LifeOps' own announcement will bring a round along behind it.
     */
    suspend fun completeTasks(taskIds: List<String>) {
        if (taskIds.isEmpty()) return
        val planner = week() ?: return
        taskIds.forEach { planner.complete(it) }
    }

    /**
     * Take specific tasks off the week — used when the plan or the asset behind them is deleted, so
     * the round can never see them again to work it out for itself.
     *
     * Behind the same gate as [round]: a round mid-flight could otherwise publish a fresh task for
     * the very plan being deleted, a moment after its old one was taken down.
     */
    suspend fun retire(taskIds: List<String>) {
        if (taskIds.isEmpty()) return
        gate.withLock {
            val planner = week() ?: return@withLock
            taskIds.forEach { planner.retire(it) }
        }
    }

    /**
     * One round at a time, process-wide for this publisher. The publisher is a singleton on
     * [com.maintenance.app.MaintenanceApp], so this is every round the app runs.
     */
    private val gate = Mutex()
}
