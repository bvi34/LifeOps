package com.lifeops.app.util

import com.lifeops.app.data.db.entities.*
import com.lifeops.app.data.model.*

fun AspectEntity.toModel() = Aspect(id, name, color, icon, isArchived)
fun Aspect.toEntity() = AspectEntity(id, name, color, icon, isArchived)

fun CategoryEntity.toModel() = Category(id, aspectId, name, isArchived)
fun Category.toEntity() = CategoryEntity(id, aspectId, name, isArchived)

fun WeekEntity.toModel() = Week(id, startDate, endDate, isClosed, closedAt)
fun Week.toEntity() = WeekEntity(id, startDate, endDate, isClosed, closedAt)

fun TaskEntity.toModel() = Task(
    id, weekId, title, aspectId, categoryId,
    Priority.from(priority), dueDate, hardDeadline,
    TaskStatus.from(status), resourceValue, completedAt, carriedFromTaskId, createdAt,
    isRecurring, estimatedMinutes, carriedCount, sortOrder, isManuallyAdded, projectId,
    TaskSource.from(source), slug
)

fun Task.toEntity() = TaskEntity(
    id, weekId, title, aspectId, categoryId,
    priority.label, dueDate, hardDeadline,
    status.value, resourceValue, completedAt, carriedFromTaskId, createdAt,
    isRecurring, estimatedMinutes, carriedCount, sortOrder, isManuallyAdded, projectId,
    source.name, slug.ifEmpty { title.toSlug() }
)

fun TaskNoteEntity.toModel() = TaskNote(id, taskId, content, createdAt, subtaskId)
fun TaskNote.toEntity() = TaskNoteEntity(id, taskId, content, createdAt, subtaskId)

fun TimeEntryEntity.toModel() = TimeEntry(id, taskId, durationMinutes, note, recordedAt, subtaskId)
fun TimeEntry.toEntity() = TimeEntryEntity(id, taskId, durationMinutes, note, recordedAt, subtaskId)

fun GameResourceEntity.toModel() = GameResource(id, name, currentValue, lifetimeEarned, slotIndex)
fun GameResource.toEntity() = GameResourceEntity(id, name, currentValue, lifetimeEarned, slotIndex)

fun GameResourceMappingEntity.toModel() = GameResourceMapping(id, gameResourceId, aspectId, weight)
fun GameResourceMapping.toEntity() = GameResourceMappingEntity(id, gameResourceId, aspectId, weight)

fun ResourceTransactionEntity.toModel() = ResourceTransaction(id, resourceId, amount, type, note, createdAt)
fun ResourceTransaction.toEntity() = ResourceTransactionEntity(id, resourceId, amount, type, note, createdAt)

fun CostResourceEntity.toModel() = CostResource(
    id, name, ResourceResetCycle.from(resetCycle), capacity, isActive, sortIndex, createdAt
)
fun CostResource.toEntity() = CostResourceEntity(id, name, resetCycle.label, capacity, isActive, sortIndex, createdAt)

fun TaskCostEntryEntity.toModel() = TaskCostEntry(id, taskId, resourceId, amount, note, recordedAt)
fun TaskCostEntry.toEntity() = TaskCostEntryEntity(id, taskId, resourceId, amount, note, recordedAt)

fun RunbookEntity.toModel() = Runbook(id, name, createdAt)
fun Runbook.toEntity() = RunbookEntity(id, name, createdAt)

fun RunbookStepEntity.toModel() = RunbookStep(id, runbookId, label, stepOrder)
fun RunbookStep.toEntity() = RunbookStepEntity(id, runbookId, label, stepOrder)

fun SubtaskEntity.toModel() = Subtask(id, taskId, runbookId, label, stepOrder, isChecked)
fun Subtask.toEntity() = SubtaskEntity(id, taskId, runbookId, label, stepOrder, isChecked)
