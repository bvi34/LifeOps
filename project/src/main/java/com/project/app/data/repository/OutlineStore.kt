package com.project.app.data.repository

import com.project.app.data.db.dao.ProjectDao
import com.project.app.data.db.entities.OutlineNodeEntity
import com.project.app.logic.Outline
import com.project.app.logic.OutlineNode
import com.project.app.logic.OutlineStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The outline: the tree of parts, chapters and scenes a project is planned as.
 *
 * Moves and deletes are computed against the whole tree rather than left to the database, because
 * `parentId` carries no foreign key and because the screen has to be able to say "this will also
 * delete 14 scenes" *before* doing it.
 */
class OutlineStore(
    private val dao: ProjectDao,
    private val labels: RecordLabels
) {

    fun observeOutline(projectId: String): Flow<List<OutlineNode>> =
        dao.observeOutline(projectId).map { rows -> rows.map { it.toLogic() } }

    suspend fun addOutlineNode(projectId: String, parentId: String?, title: String): String {
        val id = newId()
        val timestamp = now()
        val siblings = dao.getOutline(projectId).map { it.toLogic() }
        dao.upsertOutlineNode(
            OutlineNodeEntity(
                id = id,
                projectId = projectId,
                parentId = parentId,
                title = title.trim().ifEmpty { "Untitled" },
                synopsis = null,
                status = OutlineStatus.IDEA.key,
                targetWords = 0,
                actualWords = 0,
                sortOrder = Outline.nextSortOrder(siblings, parentId),
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        dao.touchProject(projectId)
        return id
    }

    suspend fun updateOutlineNode(node: OutlineNode) {
        val existing = dao.getOutlineNode(node.id) ?: return
        dao.upsertOutlineNode(
            existing.copy(
                title = node.title.trim().ifEmpty { "Untitled" },
                synopsis = node.synopsis.clean(),
                status = node.status.key,
                targetWords = node.targetWords.coerceAtLeast(0),
                // Word counts arrive from the linked document, never from an edit box; leaving the
                // stored value alone here is what stops a rename resetting a scene's length.
                updatedAt = now()
            )
        )
        if (existing.title != node.title.trim()) {
            labels.relabelRecord(existing.projectId, node.id, node.title.trim())
        }
        dao.touchProject(existing.projectId)
    }

    suspend fun setOutlineStatus(id: String, status: OutlineStatus) {
        val existing = dao.getOutlineNode(id) ?: return
        dao.upsertOutlineNode(existing.copy(status = status.key, updatedAt = now()))
        dao.touchProject(existing.projectId)
    }

    /**
     * Move, indent or outdent — the same shape three times, and deliberately so.
     *
     * Each takes the current tree, asks `logic/Outline` what changed, and writes that. An edit the
     * outline refuses (indenting the first row, moving the last one down) returns an empty list and
     * therefore writes nothing at all, rather than throwing at a user who tapped a button that
     * should have been greyed out a frame earlier.
     */
    suspend fun moveOutlineNode(projectId: String, id: String, delta: Int) =
        applyOutlineChange(projectId) { Outline.move(it, id, delta) }

    suspend fun indentOutlineNode(projectId: String, id: String) =
        applyOutlineChange(projectId) { Outline.indent(it, id) }

    suspend fun outdentOutlineNode(projectId: String, id: String) =
        applyOutlineChange(projectId) { Outline.outdent(it, id) }

    private suspend fun applyOutlineChange(
        projectId: String,
        change: (List<OutlineNode>) -> List<OutlineNode>
    ) {
        val rows = dao.getOutline(projectId)
        val changed = change(rows.map { it.toLogic() })
        if (changed.isEmpty()) return
        val byId = rows.associateBy { it.id }
        val timestamp = now()
        dao.upsertOutlineNodes(
            changed.mapNotNull { node ->
                byId[node.id]?.copy(
                    parentId = node.parentId,
                    sortOrder = node.sortOrder,
                    updatedAt = timestamp
                )
            }
        )
        dao.touchProject(projectId)
    }

    /**
     * Delete a node and everything under it.
     *
     * The subtree is computed rather than left to a cascade because there is no foreign key on
     * `parentId` (see the entity's note) — and because the screen has to be able to say "this will
     * also delete 14 scenes" *before* doing it, which needs the same list.
     */
    suspend fun deleteOutlineSubtree(projectId: String, id: String) {
        val nodes = dao.getOutline(projectId).map { it.toLogic() }
        dao.deleteOutlineSubtree(Outline.subtree(nodes, id))
        dao.touchProject(projectId)
    }

    /** How much a delete would take with it, for the confirmation the screen shows first. */
    suspend fun subtreeSize(projectId: String, id: String): Int =
        Outline.subtree(dao.getOutline(projectId).map { it.toLogic() }, id).size
}
