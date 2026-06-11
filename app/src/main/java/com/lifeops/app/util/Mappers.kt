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
    isRecurring, estimatedMinutes, carriedCount, sortOrder, isManuallyAdded, projectId
)

fun Task.toEntity() = TaskEntity(
    id, weekId, title, aspectId, categoryId,
    priority.label, dueDate, hardDeadline,
    status.value, resourceValue, completedAt, carriedFromTaskId, createdAt,
    isRecurring, estimatedMinutes, carriedCount, sortOrder, isManuallyAdded, projectId
)

fun TaskNoteEntity.toModel() = TaskNote(id, taskId, content, createdAt)
fun TaskNote.toEntity() = TaskNoteEntity(id, taskId, content, createdAt)

fun TimeEntryEntity.toModel() = TimeEntry(id, taskId, durationMinutes, note, recordedAt)
fun TimeEntry.toEntity() = TimeEntryEntity(id, taskId, durationMinutes, note, recordedAt)

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
