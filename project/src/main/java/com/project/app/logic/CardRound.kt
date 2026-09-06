package com.project.app.logic

import java.time.LocalDate

/**
 * The week planner, as the round needs it.
 *
 * Implemented against LifeOps' own task service (`data/repository/LifeOpsTasks`); declared here so
 * the round can be driven — and tested — without one.
 */
interface CardWeek {
    /** Put a task on the week. Null when the planner declined it. */
    suspend fun publish(title: String, due: LocalDate, note: String): String?

    /** Move an open task's date, and fix its title. False when it is no longer there. */
    suspend fun reschedule(taskId: String, due: LocalDate, title: String): Boolean

    /** Take a task off the week. */
    suspend fun retire(taskId: String): Boolean

    /** What the planner holds for a task, following any carry-forward hop. Null when it is gone. */
    suspend fun state(taskId: String): CardTasks.PublishedTask?
}

/** Project's own store, as the round needs it. */
interface CardStore {
    /** Every card that could have something to do with the week, with the facts to decide on. */
    suspend fun cardSnapshots(): List<CardTasks.CardSnapshot>

    suspend fun setCardLink(cardId: String, taskId: String?, publishedDue: Long?)

    /** Finish the card a tick stands for, and let go of the task. True if the card really moved. */
    suspend fun completeFromWeek(cardId: String, completedAt: Long): Boolean
}

/**
 * The round that keeps a board's due dates and the LifeOps week in step.
 *
 * It is a **reconciliation, not an event handler**, and that is the whole design — the same one
 * Maintenance uses. LifeOps announces a tick the moment it happens and a round runs on the back of
 * it, but the same round also runs when Project comes to the foreground and after every edit to a
 * card, and it reaches the same answer either way. So a tick that arrived while the database was
 * mid-restore, a task somebody deleted, or a week that closed and carried the task into a new row
 * under a new id are all just facts the next round reads and acts on. Nothing depends on having
 * caught a particular moment, which is what makes a missed announcement cost latency and never
 * correctness.
 *
 * Unlike Maintenance's, this round runs **once**. A schedule recurs, so completing one there moves
 * the plan's clock and makes something new due, which needs a second pass to publish. A card has
 * one date and one outcome: finishing it finishes it.
 */
class CardRound(
    private val week: CardWeek,
    private val store: CardStore,
    /**
     * Called when a pass actually puts something on the week. The app records that this install has
     * published at all — the cheap gate that stops a household who never dates a card from opening
     * this database every time a task is ticked anywhere in the suite.
     */
    private val onPublished: () -> Unit = {}
) {

    /** What a round did. For tests and the log; nothing on screen depends on it. */
    data class Report(
        val published: Int = 0,
        val rescheduled: Int = 0,
        val retired: Int = 0,
        val completed: Int = 0,
        val forgotten: Int = 0
    ) {
        val didAnything: Boolean
            get() = published + rescheduled + retired + completed + forgotten > 0
    }

    suspend fun run(today: LocalDate, now: Long): Report {
        var report = Report()
        val snapshots = store.cardSnapshots()

        // The task ids already standing for a card. Publishing *adopts* an open task of the same
        // title rather than adding a second beside it, which is right when the task is one you wrote
        // by hand — and wrong if it already belongs to another card. Two cards named the same thing
        // in one project would otherwise quietly share a row, and one tick would finish both. The
        // loser simply goes without a task until it is renamed.
        val claimed = snapshots.mapNotNullTo(mutableSetOf()) { it.link.taskId }

        for (item in snapshots) {
            val cardId = item.card.id
            val stored = item.link
            val task = stored.taskId?.let { week.state(it) }

            // A week that closed without the job being done mints a new row for the new week. Follow
            // the hop first, so everything below is talking about the task that exists now.
            val snapshot = if (task != null && task.id != stored.taskId) {
                store.setCardLink(cardId, task.id, stored.publishedDue)
                item.copy(link = stored.copy(taskId = task.id))
            } else {
                item
            }

            when (val action = CardTasks.decide(snapshot, task, today, now)) {
                is CardTasks.Action.Idle -> Unit

                is CardTasks.Action.Publish -> {
                    val taskId = week.publish(action.title, action.due, action.note)
                        ?.takeIf { claimed.add(it) }
                    // Recorded as published even when nothing came back, so the round does not try
                    // again on every pass against a week that is not going to give it a task.
                    store.setCardLink(cardId, taskId, snapshot.card.dueOn)
                    if (taskId != null) report = report.copy(published = report.published + 1)
                }

                is CardTasks.Action.Reschedule -> {
                    if (week.reschedule(action.taskId, action.due, action.title)) {
                        store.setCardLink(cardId, action.taskId, snapshot.card.dueOn)
                        report = report.copy(rescheduled = report.rescheduled + 1)
                    } else {
                        // It went away between the read and the write. Drop the link and let the
                        // next round decide about it from scratch.
                        store.setCardLink(cardId, null, snapshot.link.publishedDue)
                        report = report.copy(forgotten = report.forgotten + 1)
                    }
                }

                is CardTasks.Action.Retire -> {
                    week.retire(action.taskId)
                    // Both halves go: a card switched back on should put itself on the week again
                    // rather than think it already has.
                    store.setCardLink(cardId, null, null)
                    report = report.copy(retired = report.retired + 1)
                }

                is CardTasks.Action.MarkDone -> {
                    if (store.completeFromWeek(cardId, action.completedAtMillis)) {
                        report = report.copy(completed = report.completed + 1)
                    }
                }

                is CardTasks.Action.Forget -> {
                    // The day it was published for stays remembered on purpose: it is what stops
                    // the app putting back a task you deleted.
                    store.setCardLink(cardId, null, snapshot.link.publishedDue)
                    report = report.copy(forgotten = report.forgotten + 1)
                }
            }
        }

        if (report.published > 0) onPublished()
        return report
    }
}
