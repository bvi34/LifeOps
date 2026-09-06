package com.project.app.connection.local

import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.LoreCategory

/**
 * The `local/lore` routes — the wiki.
 *
 *  - `/v1/Project/local/lore/create` — params: project (required), name (required), category,
 *    summary, body.
 *
 * A body is allowed here where it is not for a document, and the difference is real rather than
 * arbitrary: a lore entry created with a line of description is a *new* row that did not exist a
 * moment ago, so the worst case is a page worth deleting. Nothing existing is overwritten, which is
 * the line every route in this package sits on.
 */
object LocalLoreConnection {
    fun register(registry: ConnectionRegistry, repo: ProjectRepository) {

        registry.register("local", "lore", "create") { request ->
            val p = request.params
            when (val found = repo.resolveProject(p.requireString("project"))) {
                is Resolved.Problem -> found.failure
                is Resolved.Ok -> {
                    val id = repo.addLoreEntry(
                        projectId = found.project.id,
                        name = p.requireString("name"),
                        // Unknown reads as Other, which is what Other is for: nothing has to be
                        // miscategorised to be written down.
                        category = LoreCategory.fromKey(p.getString("category")),
                        summary = p.getString("summary"),
                        body = p.getString("body").orEmpty()
                    )
                    ConnectionResult.ok("id" to id, "projectId" to found.project.id)
                }
            }
        }
    }
}
