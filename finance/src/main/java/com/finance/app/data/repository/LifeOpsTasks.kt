package com.finance.app.data.repository

import com.finance.app.logic.BillTasks
import com.finance.app.logic.BillWeek
import com.lifeops.app.LifeOpsApp
import com.lifeops.app.connection.service.TaskService
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.data.repository.TaskRepository
import java.time.Instant
import java.time.LocalDate

/**
 * Finance's write/read bridge into LifeOps' week.
 *
 * The same seam Maintenance uses for upkeep, pointed at bills: both apps are library modules in one
 * process, so "publish a task" is a call into LifeOps' own [TaskService] rather than a copy of a
 * task living over here. There is one week planner in the suite, and this is a door into it, not a
 * second one.
 *
 * Everything here is defensive about LifeOps not being there. [LifeOpsApp.getOrNull] is null in a
 * host that never installed it, and every method then no-ops — Finance still keeps its accounts and
 * its due list; it simply stops putting them on a week that doesn't exist.
 *
 * Two things this deliberately does **not** do, both inherited from Maintenance's version and both
 * for reasons that apply at least as strongly to money:
 *
 * - **It never sets `isRecurring`.** LifeOps can repeat a task on its own cadence, and a bill that
 *   used it would have two engines deciding when the rent is next due. Finance owns the cadence —
 *   or rather the bank does, and Finance reads it — so each occurrence is published as a one-off.
 * - **It never sets a hard deadline.** A hard deadline expires the task at week close, which for an
 *   unpaid bill would mean the app quietly forgetting something that is now late.
 */
class LifeOpsTasks(
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
    /**
     * The aspect a published task is filed under, read from LifeOps' own settings on each publish.
     *
     * Which part of your life paying a bill counts towards is **LifeOps' decision, not this app's** —
     * exactly as it is for Maintenance's upkeep and Citation's reading time. So this is a lambda over
     * a LifeOps preference rather than a field Finance keeps: there is one place to change it, and it
     * is the place that owns aspects.
     */
    private val aspectId: () -> String? = { null }
) : BillWeek {

    /**
     * Put a task on the week, dated [due] — **adopting** an open one with the same title rather than
     * adding a second beside it. Returns the task's id, or null if LifeOps refused outright.
     *
     * *Adopt*, because if you had already written "Pay the mortgage" onto your week by hand, the
     * honest answer is that it is the job — so it gets adopted, and ticking the row you wrote ticks
     * the one Finance is watching. Two rows for one bill is the worse outcome.
     *
     * *Bypass the title check* for the create that follows, because LifeOps' duplicate rule looks at
     * every task in the current week **including completed ones**, and a future-dated task lives in
     * the current week until it closes. Pay this month's card and next month's bill is published a
     * few weeks later under the same title — which the check would silently swallow. De-duplication
     * here is by the task id Finance keeps, which is what `allowDuplicateTitle` is for.
     */
    override suspend fun publish(title: String, due: LocalDate, note: String): String? {
        taskService.findOpen(title)?.let { return it.id }

        val outcome = taskService.create(
            TaskService.CreateInput(
                title = title,
                note = note,
                dueDate = due.toString(),
                // Whatever LifeOps' settings say today. Read at publish rather than stamped on the
                // bill, so the answer comes from one place — and so changing it re-files what is
                // published from then on without reaching back into weeks already planned.
                aspectId = aspectId(),
                // Not recurring, not a hard deadline — see the class note.
                isRecurring = false,
                hardDeadline = false,
                // We adopted above if there was anything to adopt; anything left is a title
                // collision with a *finished* row, which must not swallow next month's bill.
                allowDuplicateTitle = true
            )
        )
        return (outcome as? TaskService.CreateOutcome.Created)?.task?.id
    }

    /**
     * Move an open task's date, and fix its title when the statement was re-read.
     *
     * The aspect is deliberately **not** passed: `TaskService.update` leaves a field alone when it is
     * null, so a task you re-filed by hand in LifeOps keeps where you put it. The app owns the title
     * and the date because it is the only thing that knows them; it does not own your filing.
     */
    override suspend fun reschedule(taskId: String, due: LocalDate, title: String): Boolean =
        taskService.update(taskId = taskId, title = title, dueDate = due.toString()) != null

    /** Take a task off the week — the bill was paid, deleted, or switched to not publishing. */
    override suspend fun retire(taskId: String): Boolean = taskService.delete(taskId)

    /**
     * What LifeOps holds for [taskId] — following carry-forward hops.
     *
     * When a week closes without the bill being paid and the task was marked to carry, LifeOps mints
     * a **new row** for the new week. The id Finance stored then points at a completed-looking fossil
     * rather than at the live task, so the chain is walked to its end and the live one comes back;
     * the round re-points its link at whatever id it gets.
     *
     * Null means LifeOps has no such task at all — it was deleted.
     */
    override suspend fun state(taskId: String): BillTasks.PublishedTask? {
        var current = taskRepository.getById(taskId) ?: return null
        var hops = 0
        while (hops < MAX_CARRY_HOPS) {
            current = taskRepository.childOf(current.id) ?: break
            hops++
        }
        return BillTasks.PublishedTask(
            id = current.id,
            title = current.title,
            dueDate = current.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            completed = current.status == TaskStatus.COMPLETED,
            completedAtMillis = current.completedAt?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            },
            open = current.status in OPEN_STATUSES
        )
    }

    companion object {
        /**
         * A bill carried week after week is a real and slightly sad thing; a cycle in the chain is
         * not, but a corrupt row could make one. The walk is bounded so a round can never hang on it.
         */
        private const val MAX_CARRY_HOPS = 260

        /** Still actionable in LifeOps: this week's list, the future queue, or marked to carry. */
        private val OPEN_STATUSES = setOf(
            TaskStatus.PENDING,
            TaskStatus.QUEUED,
            TaskStatus.CARRIED_FORWARD
        )

        /**
         * The bridge, or null when LifeOps isn't installed in this process. Null is a normal answer,
         * not a failure: Finance works without a week planner behind it.
         */
        fun createOrNull(): LifeOpsTasks? {
            val lifeOps = LifeOpsApp.getOrNull() ?: return null
            return LifeOpsTasks(
                taskService = lifeOps.taskService,
                taskRepository = lifeOps.taskRepository,
                aspectId = { lifeOps.preferencesRepository.financeAspectId }
            )
        }
    }
}
