package com.project.app.data.repository

import com.project.app.data.db.dao.CountByProject
import com.project.app.data.db.dao.ProjectDao
import com.project.app.data.db.entities.LoreEntryEntity
import com.project.app.logic.Attachments
import com.project.app.logic.DocBlocks
import com.project.app.logic.Lore
import com.project.app.logic.LoreCategory
import com.project.app.data.model.LoreEntryView
import java.util.UUID

/**
 * The stamps every store in this package puts on a new row.
 *
 * Top-level rather than a base class: inheriting from something in order to call
 * `System.currentTimeMillis()` is a hierarchy that buys nothing.
 */
internal fun now(): Long = System.currentTimeMillis()

internal fun newId(): String = UUID.randomUUID().toString()

/**
 * Stamp the project as touched.
 *
 * Every write in every store goes through here, which is what makes "last worked on" mean *anything
 * you did in the project* rather than "the last time you renamed it". The shelf sorts nothing by it,
 * but it is the line under a project's name that tells you which of five you actually left
 * half-done.
 *
 * An extension on the DAO rather than a collaborator of its own: it is one write, it needs nothing
 * but the DAO, and a class wrapping it would be a class wrapping one write.
 */
internal suspend fun ProjectDao.touchProject(projectId: String) {
    val existing = getProject(projectId) ?: return
    upsertProject(existing.copy(updatedAt = now()))
}

internal fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

internal fun List<CountByProject>.byProject(): Map<String, Int> =
    associate { it.projectId to it.total }

internal fun List<LoreEntryEntity>.toViews(): List<LoreEntryView> {
    val entries = map { it.toLogic() }
    val index = Lore.index(entries)
    val backlinks = Lore.backlinks(entries)
    return mapIndexed { position, row ->
        val entry = entries[position]
        LoreEntryView(
            entry = entry,
            category = LoreCategory.fromKey(row.category),
            colorArgb = row.colorArgb,
            links = Lore.mentions(entry.body, index),
            backlinkIds = backlinks[entry.id].orEmpty()
        )
    }
}

/**
 * How a rename here reaches the household's shelf.
 *
 * Repository (the app) stores a record's name as a **label**, not a foreign key — which is what lets
 * it show a project's paperwork without knowing what a project is, and is the one upkeep cost of
 * that choice: a renamed record whose drawer still says the old name is a drawer nobody finds again.
 * So every rename in every store pushes the new label down through here.
 *
 * Its own class rather than a method on [ProjectShelfStore] because five stores rename something and
 * none of them needs the rest of what the shelf can do.
 */
class RecordLabels(
    private val dao: ProjectDao,
    private val relabelDocuments: suspend (recordKey: String, label: String) -> Unit
) {

    /** Re-label one record's drawer, after the record was renamed. */
    suspend fun relabelRecord(projectId: String, recordKey: String, recordName: String) {
        val project = dao.getProject(projectId) ?: return
        relabelDocuments(recordKey, Attachments.shelfLabel(project.name, recordName))
    }

    /**
     * Re-label every drawer belonging to a project, after the project itself was renamed.
     *
     * A loop over every record, which is only ever run on a rename — and is the price of a label
     * that leads with the project. The alternative, a drawer called "The docks" sitting on a shelf
     * beside a mortgage statement and a boiler manual, is not one worth paying less for.
     */
    suspend fun relabelEveryRecordOf(projectId: String) {
        val project = dao.getProject(projectId) ?: return
        relabelDocuments(projectId, Attachments.shelfLabel(project.name, null))
        dao.getOutline(projectId).forEach {
            relabelDocuments(it.id, Attachments.shelfLabel(project.name, it.title))
        }
        dao.getLore(projectId).forEach {
            relabelDocuments(it.id, Attachments.shelfLabel(project.name, it.name))
        }
        dao.getCards(projectId).forEach {
            relabelDocuments(it.id, Attachments.shelfLabel(project.name, it.title))
        }
    }
}

/**
 * Word counts, kept true after any edit.
 *
 * A document's count is its blocks'; a scene's is the sum of the documents written for it. Summed
 * rather than taken from one document because a scene can have more than one — a draft and its
 * rewrite, or the scene and the notes for it — and because a scene with no document at all should
 * read as zero rather than keep the last number it happened to be given.
 *
 * Shared by [DocStore] and [DocVersionStore] because editing a block and restoring a version change
 * exactly the same three numbers.
 */
class DocCounts(private val dao: ProjectDao) {

    /** Recount a document, then the scene it belongs to, then stamp the project. */
    suspend fun refreshDocCount(docId: String) {
        val doc = dao.getDoc(docId) ?: return
        val words = DocBlocks.wordCount(dao.getBlocks(docId).map { it.toLogic() })
        dao.upsertDoc(doc.copy(wordCount = words, updatedAt = now()))
        doc.outlineNodeId?.let { syncOutlineWords(it) }
        dao.touchProject(doc.projectId)
    }

    /** Re-sum one scene's word count from the documents written for it. */
    suspend fun syncOutlineWords(outlineNodeId: String) {
        val node = dao.getOutlineNode(outlineNodeId) ?: return
        val total = dao.docsOf(node.projectId)
            .filter { it.outlineNodeId == outlineNodeId }
            .sumOf { it.wordCount }
        if (total != node.actualWords) {
            dao.upsertOutlineNode(node.copy(actualWords = total, updatedAt = now()))
        }
    }
}
