package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionError
import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.RunbookService

/**
 * Runbook routes: `local/runbook/*` and `local/subtask/*`.
 *
 *  - `runbook/create` — params: name (required), steps (string list, required).
 *  - `runbook/delete` — params: id (required).
 *  - `runbook/stamp`  — params: id (runbook, required), taskId (required). Stamps steps as subtasks.
 *  - `subtask/check`  — params: id (required), checked (bool, default true).
 *  - `subtask/delete` — params: id (required).
 */
object LocalRunbookConnection {
    fun register(registry: ConnectionRegistry, runbookService: RunbookService) {
        registry.register("local", "runbook", "create") { request ->
            val p = request.params
            val steps = p.getStringList("steps")
            if (steps.isEmpty()) {
                ConnectionResult.fail(ConnectionError.INVALID_PARAMS, "steps must be a non-empty list")
            } else {
                val rb = runbookService.create(p.requireString("name"), steps)
                ConnectionResult.ok("id" to rb.runbook.id, "steps" to rb.steps.size)
            }
        }

        registry.register("local", "runbook", "delete") { request ->
            val id = request.params.requireString("id")
            runbookService.delete(id)
            ConnectionResult.ok("id" to id)
        }

        registry.register("local", "runbook", "stamp") { request ->
            val p = request.params
            val id = p.requireString("id")
            val taskId = p.requireString("taskId")
            if (runbookService.stamp(taskId, id)) ConnectionResult.ok("runbookId" to id, "taskId" to taskId)
            else ConnectionResult.fail(ConnectionError.NOT_FOUND, "No runbook with id '$id'")
        }

        registry.register("local", "subtask", "check") { request ->
            val p = request.params
            val id = p.requireString("id")
            val checked = if (p.has("checked")) p.getBoolean("checked") else true
            runbookService.setSubtaskChecked(id, checked)
            ConnectionResult.ok("id" to id, "checked" to checked)
        }

        registry.register("local", "subtask", "delete") { request ->
            val id = request.params.requireString("id")
            runbookService.deleteSubtask(id)
            ConnectionResult.ok("id" to id)
        }
    }
}
