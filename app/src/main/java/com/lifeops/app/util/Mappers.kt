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
    id, weekId, title, notes, aspectId, categoryId,
    Priority.from(priority), dueDate, hardDeadline,
    TaskStatus.from(status), resourceValue, completedAt, carriedFromTaskId, createdAt
)

fun Task.toEntity() = TaskEntity(
    id, weekId, title, notes, aspectId, categoryId,
    priority.label, dueDate, hardDeadline,
    status.value, resourceValue, completedAt, carriedFromTaskId, createdAt
)

fun GameResourceEntity.toModel() = GameResource(id, name, currentValue, lifetimeEarned, slotIndex)
fun GameResource.toEntity() = GameResourceEntity(id, name, currentValue, lifetimeEarned, slotIndex)

fun GameResourceMappingEntity.toModel() = GameResourceMapping(id, gameResourceId, aspectId, weight)
fun GameResourceMapping.toEntity() = GameResourceMappingEntity(id, gameResourceId, aspectId, weight)
