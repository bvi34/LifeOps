package com.lifeops.app.connection

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The outbound half of the connection layer: LifeOps saying *"this got done"* to whoever else in
 * the process cares.
 *
 * Everything else in `connection/` points inwards — an address, a payload, a use-case invoked on
 * LifeOps. This is the one thing that points out. It exists because a task can be **owned** by
 * another app: Maintenance publishes "Truck: Oil change" onto a future week, and the moment that
 * line is ticked in LifeOps, the schedule it came from has to move. Polling would answer that
 * question a foreground later; this answers it as the tick lands.
 *
 * Three rules keep it honest:
 *
 * - **It announces facts, not requests.** A listener is told a task was completed; nothing here
 *   waits for it, reads its answer, or lets it veto anything. LifeOps' behaviour is identical
 *   whether or not anybody is listening, which is what stops this becoming a back door into the
 *   task lifecycle.
 * - **A listener cannot break a tick.** Each is called inside `runCatching`: a hosted app whose
 *   database is mid-restore must not turn "mark done" into a crash in LifeOps.
 * - **It fires after the transaction commits.** A listener that read the task mid-transaction could
 *   see a completion that then rolled back.
 *
 * The bus deliberately carries no un-completion event. Un-ticking a task in LifeOps is a correction
 * to LifeOps' week; what another app already wrote down in response to the tick is *its* record to
 * correct, and silently deleting somebody else's service history from here would be worse than
 * leaving it.
 *
 * Registration is process-wide and lives as long as the process — the suite's apps are installed
 * once, at `Application.onCreate`, so there is nothing to unregister in practice. [unregister] is
 * there for tests.
 */
object TaskCompletionBus {

    /** One task, ticked. [completedAtMillis] is the instant LifeOps recorded, not "now". */
    data class Completion(
        val taskId: String,
        val title: String,
        val completedAtMillis: Long
    )

    fun interface Listener {
        fun onTaskCompleted(completion: Completion)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun register(listener: Listener) {
        if (listener !in listeners) listeners += listener
    }

    fun unregister(listener: Listener) {
        listeners -= listener
    }

    /**
     * Tell every listener. Called by `TaskRepository.completeTask` once the tick is committed —
     * not from screens, which would announce a completion the database hasn't agreed to yet.
     */
    fun announce(completion: Completion) {
        listeners.forEach { listener -> runCatching { listener.onTaskCompleted(completion) } }
    }
}
