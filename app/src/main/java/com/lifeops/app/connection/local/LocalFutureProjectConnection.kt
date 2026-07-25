package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionError
import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.FutureProjectService
import com.lifeops.app.data.model.FutureProjectStatus

/**
 * The `local/futureProject/*` routes.
 *
 *  - `create`  — params: title (required).
 *  - `addNote` — params: id (required), content (required).
 *  - `archive` — params: id (required), archived (bool, default true) → ARCHIVED/ACTIVE.
 *  - `delete`  — params: id (required).
 */
object LocalFutureProjectConnection {
    fun register(registry: ConnectionRegistry, service: FutureProjectService) {
        registry.register("local", "futureProject", "create") { request ->
            val project = service.create(request.params.requireString("title"))
            ConnectionResult.ok("id" to project.id)
        }

        registry.register("local", "futureProject", "addNote") { request ->
            val p = request.params
            val id = p.requireString("id")
            if (service.addNote(id, p.requireString("content"))) ConnectionResult.ok("id" to id)
            else ConnectionResult.fail(ConnectionError.INVALID_PARAMS, "Note content must not be blank")
        }

        registry.register("local", "futureProject", "archive") { request ->
            val p = request.params
            val id = p.requireString("id")
            val archived = if (p.has("archived")) p.getBoolean("archived") else true
            val status = if (archived) FutureProjectStatus.ARCHIVED else FutureProjectStatus.ACTIVE
            service.setStatus(id, status)
            ConnectionResult.ok("id" to id, "status" to status.value)
        }

        registry.register("local", "futureProject", "delete") { request ->
            val id = request.params.requireString("id")
            service.delete(id)
            ConnectionResult.ok("id" to id)
        }
    }
}
