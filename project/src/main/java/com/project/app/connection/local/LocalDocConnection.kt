package com.project.app.connection.local

import com.operations.connectkit.ConnectionRegistry
import com.operations.connectkit.Resolution
import com.operations.connectkit.ConnectionResult
import com.project.app.data.repository.ProjectRepository

/**
 * The `local/doc` routes — the writing.
 *
 *  - `/v1/Project/local/doc/create` — params: project (required), title (required), outlineNodeId.
 *
 * **One route, and that is the point.** Nothing here writes prose. There is no route that appends
 * to a document, replaces one from Markdown, edits a block or restores a version — every one of
 * those is a way for a caller working from a misheard sentence to change writing that may have no
 * second copy anywhere. Creating an empty document to write in is additive and undoable by deleting
 * it; rewriting one is neither.
 */
object LocalDocConnection {
    fun register(registry: ConnectionRegistry, repo: ProjectRepository) {

        registry.register("local", "doc", "create") { request ->
            val p = request.params
            when (val found = repo.resolveProject(p.requireString("project"))) {
                is Resolution.Problem -> found.failure
                is Resolution.Ok -> {
                    val id = repo.docs.addDoc(
                        projectId = found.value.id,
                        title = p.requireString("title"),
                        outlineNodeId = p.getString("outlineNodeId")
                    )
                    ConnectionResult.ok("id" to id, "projectId" to found.value.id)
                }
            }
        }
    }
}
