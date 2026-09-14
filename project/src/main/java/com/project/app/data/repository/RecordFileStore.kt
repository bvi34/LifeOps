package com.project.app.data.repository

import com.project.app.data.db.dao.ProjectDao
import com.project.app.logic.AttachKind
import com.project.app.logic.Attachments
import com.project.app.logic.Lore
import kotlinx.coroutines.flow.map

/**
 * Files attached to a record, and the drawer on the household shelf they live in.
 */
class RecordFileStore(
    private val dao: ProjectDao,
    private val labels: RecordLabels
) {

    /**
     * The record a set of files is filed on, ready to be shown.
     *
     * Null when it has been deleted since — which is ordinary, because a link to a record's files
     * can outlive the record, and landing on a file drawer for something that is not there is worse
     * than landing back where you were.
     */
    suspend fun attachTarget(projectId: String, kind: AttachKind, recordId: String): AttachTarget? {
        val project = dao.getProject(projectId) ?: return null

        val name = when (kind) {
            AttachKind.PROJECT -> project.name.takeIf { recordId == projectId }
            AttachKind.OUTLINE -> dao.getOutlineNode(recordId)?.takeIf { it.projectId == projectId }?.title
            AttachKind.CARD -> dao.getCard(recordId)?.takeIf { it.projectId == projectId }?.title
            // Lore has no by-id read of its own; the project's entries are a short list and this is
            // asked once, when a screen opens.
            AttachKind.LORE -> dao.getLore(projectId).firstOrNull { it.id == recordId }?.name
        } ?: return null

        return AttachTarget(
            recordKey = recordId,
            kind = kind,
            name = name,
            shelfLabel = Attachments.shelfLabel(project.name, name.takeIf { kind != AttachKind.PROJECT })
        )
    }

    /**
     * Every record of a project that could have files on it, including the project itself.
     *
     * Read before a project is deleted. Its rows cascade with it, so afterwards there is nothing
     * left to work out which drawers on the shelf belonged to it — and the household would be left
     * with a pile of documents filed against ids that name nothing.
     */
    suspend fun attachableRecordKeys(projectId: String): List<String> =
        listOf(projectId) +
            dao.getOutline(projectId).map { it.id } +
            dao.getLore(projectId).map { it.id } +
            dao.getCards(projectId).map { it.id }
}
