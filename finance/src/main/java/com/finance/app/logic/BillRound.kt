package com.finance.app.logic

import java.time.LocalDate
import java.time.ZoneId

/**
 * One bill as a round sees it: the bill, which account it is paid from, and what the week planner
 * currently holds for it.
 *
 * Assembled in one read before any decision is taken, so a round decides bill by bill in pure code
 * rather than interleaving decisions with database reads — which is how a round ends up meaning
 * something subtly different on a slow phone.
 */
data class BillSnapshot(
    val bill: Bills.Bill,
    val accountName: String,
    val link: BillTasks.TaskLink
)

/**
 * The week planner, as the round needs it. Implemented against LifeOps' own task service; declared
 * here so the round can be driven — and tested — without one.
 */
interface BillWeek {
    /** Put a task on the week. Null when the planner declined it. */
    suspend fun publish(title: String, due: LocalDate, note: String): String?

    /** Move an open task's date, and fix its title. False when it is no longer there. */
    suspend fun reschedule(taskId: String, due: LocalDate, title: String): Boolean

    /** Take a task off the week. */
    suspend fun retire(taskId: String): Boolean

    /** What the planner holds for a task, following any carry-forward hop. Null when it is gone. */
    suspend fun state(taskId: String): BillTasks.PublishedTask?
}

/** Finance's own store, as the round needs it. */
interface BillStore {
    suspend fun billSnapshots(now: Long): List<BillSnapshot>
    suspend fun setBillLink(billId: String, taskId: String?, publishedDue: LocalDate?)

    /** Record that a bill was paid, from a tick on the week rather than from a matched payment. */
    suspend fun markPaidFromWeek(billId: String, paidOn: LocalDate): Boolean
}

/**
 * The round that keeps Finance's bills and the LifeOps week in step.
 *
 * It is a **reconciliation, not an event handler**, and that is the whole design. LifeOps announces
 * a tick the moment it happens and this round runs on the back of it — but the same round also runs
 * when Finance comes to the foreground and after every refresh from the bank, and it reaches the
 * same answer either way. A tick that arrived mid-restore, a task somebody deleted, or a week that
 * closed and carried a task into a new row under a new id are all just facts the next round reads
 * and acts on. Nothing depends on having caught a particular moment.
 *
 * Unlike Maintenance's round this one is **single-pass**. There, a completion restarts a plan's
 * clock and the new occurrence needs publishing in the same round; here a completion ends the bill,
 * and next month's bill is a different row that will not exist until a payment or a statement brings
 * it into being. There is nothing a second pass could discover, so there isn't one.
 */
class BillRound(
    private val week: BillWeek,
    private val store: BillStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * Called when a pass actually puts something on the week. The app uses it to record that this
     * install has published at all — the cheap gate that keeps an unused Finance from opening its
     * database every time a task is ticked anywhere in the suite.
     */
    private val onPublished: () -> Unit = {},
    private val currencySymbol: String = "$"
) {

    /** What a round did. For the log and for tests; nothing on screen depends on it. */
    data class Report(
        val published: Int = 0,
        val rescheduled: Int = 0,
        val retired: Int = 0,
        val paid: Int = 0,
        val forgotten: Int = 0
    ) {
        val didAnything: Boolean get() = published + rescheduled + retired + paid + forgotten > 0
    }

    suspend fun run(now: Long): Report {
        var report = Report()
        val snapshots = store.billSnapshots(now)

        // The task ids already standing for a bill. Publishing *adopts* an open task of the same
        // title rather than adding a second beside it, which is right when the task is one you wrote
        // by hand — and wrong if it already belongs to another bill. Two bills to the same payee in
        // one month would otherwise quietly share a row, and one tick would pay both. The loser of
        // that race simply goes without a task.
        val claimed = snapshots.mapNotNullTo(mutableSetOf()) { it.link.taskId }

        for (item in snapshots) {
            val billId = item.bill.id
            val stored = item.link
            val task = stored.taskId?.let { week.state(it) }

            // A week that closed without the bill being paid mints a new row for the new week. Follow
            // the hop first, so everything below is talking about the task that actually exists now.
            val link = if (task != null && task.id != stored.taskId) {
                store.setBillLink(billId, task.id, stored.publishedDue)
                stored.copy(taskId = task.id)
            } else {
                stored
            }

            when (
                val action = BillTasks.decide(
                    bill = item.bill,
                    accountName = item.accountName,
                    link = link,
                    task = task,
                    now = now,
                    zone = zone,
                    currencySymbol = currencySymbol
                )
            ) {
                is BillTasks.Action.Idle -> Unit

                is BillTasks.Action.Publish -> {
                    val taskId = week.publish(action.title, action.due, action.note)
                        ?.takeIf { claimed.add(it) }
                    // The due date is recorded as published even when nothing came back, so the round
                    // doesn't try again on every pass against a week that isn't going to give it a
                    // task.
                    store.setBillLink(billId, taskId, action.due)
                    if (taskId != null) report = report.copy(published = report.published + 1)
                }

                is BillTasks.Action.Reschedule -> {
                    if (week.reschedule(action.taskId, action.due, action.title)) {
                        store.setBillLink(billId, action.taskId, action.due)
                        report = report.copy(rescheduled = report.rescheduled + 1)
                    } else {
                        // It went away between the read and the write. Drop the link and let the next
                        // round decide about it from scratch.
                        store.setBillLink(billId, null, link.publishedDue)
                        report = report.copy(forgotten = report.forgotten + 1)
                    }
                }

                is BillTasks.Action.Retire -> {
                    week.retire(action.taskId)
                    // Both halves of the link go. A bill that was paid keeps no claim on the week,
                    // and one switched back on should publish afresh rather than think it already
                    // has.
                    store.setBillLink(billId, null, null)
                    report = report.copy(retired = report.retired + 1)
                }

                is BillTasks.Action.MarkPaid -> {
                    val paidOn = java.time.Instant.ofEpochMilli(action.completedAtMillis)
                        .atZone(zone).toLocalDate()
                    if (store.markPaidFromWeek(billId, paidOn)) {
                        // The task stays where it is, ticked. It is a true record of what happened,
                        // and taking it off the week would erase somebody's own week.
                        report = report.copy(paid = report.paid + 1)
                    }
                }

                is BillTasks.Action.Forget -> {
                    // The due date stays remembered on purpose: it is what stops the app putting back
                    // a task you deleted.
                    store.setBillLink(billId, null, link.publishedDue)
                    report = report.copy(forgotten = report.forgotten + 1)
                }
            }
        }

        if (report.published > 0) onPublished()
        return report
    }
}
