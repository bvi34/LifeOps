package com.project.app.connection.local

import com.operations.connectkit.ConnectionRegistry
import com.operations.connectkit.Resolution
import com.operations.connectkit.ConnectionResult
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.ProjectKind

/**
 * The `local/project` routes — the shelf.
 *
 *  - `/v1/Project/local/project/create`  — params: name (required), kind, summary.
 *  - `/v1/Project/local/project/archive` — params: project (required), archived.
 */
object LocalProjectConnection {
    fun register(registry: ConnectionRegistry, repo: ProjectRepository) {

        registry.register("local", "project", "create") { request ->
            val p = request.params
            val id = repo.shelf.addProject(
                name = p.requireString("name"),
                // An unknown kind reads as General rather than failing: kind is vocabulary, and
                // refusing to make a project because somebody said "novel" would be pedantry.
                kind = ProjectKind.fromKey(p.getString("kind")),
                summary = p.getString("summary")
            )
            ConnectionResult.ok("id" to id)
        }

        registry.register("local", "project", "archive") { request ->
            when (val found = repo.resolveProject(request.params.requireString("project"))) {
                is Resolution.Problem -> found.failure
                is Resolution.Ok -> {
                    val archived = request.params.getBoolean("archived", default = true)
                    repo.shelf.setArchived(found.value.id, archived)
                    ConnectionResult.ok("id" to found.value.id, "archived" to archived)
                }
            }
        }
    }
}
