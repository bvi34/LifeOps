package com.maintenance.app.data.repository

import com.lifeops.app.LifeOpsApp
import com.lifeops.app.connection.service.TaskService
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.data.repository.TaskRepository
import com.maintenance.app.logic.UpkeepTasks
import com.maintenance.app.logic.UpkeepWeek
import java.time.Instant
import java.time.LocalDate

/**
 * Maintenance's write/read bridge into LifeOps' week.
 *
 * The same seam Logistics uses for LifeOps' food catalog, pointed at tasks: both apps are library
 * modules in one process, so "publish a task" is a call into LifeOps' own [TaskService] rather than
 * a copy of a task living over here. There is one week planner in the suite, and this is a door
 * into it, not a second one.
 *
 * Everything here is defensive about LifeOps not being there. [LifeOpsApp.getOrNull] is null in a
 * host that never installed it, and every method then no-ops — Maintenance still keeps its
 * schedules and its docket; it simply stops putting them on a week that doesn't exist.
 *
 * Two things this deliberately does **not** do:
 *
 * - **It never sets `isRecurring`.** LifeOps can repeat a task on its own cadence, and a plan that
 *   used it would have two engines deciding when the next oil change is. Maintenance owns the
 *   cadence; each occurrence is published as a one-off, and the next is published when this one is
 *   ticked.
 * - **It never sets a hard deadline.** A hard deadline expires the task at week close, which would
 *   quietly bin a job that just didn't get done that week.
 */
class LifeOpsTasks(
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
    /**
     * The aspect a published task is filed under, read from LifeOps' own settings on each publish.
     *
     * Which part of your life an upkeep job counts towards is **LifeOps' decision, not this app's** —
     * exactly as it is for the reading time Citation reports (`Settings → Reading rewards`). So this
     * is a lambda over a LifeOps preference rather than a field Maintenance keeps: there is one
     * place to change it, and it is the place that owns aspects.
     */
    private val aspectId: () -> String? = { null }
) : UpkeepWeek {

    /**
     * Put a task on the week, dated [due]. Returns its id, or null when LifeOps declined it.
     *
     * The decline is real and worth handling rather than asserting away: LifeOps skips a create
     * whose title collides with an existing task in the same week, so a job you had already written
     * onto the week by hand keeps its own row instead of gaining a duplicate. The caller records the
     * occurrence as published either way, so the round doesn't retry on every pass.
     */
    override suspend fun publish(title: String, due: LocalDate, note: String): String? {
        val outcome = taskService.create(
            TaskService.CreateInput(
                title = title,
                note = note,
                dueDate = due.toString(),
                // Whatever LifeOps' settings say today. Read at publish rather than stamped on the
                // plan, so the answer comes from one place — and so changing it re-files what is
                // published from then on without reaching back into weeks already planned.
                aspectId = aspectId(),
                // Not recurring, not a hard deadline — see the class note.
                isRecurring = false,
                hardDeadline = false
            )
        )
        return (outcome as? TaskService.CreateOutcome.Created)?.task?.id
    }

    /**
     * Move an open task's date (and fix its title when the plan or the asset was renamed).
     *
     * The aspect is deliberately **not** passed: `TaskService.update` leaves a field alone when it
     * is null, so a task you re-filed by hand in LifeOps keeps where you put it. The app owns the
     * title and the date because it is the only thing that knows them; it does not own your filing.
     */
    override suspend fun reschedule(taskId: String, due: LocalDate, title: String): Boolean =
        taskService.update(taskId = taskId, title = title, dueDate = due.toString()) != null

    /** Take a task off the week — the plan stopped wanting it. */
    override suspend fun retire(taskId: String): Boolean = taskService.delete(taskId)

    /**
     * What LifeOps holds for [taskId] — following carry-forward hops.
     *
     * When a week closes without the job being done and the task was marked to carry, LifeOps mints
     * a **new row** for the new week. The id Maintenance stored then points at a completed-looking
     * fossil rather than at the live task, so the chain is walked to its end and the live one comes
     * back; the caller re-points its link at whatever id it gets.
     *
     * Null means LifeOps has no such task at all — it was deleted.
     */
    override suspend fun state(taskId: String): UpkeepTasks.PublishedTask? {
        var current = taskRepository.getById(taskId) ?: return null
        var hops = 0
        while (hops < MAX_CARRY_HOPS) {
            current = taskRepository.childOf(current.id) ?: break
            hops++
        }
        return UpkeepTasks.PublishedTask(
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
         * A task carried week after week is a real thing; a cycle in the chain is not, but a
         * corrupt row could make one. The walk is bounded so a round can never hang on it.
         */
        private const val MAX_CARRY_HOPS = 260

        /** Still actionable in LifeOps: this week's list, the future queue, or marked to carry. */
        private val OPEN_STATUSES = setOf(
            TaskStatus.PENDING,
            TaskStatus.QUEUED,
            TaskStatus.CARRIED_FORWARD
        )

        /**
         * The bridge, or null when LifeOps isn't installed in this process. Null is a normal
         * answer, not a failure: Maintenance works without a week planner behind it.
         */
        fun createOrNull(): LifeOpsTasks? {
            val lifeOps = LifeOpsApp.getOrNull() ?: return null
            return LifeOpsTasks(
                taskService = lifeOps.taskService,
                taskRepository = lifeOps.taskRepository,
                aspectId = { lifeOps.preferencesRepository.maintenanceAspectId }
            )
        }
    }
}
