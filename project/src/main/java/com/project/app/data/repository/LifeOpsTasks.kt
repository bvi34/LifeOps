package com.project.app.data.repository

import com.lifeops.app.LifeOpsApp
import com.lifeops.app.connection.service.TaskService
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.data.repository.TaskRepository
import com.project.app.logic.CardTasks
import com.project.app.logic.CardWeek
import java.time.Instant
import java.time.LocalDate

/**
 * Project's write/read bridge into the LifeOps week.
 *
 * Both apps are library modules in one process, so "publish a task" is a call into LifeOps' own
 * [TaskService] rather than a copy of a task living over here. There is one week planner in the
 * suite and this is a door into it, not a second one — which is the whole point of Project having a
 * due date at all: it says when a card is due and hands that to the app that decides when you will
 * do it.
 *
 * Everything here is defensive about LifeOps not being there. [LifeOpsApp.getOrNull] is null in a
 * host that never installed it, and the bridge is then never built — Project keeps its boards and
 * its dates; it simply stops putting them on a week that does not exist.
 *
 * Two things this deliberately does **not** do, both borrowed from Maintenance's bridge because the
 * reasons are identical:
 *
 * - **It never sets `isRecurring`.** A card happens once. Letting LifeOps repeat it would put a
 *   second engine in charge of a date this app owns.
 * - **It never sets a hard deadline.** A hard deadline expires the task at week close, which would
 *   quietly bin a card that simply did not get done that week — and the card is still sitting on the
 *   board asking for it.
 */
class LifeOpsTasks(
    private val taskService: TaskService,
    private val taskRepository: TaskRepository
) : CardWeek {

    /**
     * Put a task on the week, dated [due] — **adopting** an open one with the same title rather than
     * adding a second beside it.
     *
     * If you had already written "The Kestrel: Rewrite the dock scene" onto your week by hand, the
     * honest answer is that it *is* the job, so it gets adopted and ticking the row you wrote ticks
     * the one Project is watching. Two rows for one piece of work is the worse outcome.
     *
     * `allowDuplicateTitle` is set for the create that follows, because LifeOps' duplicate rule
     * looks at every task in the current week *including completed ones* — and a card whose date
     * was moved after being finished and reopened would otherwise be silently swallowed. Anything
     * worth adopting has already been adopted a line above; what is left is a collision with a
     * finished row, which must not stop this card reaching a week.
     */
    override suspend fun publish(title: String, due: LocalDate, note: String): String? {
        taskService.findOpen(title)?.let { return it.id }

        val outcome = taskService.create(
            TaskService.CreateInput(
                title = title,
                note = note,
                dueDate = due.toString(),
                // Not recurring, not a hard deadline — see the class note.
                isRecurring = false,
                hardDeadline = false,
                allowDuplicateTitle = true
            )
        )
        return (outcome as? TaskService.CreateOutcome.Created)?.task?.id
    }

    /**
     * Move an open task's date, and fix its title when the card or the project was renamed.
     *
     * The aspect is deliberately not passed: `TaskService.update` leaves a field alone when it is
     * null, so a task you re-filed by hand in LifeOps stays where you put it. Project owns the title
     * and the date because it is the only thing that knows them; it does not own your filing.
     */
    override suspend fun reschedule(taskId: String, due: LocalDate, title: String): Boolean =
        taskService.update(taskId = taskId, title = title, dueDate = due.toString()) != null

    /** Take a task off the week — the card stopped wanting it. */
    override suspend fun retire(taskId: String): Boolean = taskService.delete(taskId)

    /**
     * What LifeOps holds for [taskId] — following carry-forward hops.
     *
     * When a week closes without the work being done and the task was marked to carry, LifeOps mints
     * a **new row** for the new week. The id Project stored then points at a completed-looking
     * fossil rather than at the live task, so the chain is walked to its end and the live one comes
     * back; the round re-points the card's link at whatever id it gets.
     *
     * Null means LifeOps has no such task at all — it was deleted.
     */
    override suspend fun state(taskId: String): CardTasks.PublishedTask? {
        var current = taskRepository.getById(taskId) ?: return null
        var hops = 0
        while (hops < MAX_CARRY_HOPS) {
            current = taskRepository.childOf(current.id) ?: break
            hops++
        }
        return CardTasks.PublishedTask(
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
         * A task carried week after week is a real thing; a cycle in the chain is not, but a corrupt
         * row could make one. The walk is bounded so a round can never hang on it.
         */
        private const val MAX_CARRY_HOPS = 260

        /** Still actionable in LifeOps: this week's list, the future queue, or marked to carry. */
        private val OPEN_STATUSES = setOf(
            TaskStatus.PENDING,
            TaskStatus.QUEUED,
            TaskStatus.CARRIED_FORWARD
        )

        /**
         * The bridge, or null when LifeOps is not installed in this process. Null is a normal
         * answer rather than a failure: Project works without a week planner behind it.
         */
        fun createOrNull(): LifeOpsTasks? {
            val lifeOps = LifeOpsApp.getOrNull() ?: return null
            return LifeOpsTasks(
                taskService = lifeOps.taskService,
                taskRepository = lifeOps.taskRepository
            )
        }
    }
}
