package com.project.app.connection.local

import com.lifeops.app.connection.ConnectionError
import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.ProjectLookup
import java.time.LocalDate

/**
 * The `local/card` routes — the board.
 *
 *  - `/v1/Project/local/card/create`   — params: project (required), title (required), column,
 *    notes, dueOn (ISO date), outlineNodeId, docId.
 *  - `/v1/Project/local/card/move`     — params: id (required), column (required).
 *  - `/v1/Project/local/card/complete` — params: id (required).
 *  - `/v1/Project/local/card/delete`   — params: id (required).
 *
 * These are the routes with the most reason to exist. A card is the smallest unit of "something to
 * do about this project", it is cheap to make and cheap to undo, and — since a dated one publishes
 * itself onto the LifeOps week — a card created through here can reach somebody's Tuesday.
 *
 * That is also why `dueOn` is parsed strictly: a date that will not parse is refused rather than
 * dropped, because a card silently created without the deadline that was the whole point of it is
 * worse than a caller being asked to say the date again.
 */
object LocalCardConnection {
    fun register(registry: ConnectionRegistry, repo: ProjectRepository) {

        registry.register("local", "card", "create") { request ->
            val p = request.params
            when (val found = repo.resolveProject(p.requireString("project"))) {
                is Resolved.Problem -> found.failure
                is Resolved.Ok -> {
                    val projectId = found.project.id
                    val columns = repo.columnsOf(projectId)

                    val named = p.getString("column")
                    val column = if (named == null) {
                        ProjectLookup.defaultColumn(columns) { it.isDone }
                    } else {
                        when (val m = ProjectLookup.resolve(named, columns, { it.id }, { it.name })) {
                            is ProjectLookup.Match.Found -> m.value
                            is ProjectLookup.Match.None -> null
                            is ProjectLookup.Match.Ambiguous -> return@register ConnectionResult.fail(
                                ConnectionError.INVALID_PARAMS,
                                "More than one column is called '$named'. Use its id."
                            )
                        }
                    } ?: return@register ConnectionResult.fail(
                        ConnectionError.NOT_FOUND,
                        if (named == null) "${found.project.name} has no column to put a card in"
                        else "No column called '$named' on ${found.project.name}"
                    )

                    val text = p.getString("dueOn")
                    val dueOn = if (text == null) null else {
                        runCatching { LocalDate.parse(text.trim()) }.getOrNull()
                            ?: return@register ConnectionResult.fail(
                                ConnectionError.INVALID_PARAMS,
                                "dueOn must be a date like 2026-03-14, not '$text'"
                            )
                    }

                    val id = repo.addCard(
                        projectId = projectId,
                        columnId = column.id,
                        title = p.requireString("title"),
                        notes = p.getString("notes"),
                        outlineNodeId = p.getString("outlineNodeId"),
                        docId = p.getString("docId"),
                        dueOn = dueOn?.toEpochDay()
                    )
                    ConnectionResult.ok("id" to id, "projectId" to projectId, "columnId" to column.id)
                }
            }
        }

        registry.register("local", "card", "move") { request ->
            val p = request.params
            val id = p.requireString("id")
            val card = repo.cardWithProject(id)
                ?: return@register ConnectionResult.fail(ConnectionError.NOT_FOUND, "No card with id '$id'")

            val named = p.requireString("column")
            val columns = repo.columnsOf(card.projectId)
            val target = when (val m = ProjectLookup.resolve(named, columns, { it.id }, { it.name })) {
                is ProjectLookup.Match.Found -> m.value
                is ProjectLookup.Match.None -> return@register ConnectionResult.fail(
                    ConnectionError.NOT_FOUND,
                    "No column called '$named' on that board"
                )
                is ProjectLookup.Match.Ambiguous -> return@register ConnectionResult.fail(
                    ConnectionError.INVALID_PARAMS,
                    "More than one column is called '$named'. Use its id."
                )
            }

            // Clamped to the end of the lane, which is where a card put there by somebody else's
            // sentence belongs — not on top of the thing you were about to pick up.
            repo.moveCard(card.projectId, id, target.id, Int.MAX_VALUE)
            ConnectionResult.ok("id" to id, "columnId" to target.id)
        }

        registry.register("local", "card", "complete") { request ->
            val id = request.params.requireString("id")
            val card = repo.cardWithProject(id)
                ?: return@register ConnectionResult.fail(ConnectionError.NOT_FOUND, "No card with id '$id'")

            // Moved into the board's finished column, which is what done means here. The LifeOps
            // task it may have published is deliberately *not* touched: the next hand-off round sees
            // a finished card and retires it, which is the one place that decision is made.
            val moved = repo.completeCard(card.projectId, id)
            ConnectionResult.ok("id" to id, "moved" to moved)
        }

        registry.register("local", "card", "delete") { request ->
            val id = request.params.requireString("id")
            val card = repo.cardWithProject(id)
                ?: return@register ConnectionResult.fail(ConnectionError.NOT_FOUND, "No card with id '$id'")
            repo.deleteCard(card.projectId, id)
            ConnectionResult.ok("id" to id)
        }
    }
}
