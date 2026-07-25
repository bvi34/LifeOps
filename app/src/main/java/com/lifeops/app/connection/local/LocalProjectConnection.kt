package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionError
import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.ProjectService

/**
 * The `local/project/*` routes.
 *
 *  - `/v1/LifeOps/local/project/create`   — params: title (required), aspectId, categoryId,
 *    description. Returns the new project id.
 *  - `/v1/LifeOps/local/project/update`   — params: id (required) + any of title/aspectId/
 *    categoryId/description to change.
 *  - `/v1/LifeOps/local/project/complete` — params: id (required). Marks COMPLETED.
 *  - `/v1/LifeOps/local/project/reopen`   — params: id (required). Marks ACTIVE.
 */
object LocalProjectConnection {

    private const val CONNECTION = "local"
    private const val RESOURCE = "project"

    fun register(registry: ConnectionRegistry, projectService: ProjectService) {
        registry.register(CONNECTION, RESOURCE, "create") { request ->
            val p = request.params
            val project = projectService.create(
                title = p.requireString("title"),
                aspectId = p.getString("aspectId"),
                categoryId = p.getString("categoryId"),
                description = p.getString("description")
            )
            ConnectionResult.ok("id" to project.id, "status" to project.status.value)
        }

        registry.register(CONNECTION, RESOURCE, "update") { request ->
            val p = request.params
            val id = p.requireString("id")
            projectService.update(
                id = id,
                title = p.getString("title"),
                aspectId = p.getString("aspectId"),
                categoryId = p.getString("categoryId"),
                description = p.getString("description")
            )?.let { ConnectionResult.ok("id" to it.id) } ?: notFound(id)
        }

        registry.register(CONNECTION, RESOURCE, "complete") { request ->
            val id = request.params.requireString("id")
            if (projectService.complete(id)) ConnectionResult.ok("id" to id) else notFound(id)
        }

        registry.register(CONNECTION, RESOURCE, "reopen") { request ->
            val id = request.params.requireString("id")
            if (projectService.reopen(id)) ConnectionResult.ok("id" to id) else notFound(id)
        }
    }

    private fun notFound(id: String): ConnectionResult =
        ConnectionResult.fail(ConnectionError.NOT_FOUND, "No project with id '$id'")
}
