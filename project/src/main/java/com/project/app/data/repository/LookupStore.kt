package com.project.app.data.repository

import com.project.app.data.db.dao.ProjectDao
import com.project.app.data.model.Project
import com.project.app.logic.BoardColumn
import kotlinx.coroutines.flow.map

/**
 * What a route asks for before it can act: resolving a name said out loud, or an id handed
 * over on its own, to the thing it names.
 *
 * Reads only. Nothing here writes, which is what makes it safe for a route to call before it has
 * decided whether the request is valid.
 */
class LookupStore(
    private val dao: ProjectDao,
    private val board: BoardStore
) {

    /**
     * Every project, for resolving a name a caller said out loud.
     *
     * Archived ones included, deliberately: archiving takes a project off the shelf, not out of the
     * app, and "add a card to the Kestrel" failing because it was tidied away last month would be a
     * puzzling refusal.
     */
    suspend fun allProjectsForLookup(): List<Project> = dao.allProjects().map { it.toModel() }

    /** A board's columns, for resolving a column a caller named. */
    suspend fun columnsOf(projectId: String): List<BoardColumn> =
        dao.getColumns(projectId).map { it.toLogic() }

    /** Which project a card belongs to — a route is handed a card id and nothing else. */
    suspend fun cardWithProject(cardId: String): CardOwner? =
        dao.getCard(cardId)?.let { CardOwner(it.id, it.projectId) }

    /** Whether an outline row is really there, so a route can say NOT_FOUND rather than no-op. */
    suspend fun outlineNodeExists(id: String): Boolean = dao.getOutlineNode(id) != null

    /**
     * Finish a card the way the board means it: moved into the finished column.
     *
     * The LifeOps link is deliberately left alone, which is the difference between this and
     * [completeFromWeek]. Here the task is still open on somebody's week and has to be taken down
     * by the next hand-off round, which needs the link to do it. There the task has already been
     * ticked, so the link is let go of instead.
     */
    suspend fun completeCard(projectId: String, cardId: String, at: Long = now()): Boolean =
        board.moveToDone(projectId, cardId, at)
}
