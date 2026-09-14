package com.project.app.data.repository

import com.project.app.data.db.dao.ProjectDao
import com.project.app.data.db.entities.BoardCardEntity
import com.project.app.data.db.entities.BoardColumnEntity
import com.project.app.logic.Board
import com.project.app.logic.BoardCard
import com.project.app.logic.BoardColumn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The board: columns, the cards on them, and moving a card between them.
 */
class BoardStore(
    private val dao: ProjectDao,
    private val labels: RecordLabels
) {

    fun observeColumns(projectId: String): Flow<List<BoardColumn>> =
        dao.observeColumns(projectId).map { rows -> rows.map { it.toLogic() } }

    fun observeCards(projectId: String): Flow<List<BoardCard>> =
        dao.observeCards(projectId).map { rows -> rows.map { it.toLogic() } }

    suspend fun addColumn(projectId: String, name: String, isDone: Boolean = false): String {
        val id = newId()
        dao.upsertColumn(
            BoardColumnEntity(
                id = id,
                projectId = projectId,
                name = name.trim().ifEmpty { "Untitled" },
                sortOrder = dao.nextColumnSortOrder(projectId),
                wipLimit = null,
                isDone = isDone
            )
        )
        dao.touchProject(projectId)
        return id
    }

    suspend fun updateColumn(projectId: String, column: BoardColumn) {
        val existing = dao.getColumns(projectId).firstOrNull { it.id == column.id } ?: return
        dao.upsertColumn(
            existing.copy(
                name = column.name.trim().ifEmpty { "Untitled" },
                // A limit of zero is somebody clearing the box, not a column that may hold nothing.
                wipLimit = column.wipLimit?.takeIf { it > 0 },
                isDone = column.isDone
            )
        )
        dao.touchProject(projectId)
    }

    suspend fun moveColumn(projectId: String, columnId: String, delta: Int) {
        val columns = dao.getColumns(projectId).toMutableList()
        val index = columns.indexOfFirst { it.id == columnId }
        val target = index + delta
        if (index < 0 || target !in columns.indices) return
        columns.add(target, columns.removeAt(index))
        dao.upsertColumns(columns.mapIndexed { position, column -> column.copy(sortOrder = position) })
        dao.touchProject(projectId)
    }

    /** Delete a column. Its cards stay — see the DAO's note, and [refileOrphans]. */
    suspend fun deleteColumn(projectId: String, columnId: String) {
        dao.deleteColumn(columnId)
        dao.touchProject(projectId)
    }

    /** Put every stranded card back into [columnId] — the board screen's one-tap repair. */
    suspend fun refileOrphans(projectId: String, columnId: String) {
        val columns = dao.getColumns(projectId).map { it.toLogic() }
        val cards = dao.getCards(projectId).map { it.toLogic() }
        val orphans = Board.orphans(columns, cards)
        if (orphans.isEmpty()) return
        val rows = dao.getCards(projectId).associateBy { it.id }
        var next = Board.nextSortOrder(cards, columnId)
        dao.upsertCards(
            orphans.mapNotNull { orphan ->
                rows[orphan.id]?.copy(columnId = columnId, sortOrder = next++)
            }
        )
        dao.touchProject(projectId)
    }

    suspend fun addCard(
        projectId: String,
        columnId: String,
        title: String,
        notes: String? = null,
        outlineNodeId: String? = null,
        docId: String? = null,
        dueOn: Long? = null
    ): String {
        val id = newId()
        val cards = dao.getCards(projectId).map { it.toLogic() }
        dao.upsertCard(
            BoardCardEntity(
                id = id,
                projectId = projectId,
                columnId = columnId,
                title = title.trim().ifEmpty { "Untitled" },
                notes = notes.clean(),
                sortOrder = Board.nextSortOrder(cards, columnId),
                outlineNodeId = outlineNodeId,
                docId = docId,
                dueOn = dueOn,
                publishToLifeOps = true,
                lifeOpsTaskId = null,
                publishedDue = null,
                createdAt = now(),
                doneAt = null
            )
        )
        dao.touchProject(projectId)
        return id
    }

    suspend fun updateCard(projectId: String, card: BoardCard) {
        val existing = dao.getCard(card.id) ?: return
        dao.upsertCard(
            existing.copy(
                title = card.title.trim().ifEmpty { "Untitled" },
                notes = card.notes.clean(),
                outlineNodeId = card.outlineNodeId,
                docId = card.docId,
                // Settable and clearable in the same breath: a deadline that has been dropped has
                // to be droppable, or the only way to lose one is to delete the card.
                dueOn = card.dueOn,
                publishToLifeOps = card.publishToLifeOps
                // The link is deliberately absent: `BoardCard` does not carry it, so no edit made
                // on a screen can wipe it and strand a task on somebody's week.
            )
        )
        if (existing.title != card.title.trim()) labels.relabelRecord(projectId, card.id, card.title.trim())
        dao.touchProject(projectId)
    }

    suspend fun deleteCard(projectId: String, id: String) {
        dao.deleteCard(id)
        dao.touchProject(projectId)
    }

    /**
     * Move a card, writing only the rows whose position actually changed.
     *
     * The arithmetic — closing the gap behind, opening one ahead, stamping or clearing the day it
     * was finished — is `logic/Board.move`'s, and is the same whether the move came from a menu, a
     * drag, or the arrows on the card.
     */
    suspend fun moveCard(projectId: String, cardId: String, toColumnId: String, toIndex: Int) {
        val columnRows = dao.getColumns(projectId)
        val cardRows = dao.getCards(projectId)
        val changed = Board.move(
            columns = columnRows.map { it.toLogic() },
            cards = cardRows.map { it.toLogic() },
            cardId = cardId,
            toColumnId = toColumnId,
            toIndex = toIndex,
            now = now()
        )
        if (changed.isEmpty()) return
        val byId = cardRows.associateBy { it.id }
        dao.upsertCards(
            changed.mapNotNull { card ->
                byId[card.id]?.copy(
                    columnId = card.columnId,
                    sortOrder = card.sortOrder,
                    doneAt = card.doneAt
                )
            }
        )
        dao.touchProject(projectId)
    }

    /**
     * Move a card into the board's finished column, or — if that column has been deleted — stamp
     * it where it stands rather than dropping the completion.
     *
     * Visible to the package rather than private because finishing a card is asked for from three
     * directions: the board itself, a route naming a card, and the LifeOps week ticking the task
     * that was published from it. All three mean exactly this.
     */
    internal suspend fun moveToDone(projectId: String, cardId: String, at: Long): Boolean {
        val row = dao.getCard(cardId) ?: return false
        val columns = dao.getColumns(projectId)
        val doneColumn = columns.firstOrNull { it.isDone }

        if (doneColumn != null && row.columnId == doneColumn.id) return false

        if (doneColumn == null) {
            // A board whose finished column has been deleted still has to be able to take this, so
            // the card is stamped where it stands rather than the completion being dropped.
            if (row.doneAt != null) return false
            dao.upsertCard(row.copy(doneAt = at))
            dao.touchProject(projectId)
            return true
        }

        val cards = dao.getCards(projectId).map { it.toLogic() }
        val changed = Board.move(
            columns = columns.map { it.toLogic() },
            cards = cards,
            cardId = cardId,
            toColumnId = doneColumn.id,
            // Clamped to the end of the lane, so finished work reads in the order it was finished.
            toIndex = Int.MAX_VALUE,
            now = at
        )
        if (changed.isEmpty()) return false

        val byId = dao.getCards(projectId).associateBy { it.id }
        dao.upsertCards(
            changed.mapNotNull { card ->
                byId[card.id]?.copy(
                    columnId = card.columnId,
                    sortOrder = card.sortOrder,
                    doneAt = card.doneAt
                )
            }
        )
        dao.touchProject(projectId)
        return true
    }

}
