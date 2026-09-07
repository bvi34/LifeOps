package com.lifeops.app.connection.local

import com.operations.connectkit.ConnectionError
import com.operations.connectkit.ConnectionRegistry
import com.operations.connectkit.ConnectionResult
import com.lifeops.app.connection.service.FutureOperationService
import com.lifeops.app.data.model.FutureOperationStatus

/**
 * The `local/futureOperation` routes.
 *
 *  - `create`  — params: title (required).
 *  - `addNote` — params: id (required), content (required).
 *  - `archive` — params: id (required), archived (bool, default true) → ARCHIVED/ACTIVE.
 *  - `delete`  — params: id (required).
 */
object LocalFutureOperationConnection {
    fun register(registry: ConnectionRegistry, service: FutureOperationService) {
        registry.register("local", "futureOperation", "create") { request ->
            val operation = service.create(request.params.requireString("title"))
            ConnectionResult.ok("id" to operation.id)
        }

        registry.register("local", "futureOperation", "addNote") { request ->
            val p = request.params
            val id = p.requireString("id")
            if (service.addNote(id, p.requireString("content"))) ConnectionResult.ok("id" to id)
            else ConnectionResult.fail(ConnectionError.INVALID_PARAMS, "Note content must not be blank")
        }

        registry.register("local", "futureOperation", "archive") { request ->
            val p = request.params
            val id = p.requireString("id")
            val archived = if (p.has("archived")) p.getBoolean("archived") else true
            val status = if (archived) FutureOperationStatus.ARCHIVED else FutureOperationStatus.ACTIVE
            service.setStatus(id, status)
            ConnectionResult.ok("id" to id, "status" to status.value)
        }

        registry.register("local", "futureOperation", "delete") { request ->
            val id = request.params.requireString("id")
            service.delete(id)
            ConnectionResult.ok("id" to id)
        }
    }
}
