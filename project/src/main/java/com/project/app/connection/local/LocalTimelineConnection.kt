package com.project.app.connection.local

import com.operations.connectkit.ConnectionRegistry
import com.operations.connectkit.Resolution
import com.operations.connectkit.ConnectionResult
import com.project.app.data.repository.ProjectRepository

/**
 * The `local/timeline` routes.
 *
 *  - `/v1/Project/local/timeline/add` — params: project (required), title (required), when, era,
 *    detail, outlineNodeId.
 *
 * `when` is free text on purpose, here as everywhere else: the timeline reads it as a date where it
 * can and keeps the author's order regardless. A route that demanded a real date would refuse "the
 * spring after the coronation", which is exactly the kind of thing a timeline is for.
 */
object LocalTimelineConnection {
    fun register(registry: ConnectionRegistry, repo: ProjectRepository) {

        registry.register("local", "timeline", "add") { request ->
            val p = request.params
            when (val found = repo.resolveProject(p.requireString("project"))) {
                is Resolution.Problem -> found.failure
                is Resolution.Ok -> {
                    val id = repo.addEvent(
                        projectId = found.value.id,
                        title = p.requireString("title"),
                        whenLabel = p.getString("when"),
                        era = p.getString("era"),
                        detail = p.getString("detail"),
                        outlineNodeId = p.getString("outlineNodeId")
                    )
                    ConnectionResult.ok("id" to id, "projectId" to found.value.id)
                }
            }
        }
    }
}
