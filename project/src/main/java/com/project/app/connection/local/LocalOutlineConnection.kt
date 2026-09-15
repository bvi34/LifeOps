package com.project.app.connection.local

import com.operations.connectkit.ConnectionError
import com.operations.connectkit.ConnectionRegistry
import com.operations.connectkit.Resolution
import com.operations.connectkit.ConnectionResult
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.OutlineStatus

/**
 * The `local/outline` routes — the shape of the work.
 *
 *  - `/v1/Project/local/outline/add`       — params: project (required), title (required), parentId.
 *  - `/v1/Project/local/outline/setStatus` — params: id (required), status (required).
 *
 * Deliberately no delete: deleting an outline node takes its whole subtree, and a route that can
 * remove a chapter and everything under it on a misheard sentence is not a route worth having. That
 * is a confirmation dialog's job, and the app has one.
 */
object LocalOutlineConnection {
    fun register(registry: ConnectionRegistry, repo: ProjectRepository) {

        registry.register("local", "outline", "add") { request ->
            val p = request.params
            when (val found = repo.resolveProject(p.requireString("project"))) {
                is Resolution.Problem -> found.failure
                is Resolution.Ok -> {
                    val id = repo.outline.addOutlineNode(
                        projectId = found.value.id,
                        parentId = p.getString("parentId"),
                        title = p.requireString("title")
                    )
                    ConnectionResult.ok("id" to id, "projectId" to found.value.id)
                }
            }
        }

        registry.register("local", "outline", "setStatus") { request ->
            val p = request.params
            val id = p.requireString("id")
            val status = OutlineStatus.entries.firstOrNull { it.key == p.requireString("status") }
                ?: return@register ConnectionResult.fail(
                    ConnectionError.INVALID_PARAMS,
                    "Unknown status. One of: " + OutlineStatus.entries.joinToString(", ") { it.key }
                )
            if (repo.lookups.outlineNodeExists(id)) {
                repo.outline.setOutlineStatus(id, status)
                ConnectionResult.ok("id" to id, "status" to status.key)
            } else {
                ConnectionResult.fail(ConnectionError.NOT_FOUND, "No outline row with id '$id'")
            }
        }
    }
}
