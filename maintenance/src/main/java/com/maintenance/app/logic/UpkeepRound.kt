package com.maintenance.app.logic

import java.time.LocalDate
import java.time.ZoneId

/**
 * One plan as a round sees it: the plan, whose asset it is, where its meter stands, what the app
 * makes of it, and what the week planner currently holds for it.
 *
 * Assembled in one read before any decision is taken, so a round decides plan by plan in pure code
 * rather than interleaving decisions with database reads — which is how a round ends up meaning
 * something subtly different on a slow phone.
 */
data class PlanSnapshot(
    val plan: UpkeepPlan,
    val assetName: String,
    val meter: MeterState?,
    val link: UpkeepTasks.TaskLink,
    val verdict: DueVerdict
)

/**
 * The week planner, as the round needs it. Implemented against LifeOps' own task service; declared
 * here so the round can be driven — and tested — without one.
 */
interface UpkeepWeek {
    /** Put a task on the week. Null when the planner declined it (a title already there). */
    suspend fun publish(title: String, due: LocalDate, note: String): String?

    /** Move an open task's date, and fix its title. False when it is no longer there. */
    suspend fun reschedule(taskId: String, due: LocalDate, title: String): Boolean

    /** Take a task off the week. */
    suspend fun retire(taskId: String): Boolean

    /** What the planner holds for a task, following any carry-forward hop. Null when it is gone. */
    suspend fun state(taskId: String): UpkeepTasks.PublishedTask?
}

/** Maintenance's own store, as the round needs it. */
interface UpkeepStore {
    suspend fun planSnapshots(now: Long): List<PlanSnapshot>
    suspend fun setPlanLink(planId: String, taskId: String?, publishedDue: LocalDate?)
    /** Log the service the tick stands for, move the clock, let go of the task. */
    suspend fun completeFromWeek(planId: String, completedAt: Long): Boolean
}

/**
 * The round that keeps Maintenance's schedules and the LifeOps week in step.
 *
 * It is a **reconciliation, not an event handler**, and that is the whole design. LifeOps announces
 * a tick the moment it happens and this round runs on the back of it — but the same round also runs
 * when Maintenance comes to the foreground and after every edit to a schedule, and it reaches the
 * same answer either way. So a tick that arrived while the database was mid-restore, a task somebody
 * deleted, or a week that closed and carried the task into a new row under a new id are all just
 * facts the next round reads and acts on. Nothing depends on having caught a particular moment.
 *
 * Each pass reads what is true, asks [UpkeepTasks] what should happen to each plan, and does that
 * one thing. The only action that changes what the *next* answer would be is a completion — it moves
 * the plan's clock, and so its due date — which is why a pass that completed something is followed
 * by exactly one more, to publish the next occurrence. Two passes, bounded; no loop that can chase
 * its own tail.
 */
class UpkeepRound(
    private val week: UpkeepWeek,
    private val store: UpkeepStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * Called when a pass actually puts something on the week. The app uses it to record that this
     * install has published at all — the cheap gate that keeps an unused Maintenance from opening
     * its database every time a task is ticked anywhere in the suite.
     */
    private val onPublished: () -> Unit = {}
) {

    /** What a round did. For the log and for tests; nothing on screen depends on it. */
    data class Report(
        val published: Int = 0,
        val rescheduled: Int = 0,
        val retired: Int = 0,
        val completed: Int = 0,
        val forgotten: Int = 0
    ) {
        val didAnything: Boolean
            get() = published + rescheduled + retired + completed + forgotten > 0

        operator fun plus(other: Report) = Report(
            published + other.published,
            rescheduled + other.rescheduled,
            retired + other.retired,
            completed + other.completed,
            forgotten + other.forgotten
        )
    }

    suspend fun run(now: Long): Report {
        var total = Report()
        repeat(MAX_PASSES) {
            val pass = onePass(now)
            total += pass
            if (pass.published > 0) onPublished()
            // Everything but a completion has already converged; a completion has just changed when
            // this plan is next due, and that wants publishing.
            if (pass.completed == 0) return total
        }
        return total
    }

    private suspend fun onePass(now: Long): Report {
        var report = Report()
        val snapshots = store.planSnapshots(now)

        // The task ids already standing for a plan. Publishing *adopts* an open task of the same
        // title rather than adding a second beside it, which is right when the task is one you wrote
        // by hand — and wrong if it already belongs to another plan. Two schedules named the same
        // thing on one asset would otherwise quietly share a row, and one tick would complete both.
        // The loser of that race simply goes without a task until it is renamed.
        val claimed = snapshots.mapNotNullTo(mutableSetOf()) { it.link.taskId }

        for (item in snapshots) {
            val planId = item.plan.id
            val stored = item.link
            val task = stored.taskId?.let { week.state(it) }

            // A week that closed without the job being done mints a new row for the new week. Follow
            // the hop first, so everything below is talking about the task that actually exists now.
            val link = if (task != null && task.id != stored.taskId) {
                store.setPlanLink(planId, task.id, stored.publishedDue)
                stored.copy(taskId = task.id)
            } else {
                stored
            }

            val action = UpkeepTasks.decide(
                plan = item.plan,
                assetName = item.assetName,
                verdict = item.verdict,
                link = link,
                task = task,
                now = now,
                zone = zone,
                meterUnit = item.meter?.unit
            )

            when (action) {
                is UpkeepTasks.Action.Idle -> Unit

                is UpkeepTasks.Action.Publish -> {
                    // `add` returns false when the id is already spoken for — see [claimed].
                    val taskId = week.publish(action.title, action.due, action.note)?.takeIf { claimed.add(it) }
                    // The occurrence is recorded as published even when nothing came back, so the
                    // round doesn't try again on every pass against a week that isn't going to
                    // give it a task.
                    store.setPlanLink(planId, taskId, action.due)
                    if (taskId != null) report = report.copy(published = report.published + 1)
                }

                is UpkeepTasks.Action.Reschedule -> {
                    if (week.reschedule(action.taskId, action.due, action.title)) {
                        store.setPlanLink(planId, action.taskId, action.due)
                        report = report.copy(rescheduled = report.rescheduled + 1)
                    } else {
                        // It went away between the read and the write. Drop the link and let the
                        // next round decide about it from scratch.
                        store.setPlanLink(planId, null, link.publishedDue)
                        report = report.copy(forgotten = report.forgotten + 1)
                    }
                }

                is UpkeepTasks.Action.Retire -> {
                    week.retire(action.taskId)
                    // Both halves of the link go: a plan switched back on should put itself on the
                    // week again rather than think it already has.
                    store.setPlanLink(planId, null, null)
                    report = report.copy(retired = report.retired + 1)
                }

                is UpkeepTasks.Action.MarkDone -> {
                    if (store.completeFromWeek(planId, action.completedAtMillis)) {
                        report = report.copy(completed = report.completed + 1)
                    }
                }

                is UpkeepTasks.Action.Forget -> {
                    // The occurrence stays remembered on purpose: it is what stops the app putting
                    // back a task you deleted.
                    store.setPlanLink(planId, null, link.publishedDue)
                    report = report.copy(forgotten = report.forgotten + 1)
                }
            }
        }

        return report
    }

    private companion object {
        /** One pass to converge, one more to publish whatever a completion in it made due. */
        const val MAX_PASSES = 2
    }
}
