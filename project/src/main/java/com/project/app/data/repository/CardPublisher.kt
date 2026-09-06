package com.project.app.data.repository

import com.project.app.logic.CardRound
import com.project.app.logic.CardStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * The Android side of the hand-off: it finds the week planner and runs [CardRound] against it.
 *
 * The round itself is pure and lives in `logic/` — this exists because *finding* LifeOps is an
 * Android question. It is resolved per round rather than injected once, since a round can be asked
 * for before the host Application has installed LifeOps (a restore, a test host); null then means
 * "no week planner in this process", and the round becomes a no-op rather than a crash. Project
 * keeps its boards and its dates either way; it simply stops putting them on a week that is not
 * there.
 */
class CardPublisher(
    private val store: CardStore,
    private val week: () -> LifeOpsTasks? = { LifeOpsTasks.createOrNull() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val onPublished: () -> Unit = {}
) {

    /**
     * Reconcile every dated card with the week. Idempotent: safe to call after anything, and often.
     *
     * **One at a time.** Rounds are kicked off from three unrelated places — the app coming to the
     * foreground, a completion announced by LifeOps, and every edit to a card — and any two of those
     * can land together. Two rounds reading "no task yet" would both publish one, leaving an orphan
     * on the week that nothing points at; two rounds reading the same completed task would both try
     * to finish the card. The round is idempotent when it is the only one running, and this is what
     * makes that true.
     */
    suspend fun round(now: Long = System.currentTimeMillis()): CardRound.Report = gate.withLock {
        val planner = week() ?: return@withLock CardRound.Report()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        CardRound(planner, store, onPublished).run(today, now)
    }

    /**
     * Take specific tasks off the week — used when the cards behind them go with their project, so
     * no later round can see them to work it out for itself.
     *
     * Behind the same gate as [round]: a round mid-flight could otherwise publish a fresh task for a
     * card being deleted, a moment after its old one was taken down.
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
     * [com.project.app.ProjectApp], so this is every round the app runs.
     */
    private val gate = Mutex()
}
