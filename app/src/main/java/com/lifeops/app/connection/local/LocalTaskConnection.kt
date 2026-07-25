package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.TaskService
import com.lifeops.app.data.model.Priority

/**
 * The reference connection: registers the `local/task/*` routes. Each handler is a thin adapter —
 * it reads and coerces the payload, then delegates to [TaskService]. This is the pattern every
 * other resource (week, counter, wellness, …) should follow when it is migrated onto the
 * connection layer.
 *
 * Routes:
 *  - `/v1/LifeOps/local/task/create`   — params: title (required), note, aspectId, categoryId,
 *    priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, projectId, counterId,
 *    recurrenceIntervalWeeks, recurrenceDayOfMonth
 *  - `/v1/LifeOps/local/task/update`   — params: id (required) + any create field to change
 *  - `/v1/LifeOps/local/task/complete` — params: id (required)
 *  - `/v1/LifeOps/local/task/delete`   — params: id (required)
 */
object LocalTaskConnection {

    private const val CONNECTION = "local"
    private const val RESOURCE = "task"

    fun register(registry: ConnectionRegistry, taskService: TaskService) {
        registry.register(CONNECTION, RESOURCE, "create") { request ->
            val p = request.params
            val outcome = taskService.create(
                TaskService.CreateInput(
                    title = p.requireString("title"),
                    note = p.getString("note"),
                    aspectId = p.getString("aspectId"),
                    categoryId = p.getString("categoryId"),
                    priority = priorityOf(p.getString("priority")),
                    dueDate = p.getString("dueDate"),
                    hardDeadline = p.getBoolean("hardDeadline"),
                    isRecurring = p.getBoolean("isRecurring"),
                    estimatedMinutes = p.getInt("estimatedMinutes"),
                    projectId = p.getString("projectId"),
                    counterId = p.getString("counterId"),
                    recurrenceIntervalWeeks = p.getInt("recurrenceIntervalWeeks") ?: 1,
                    recurrenceDayOfMonth = p.getInt("recurrenceDayOfMonth")
                )
            )
            when (outcome) {
                is TaskService.CreateOutcome.Created ->
                    ConnectionResult.ok("id" to outcome.task.id, "status" to outcome.task.status.value)
                is TaskService.CreateOutcome.DuplicateSkipped ->
                    ConnectionResult.ok("skipped" to true, "reason" to "duplicate", "slug" to outcome.slug)
            }
        }

        registry.register(CONNECTION, RESOURCE, "update") { request ->
            val p = request.params
            val id = p.requireString("id")
            val updated = taskService.update(
                taskId = id,
                title = p.getString("title"),
                aspectId = p.getString("aspectId"),
                categoryId = p.getString("categoryId"),
                priority = p.getString("priority")?.let { priorityOf(it) },
                dueDate = p.getString("dueDate"),
                hardDeadline = if (p.has("hardDeadline")) p.getBoolean("hardDeadline") else null,
                estimatedMinutes = p.getInt("estimatedMinutes"),
                projectId = p.getString("projectId")
            )
            updated?.let { ConnectionResult.ok("id" to it.id) } ?: notFound(id)
        }

        registry.register(CONNECTION, RESOURCE, "complete") { request ->
            val id = request.params.requireString("id")
            if (taskService.complete(id)) ConnectionResult.ok("id" to id) else notFound(id)
        }

        registry.register(CONNECTION, RESOURCE, "delete") { request ->
            val id = request.params.requireString("id")
            if (taskService.delete(id)) ConnectionResult.ok("id" to id) else notFound(id)
        }
    }

    private fun priorityOf(value: String?): Priority =
        value?.let { Priority.from(it.lowercase()) } ?: Priority.MEDIUM

    private fun notFound(id: String): ConnectionResult =
        ConnectionResult.fail(com.lifeops.app.connection.ConnectionError.NOT_FOUND, "No task with id '$id'")
}
