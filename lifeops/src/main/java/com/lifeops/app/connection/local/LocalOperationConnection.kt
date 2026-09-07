package com.lifeops.app.connection.local

import com.operations.connectkit.ConnectionError
import com.operations.connectkit.ConnectionRegistry
import com.operations.connectkit.ConnectionResult
import com.lifeops.app.connection.service.OperationService

/**
 * The `local/operation` routes.
 *
 *  - `/v1/LifeOps/local/operation/create`   — params: title (required), aspectId, categoryId,
 *    description. Returns the new operation id.
 *  - `/v1/LifeOps/local/operation/update`   — params: id (required) + any of title/aspectId/
 *    categoryId/description to change.
 *  - `/v1/LifeOps/local/operation/complete` — params: id (required). Marks COMPLETED.
 *  - `/v1/LifeOps/local/operation/reopen`   — params: id (required). Marks ACTIVE.
 */
object LocalOperationConnection {

    private const val CONNECTION = "local"
    private const val RESOURCE = "operation"

    fun register(registry: ConnectionRegistry, operationService: OperationService) {
        registry.register(CONNECTION, RESOURCE, "create") { request ->
            val p = request.params
            val operation = operationService.create(
                title = p.requireString("title"),
                aspectId = p.getString("aspectId"),
                categoryId = p.getString("categoryId"),
                description = p.getString("description")
            )
            ConnectionResult.ok("id" to operation.id, "status" to operation.status.value)
        }

        registry.register(CONNECTION, RESOURCE, "update") { request ->
            val p = request.params
            val id = p.requireString("id")
            operationService.update(
                id = id,
                title = p.getString("title"),
                aspectId = p.getString("aspectId"),
                categoryId = p.getString("categoryId"),
                description = p.getString("description")
            )?.let { ConnectionResult.ok("id" to it.id) } ?: notFound(id)
        }

        registry.register(CONNECTION, RESOURCE, "complete") { request ->
            val id = request.params.requireString("id")
            if (operationService.complete(id)) ConnectionResult.ok("id" to id) else notFound(id)
        }

        registry.register(CONNECTION, RESOURCE, "reopen") { request ->
            val id = request.params.requireString("id")
            if (operationService.reopen(id)) ConnectionResult.ok("id" to id) else notFound(id)
        }
    }

    private fun notFound(id: String): ConnectionResult =
        ConnectionResult.fail(ConnectionError.NOT_FOUND, "No operation with id '$id'")
}
