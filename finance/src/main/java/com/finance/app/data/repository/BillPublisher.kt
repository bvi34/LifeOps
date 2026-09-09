package com.finance.app.data.repository

import com.finance.app.logic.BillRound
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * The Android side of the round: it finds the week planner, and runs [BillRound] against it.
 *
 * The round itself is pure and lives in `logic/` — this exists because *finding* LifeOps is an
 * Android question. It is resolved per round rather than injected once, since a round can be asked
 * for before the host Application has installed LifeOps (a restore, a test host); null then means
 * "no week planner in this process", and the round becomes a no-op rather than a crash. Finance
 * keeps its accounts and its due list either way; it simply stops putting them on a week that isn't
 * there.
 */
class BillPublisher(
    private val store: FinanceRepository,
    private val week: () -> LifeOpsTasks? = { LifeOpsTasks.createOrNull() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val onPublished: () -> Unit = {}
) {

    /**
     * Reconcile every bill with the week. Idempotent: safe to call after anything, and often.
     *
     * **One at a time.** Rounds are kicked off from three unrelated places — the app coming to the
     * foreground, a completion announced by LifeOps, and the end of every refresh from the bank —
     * and any two of those can land together. Two rounds reading "no task yet" would both publish
     * one, leaving an orphan on the week that nothing points at; two rounds reading the same
     * completed task would both mark the bill paid. The round is idempotent when it is the only one
     * running, and this is what makes that true.
     */
    suspend fun round(now: Long = System.currentTimeMillis()): BillRound.Report = gate.withLock {
        val planner = week() ?: return@withLock BillRound.Report()
        BillRound(planner, store, zone, onPublished).run(now)
    }

    /**
     * Take specific tasks off the week — used when the bill behind them is deleted by hand, so the
     * round can never see them again to work it out for itself.
     *
     * Behind the same gate as [round]: a round mid-flight could otherwise publish a fresh task for
     * the very bill being deleted, a moment after its old one was taken down.
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
     * [com.finance.app.FinanceApp], so this is every round the app runs.
     */
    private val gate = Mutex()
}
