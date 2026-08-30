package com.maintenance.app.logic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The seam between an upkeep plan and the week it belongs on.
 *
 * Maintenance knows *when* a thing is due; LifeOps is where the week is planned. So a plan doesn't
 * grow a notification here — it publishes a task over there, dated the day it falls due, which
 * LifeOps parks in its Future Tasks queue until the week containing that date opens. Tick it in
 * LifeOps and the tick comes back: the service is logged here and the clock starts again.
 *
 * This file is the *decision*, not the plumbing. Given what the plan wants and what LifeOps
 * currently holds, it says which single thing should happen next; the bridge does it. Keeping it
 * pure is what makes the awkward cases — a task somebody deleted, a plan that got paused, a week
 * that carried the task forward — testable without a database or an emulator.
 *
 * The awkward cases, and the position taken on each:
 *
 * - **A task that has gone** (deleted in LifeOps) is a decision, not an error. The link is dropped
 *   and the task is *not* put back for that occurrence — an app that silently re-adds what you just
 *   deleted is an app you start deleting from twice. The next occurrence publishes normally.
 * - **A carried-forward task is still the same job.** LifeOps mints a new row when a week rolls over
 *   without the work being done; following that hop is the bridge's business, so by the time a
 *   [PublishedTask] arrives here it is the live one.
 * - **A completed task always wins**, whatever else is out of step. The tick is the fact; the
 *   rescheduling can wait for the next pass.
 */
object UpkeepTasks {

    /** What Maintenance stores on a plan about the task it published. */
    data class TaskLink(
        val taskId: String? = null,
        /** The date we last published this plan for. Kept even after the task goes, deliberately. */
        val publishedDue: LocalDate? = null
    ) {
        companion object {
            val NONE = TaskLink()
        }
    }

    /**
     * What LifeOps currently holds, as far as Maintenance can see it.
     *
     * [open] is "still actionable in LifeOps" — pending, queued for a future week, or marked to
     * carry forward. A task whose week closed without it being done is *not* open: it is stranded
     * in a week nobody can tick any more, which is a different situation from a task still waiting.
     */
    data class PublishedTask(
        val id: String,
        val title: String,
        val dueDate: LocalDate?,
        val completed: Boolean,
        val completedAtMillis: Long?,
        val open: Boolean = true
    )

    /** The one thing to do next about this plan's task. */
    sealed interface Action {
        /** Everything agrees. */
        data object Idle : Action

        /** Put it on the week: a task titled [title], due [due], carrying [note]. */
        data class Publish(val due: LocalDate, val title: String, val note: String) : Action

        /** The task is still open but the date (or the name) has moved under it. */
        data class Reschedule(val taskId: String, val due: LocalDate, val title: String) : Action

        /** The plan stopped wanting a task — paused, unpublished, or its schedule removed. */
        data class Retire(val taskId: String) : Action

        /** It was ticked in LifeOps. Log the service here and start the next one. */
        data class MarkDone(val taskId: String, val completedAtMillis: Long) : Action

        /** The task is not in LifeOps any more. Drop the link; don't put it back. */
        data object Forget : Action
    }

    /**
     * Decide what should happen to [plan]'s task, given the app's [verdict] on it and what LifeOps
     * holds ([task], null when the link points at nothing).
     *
     * [now] is the clock, and [zone] turns the due *instant* into the due *day* — the only unit
     * LifeOps dates a task in.
     */
    fun decide(
        plan: UpkeepPlan,
        assetName: String,
        verdict: DueVerdict,
        link: TaskLink,
        task: PublishedTask?,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        meterUnit: MeterUnit? = null
    ): Action {
        // A tick is a fact and outranks everything else that might be out of step.
        if (task != null && task.completed) {
            return Action.MarkDone(task.id, task.completedAtMillis ?: now)
        }

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val target = targetDue(verdict, today, zone)
        val wanted = plan.active && plan.publishToLifeOps && target != null

        if (!wanted) {
            // Nothing should be on the week. Take down what is still standing; a task stranded in a
            // closed week is left exactly where it is — deleting it would edit a week that has
            // already been reviewed.
            return when {
                task != null && task.open -> Action.Retire(task.id)
                link.taskId != null -> Action.Forget
                else -> Action.Idle
            }
        }

        // The link names a task LifeOps no longer has.
        if (link.taskId != null && task == null) return Action.Forget

        val title = title(assetName, plan)

        // The week it was on closed without it being done. The job still needs doing, so it goes on
        // *this* week rather than sitting in a closed one nobody can tick.
        if (task != null && !task.open) {
            return Action.Publish(target!!, title, note(plan, verdict, meterUnit))
        }

        if (task == null) {
            // Already published for this occurrence and it isn't there any more: somebody deleted
            // it on purpose. Wait for the plan to move on rather than arguing about it.
            if (link.publishedDue == target) return Action.Idle
            return Action.Publish(target!!, title, note(plan, verdict, meterUnit))
        }

        return if (task.dueDate != target || task.title != title) {
            Action.Reschedule(task.id, target!!, title)
        } else {
            Action.Idle
        }
    }

    /**
     * The day a plan's task should be dated.
     *
     * Normally the day the verdict falls due. A plan that is **overdue with no date** — a mileage
     * interval with no rate behind it yet — is dated today instead: it wants doing, and a task with
     * no due date at all would land in this week looking like something you chose to plan.
     */
    fun targetDue(verdict: DueVerdict, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
        // `Instant.atZone(…).toLocalDate()` rather than `LocalDate.ofInstant`, which is a Java 9
        // API and minSdk here is 26 — java.time as API 26 ships it stops at Java 8.
        verdict.dueAt?.let { return Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        return if (verdict.status == DueStatus.OVERDUE) today else null
    }

    /**
     * "Truck: Oil change".
     *
     * The asset leads, because a week's task list is read across a dozen unrelated things and
     * "Oil change" on its own is a question rather than a job.
     */
    fun title(assetName: String, plan: UpkeepPlan): String = "${assetName.trim()}: ${plan.title.trim()}"

    /**
     * The note that rides along on the task, so somebody looking at it in LifeOps a month later
     * knows where it came from and what ticking it will do.
     */
    fun note(plan: UpkeepPlan, verdict: DueVerdict, meterUnit: MeterUnit? = null): String {
        val cadence = buildList {
            plan.everyMeter?.let { add("every ${meterUnit?.format(it) ?: MeterUnit.group(it)}") }
            plan.everyDays?.let { add("every ${days(it)}") }
        }.joinToString(" or ")

        return buildString {
            append("From Maintenance")
            if (cadence.isNotBlank()) append(" — $cadence")
            append(". ")
            append(verdict.summary)
            append(". Ticking this here logs it there and starts the next one.")
        }
    }

    private fun days(count: Int): String = when {
        count % 365 == 0 -> if (count == 365) "year" else "${count / 365} years"
        count % 30 == 0 -> if (count == 30) "month" else "${count / 30} months"
        count % 7 == 0 -> if (count == 7) "week" else "${count / 7} weeks"
        count == 1 -> "day"
        else -> "$count days"
    }
}
