package com.lifeops.app.connection.service

import com.lifeops.app.data.model.Priority
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.data.repository.NotificationRepository
import com.lifeops.app.data.repository.TaskNoteRepository
import com.lifeops.app.data.repository.TaskRepository
import com.lifeops.app.data.repository.WeekRepository
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ImportParser
import com.lifeops.app.util.toSlug
import java.util.UUID

/**
 * Use-case layer for tasks. Sits between the connection route handlers and the repositories,
 * holding the orchestration that used to live inline in the ViewModel (current-week resolution,
 * slug de-duplication, resource-value scoring, queued-vs-pending placement, note attachment,
 * notification scheduling). Repositories remain the source of truth for persistence; this class
 * owns the *policy* of a task lifecycle so every caller — a screen or a connection function —
 * gets identical behaviour.
 */
class TaskService(
    private val taskRepository: TaskRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val notificationRepository: NotificationRepository,
    private val weekRepository: WeekRepository
) {

    /** Fields for creating a task. Only [title] is required. */
    data class CreateInput(
        val title: String,
        val note: String? = null,
        val aspectId: String? = null,
        val categoryId: String? = null,
        val priority: Priority = Priority.MEDIUM,
        val dueDate: String? = null,
        val hardDeadline: Boolean = false,
        val isRecurring: Boolean = false,
        val estimatedMinutes: Int? = null,
        val operationId: String? = null,
        val counterId: String? = null,
        val recurrenceIntervalWeeks: Int = 1,
        val recurrenceDayOfMonth: Int? = null
    )

    /** Outcome of a create call. A same-week slug collision yields [DuplicateSkipped]. */
    sealed interface CreateOutcome {
        data class Created(val task: Task) : CreateOutcome
        data class DuplicateSkipped(val slug: String) : CreateOutcome
    }

    suspend fun create(input: CreateInput): CreateOutcome {
        val title = input.title.trim()
        require(title.isNotBlank()) { "Task title must not be blank" }

        val week = weekRepository.getOrCreateCurrentWeek()
        val slug = title.toSlug()
        // Incumbent survives: a duplicate title in the same week is skipped, not overwritten.
        if (slug in taskRepository.getSlugsByWeek(week.id)) {
            return CreateOutcome.DuplicateSkipped(slug)
        }

        val resourceValue = ImportParser.computeResourceValue(
            input.priority.label, input.hardDeadline, input.estimatedMinutes, isManuallyAdded = true
        )
        // Due beyond this week? Park it in the Future Tasks queue; it wakes to pending once a week
        // containing its due date opens (mirrors TaskRepository week-close behaviour).
        val validDueDate = input.dueDate?.takeIf { DateUtil.isValidDate(it) }
        val status = if (validDueDate != null && validDueDate > week.endDate) TaskStatus.QUEUED
                     else TaskStatus.PENDING

        val task = Task(
            id = UUID.randomUUID().toString(),
            weekId = week.id,
            title = title,
            aspectId = input.aspectId,
            categoryId = input.categoryId,
            priority = input.priority,
            dueDate = validDueDate,
            hardDeadline = input.hardDeadline,
            status = status,
            resourceValue = resourceValue,
            createdAt = DateUtil.now(),
            isRecurring = input.isRecurring,
            estimatedMinutes = input.estimatedMinutes,
            isManuallyAdded = true,
            operationId = input.operationId,
            slug = slug,
            counterId = input.counterId,
            recurrenceIntervalWeeks = if (input.isRecurring) input.recurrenceIntervalWeeks.coerceAtLeast(1) else 1,
            recurrenceDayOfMonth = if (input.isRecurring) input.recurrenceDayOfMonth else null
        )
        taskRepository.upsertTask(task)
        input.note?.takeIf { it.isNotBlank() }?.let { taskNoteRepository.addNote(task.id, it) }
        notificationRepository.scheduleForTask(task)
        return CreateOutcome.Created(task)
    }

    /**
     * Partial update: each non-null field replaces the current value; nulls leave the field as-is.
     * The task's resource value is recomputed and its reminder rescheduled so scoring and
     * notifications stay consistent with the edit. Returns the updated task, or `null` if no task
     * has [taskId].
     */
    suspend fun update(
        taskId: String,
        title: String? = null,
        aspectId: String? = null,
        categoryId: String? = null,
        priority: Priority? = null,
        dueDate: String? = null,
        hardDeadline: Boolean? = null,
        estimatedMinutes: Int? = null,
        operationId: String? = null
    ): Task? {
        val current = taskRepository.getById(taskId) ?: return null

        val newTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: current.title
        val newPriority = priority ?: current.priority
        val newHardDeadline = hardDeadline ?: current.hardDeadline
        val newEstimate = estimatedMinutes ?: current.estimatedMinutes
        val newDueDate = dueDate?.takeIf { DateUtil.isValidDate(it) } ?: current.dueDate

        val updated = current.copy(
            title = newTitle,
            slug = newTitle.toSlug(),
            aspectId = aspectId ?: current.aspectId,
            categoryId = categoryId ?: current.categoryId,
            priority = newPriority,
            dueDate = newDueDate,
            hardDeadline = newHardDeadline,
            estimatedMinutes = newEstimate,
            operationId = operationId ?: current.operationId,
            resourceValue = ImportParser.computeResourceValue(
                newPriority.label, newHardDeadline, newEstimate,
                isManuallyAdded = current.isManuallyAdded
            )
        )
        taskRepository.upsertTask(updated)
        // Re-arm the reminder against the possibly-changed due date / hard-deadline flag.
        notificationRepository.cancelForTask(updated.id)
        notificationRepository.scheduleForTask(updated)
        return updated
    }

    /** Mark a task complete. Returns `false` when no task has [taskId]. */
    suspend fun complete(taskId: String): Boolean {
        val task = taskRepository.getById(taskId) ?: return false
        taskRepository.completeTask(task)
        return true
    }

    /** Delete a task and cancel its reminders. Returns `false` when no task has [taskId]. */
    suspend fun delete(taskId: String): Boolean {
        taskRepository.getById(taskId) ?: return false
        taskRepository.deleteTask(taskId)
        return true
    }
}
