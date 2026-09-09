package com.finance.app.logic

import com.operations.suitekit.SuiteMoney
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The seam between a bill and the week it has to be paid in.
 *
 * Finance knows *when* money is due; LifeOps is where a week is planned. So a bill doesn't grow a
 * notification here — it publishes a task over there, dated the day it falls due, which LifeOps
 * parks in its Future Tasks queue until the week containing that date opens. That is the same shape
 * Maintenance publishes upkeep in, and it is the same argument: one planner for the suite, and this
 * app supplying it rather than competing with it.
 *
 * This file is the *decision*, not the plumbing. Given what the bill is and what LifeOps currently
 * holds, it says which single thing should happen next; the bridge does it.
 *
 * ## Where this differs from Maintenance's version, and why
 *
 * Maintenance's plans are cycles: tick the oil change and the clock restarts, so a completion is
 * followed by publishing the next occurrence. A bill is not a cycle — it is one obligation on one
 * date — and the recurrence lives a level up, in [Recurring], which will mint next month's bill when
 * next month's charge appears. So a completed task here **ends** the bill's involvement with the
 * week rather than beginning the next round of it.
 *
 * The more interesting difference is that a bill can be settled **without anybody ticking anything**.
 * The bank is the source of truth: when a matching payment lands, [Bills.settle] marks the bill paid
 * and the task LifeOps is still holding is now asking for something already done. That produces
 * [Action.Retire], and it is the behaviour that makes the app worth having on the week at all — the
 * list cleans itself up from the account rather than from your memory.
 */
object BillTasks {

    /**
     * How far ahead of its due date a bill is put on the week.
     *
     * Ten days, which is a compromise between two failure modes. Too short and a bill that needs a
     * transfer to cover it appears with no time to make one. Too long and every week opens with next
     * month's bills on it, which trains people to ignore the list. Ten days means a bill is on the
     * week it is due and usually the one before, which is when somebody can still do something.
     */
    const val LEAD_DAYS = 10L

    /** What Finance stores on a bill about the task it published. */
    data class TaskLink(
        val taskId: String? = null,
        /** The due date we last published this bill for. Kept after the task goes, deliberately. */
        val publishedDue: LocalDate? = null
    ) {
        companion object {
            val NONE = TaskLink()
        }
    }

    /**
     * What LifeOps currently holds, as far as Finance can see it.
     *
     * [open] is "still actionable in LifeOps" — pending, queued for a future week, or marked to carry
     * forward. A task whose week closed without it being done is not open: it is stranded in a week
     * nobody can tick any more, which is different from one still waiting.
     */
    data class PublishedTask(
        val id: String,
        val title: String,
        val dueDate: LocalDate?,
        val completed: Boolean,
        val completedAtMillis: Long?,
        val open: Boolean = true
    )

    /** The one thing to do next about this bill's task. */
    sealed interface Action {
        /** Everything agrees. */
        data object Idle : Action

        /** Put it on the week: a task titled [title], due [due], carrying [note]. */
        data class Publish(val due: LocalDate, val title: String, val note: String) : Action

        /** Still open, but the date or the amount moved under it — a statement was re-read. */
        data class Reschedule(val taskId: String, val due: LocalDate, val title: String) : Action

        /**
         * Take it off the week. The bill was paid (the bank said so), deleted, or switched to not
         * publishing.
         */
        data class Retire(val taskId: String) : Action

        /** It was ticked in LifeOps. Mark the bill paid here. */
        data class MarkPaid(val taskId: String, val completedAtMillis: Long) : Action

        /** The task is not in LifeOps any more. Drop the link; don't put it back. */
        data object Forget : Action
    }

    /**
     * Decide what should happen to [bill]'s task, given what LifeOps holds ([task], null when the
     * link points at nothing).
     *
     * The order of the checks is the order of authority, and it is the whole of the logic:
     *
     * 1. **A payment that actually happened outranks everything.** If the bank says this bill is
     *    paid, the task comes off the week whatever state it is in — including if somebody has
     *    already ticked it, which is simply two ways of finding out the same thing.
     * 2. **A tick is a fact.** Nothing else here is allowed to override somebody saying they paid it.
     * 3. Everything after that is ordinary reconciliation.
     */
    fun decide(
        bill: Bills.Bill,
        accountName: String,
        link: TaskLink,
        task: PublishedTask?,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        currencySymbol: String = "$"
    ): Action {
        // 1. The bank's word. A paid bill has no business on anybody's week, ticked or not.
        if (bill.paid) {
            return if (task != null) Action.Retire(task.id) else Action.Idle
        }

        // 2. Somebody ticked it. Take that as payment and stop asking.
        if (task != null && task.completed) {
            return Action.MarkPaid(task.id, task.completedAtMillis ?: now)
        }

        // 3. The bill doesn't want a task: autopaid, or switched off by hand.
        if (!bill.publishToWeek) {
            return if (task != null) Action.Retire(task.id) else Action.Idle
        }

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val title = title(bill, accountName, currencySymbol)

        if (task == null) {
            // The link pointed at a task that is gone. Drop it, and — deliberately — do not put it
            // back for this due date: an app that silently re-adds what you just deleted is an app
            // you start deleting from twice. `publishedDue` is what remembers that.
            if (link.taskId != null) return Action.Forget
            // Too far out to be anybody's problem yet.
            if (bill.dueDate.isAfter(today.plusDays(LEAD_DAYS))) return Action.Idle
            // Already published for this date and the task was deleted — leave it alone.
            if (link.publishedDue == bill.dueDate) return Action.Idle
            return Action.Publish(
                due = bill.dueDate,
                title = title,
                note = note(bill, accountName, currencySymbol)
            )
        }

        // A task stranded in a closed week can't be ticked and can't be moved; let go of it so the
        // next occurrence of this payee publishes cleanly rather than trying to revive a fossil.
        if (!task.open) return Action.Forget

        val moved = task.dueDate != bill.dueDate || task.title != title
        return if (moved) Action.Reschedule(task.id, bill.dueDate, title) else Action.Idle
    }

    /**
     * What the task is called on the week.
     *
     * The amount is in the title rather than only in the note, because a week is read as a list of
     * one-liners and "Pay USAA Visa" is a different decision from "Pay USAA Visa — $1,240". A bill
     * whose amount is a prediction says "about", so the week never quotes a figure the app guessed as
     * though a biller had sent it.
     */
    fun title(bill: Bills.Bill, accountName: String, symbol: String = "$"): String {
        val amount = SuiteMoney.format(bill.amountCents, symbol, cents = false)
        val qualifier = if (bill.source == Bills.Source.PREDICTED) "about " else ""
        val payee = bill.payee.takeIf { it.isNotBlank() } ?: accountName
        return "Pay $payee — $qualifier$amount"
    }

    /** The note on the task: where the figure came from, and what it is being paid from. */
    fun note(bill: Bills.Bill, accountName: String, symbol: String = "$"): String {
        val lines = mutableListOf<String>()
        lines += when (bill.source) {
            Bills.Source.STATEMENT -> "From your $accountName statement."
            Bills.Source.MANUAL -> "You entered this in Finance."
            Bills.Source.PREDICTED -> "Predicted from your history — Finance has seen this before."
        }
        bill.minimumCents?.let {
            lines += "Statement balance ${SuiteMoney.format(bill.amountCents, symbol)}, " +
                "minimum ${SuiteMoney.format(it, symbol)}."
        }
        lines += "Finance will tick this off by itself when the payment lands."
        return lines.joinToString(" ")
    }
}
