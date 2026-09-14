package com.project.app.data.repository

import com.project.app.data.db.dao.ProjectDao
import com.project.app.data.db.entities.DocBlockEntity
import com.project.app.data.db.entities.DocEntity
import com.project.app.data.model.Doc
import com.project.app.data.model.DocContent
import com.project.app.logic.BlockType
import com.project.app.logic.DocBlock
import com.project.app.logic.DocBlocks
import com.project.app.logic.MarkdownTables
import com.project.app.logic.RevisionReason
import com.project.app.logic.Tree
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Documents and the blocks they are written in.

 * Word counts are [DocCounts]' job rather than this class's: a block edit changes a document's
 * count, a scene's count, and nothing else, and that fan-out is the same from here and from
 * [DocVersionStore].
 */
class DocStore(
    private val dao: ProjectDao,
    private val counts: DocCounts,
    private val versions: DocVersionStore
) {

    fun observeDocs(projectId: String): Flow<List<Doc>> =
        dao.observeDocs(projectId).map { rows -> rows.map { it.toModel() } }

    fun observeDocContent(docId: String): Flow<DocContent?> =
        combine(dao.observeDoc(docId), dao.observeBlocks(docId)) { doc, blocks ->
            doc?.let { DocContent(it.toModel(), blocks.map { block -> block.toLogic() }) }
        }

    suspend fun addDoc(
        projectId: String,
        title: String,
        parentDocId: String? = null,
        outlineNodeId: String? = null
    ): String {
        val id = newId()
        val timestamp = now()
        dao.upsertDoc(
            DocEntity(
                id = id,
                projectId = projectId,
                parentDocId = parentDocId,
                title = title.trim().ifEmpty { "Untitled" },
                icon = null,
                outlineNodeId = outlineNodeId,
                wordCount = 0,
                sortOrder = dao.nextDocSortOrder(projectId),
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        // A brand-new document opens on one empty paragraph rather than on nothing: an editor with
        // no block in it has nowhere to put the cursor.
        dao.upsertBlock(DocBlockEntity(newId(), id, BlockType.PARAGRAPH.key, "", false, 0))
        dao.touchProject(projectId)
        return id
    }

    suspend fun updateDoc(doc: Doc) {
        val existing = dao.getDoc(doc.id) ?: return
        dao.upsertDoc(
            existing.copy(
                title = doc.title.trim().ifEmpty { "Untitled" },
                icon = doc.icon.clean(),
                parentDocId = doc.parentDocId,
                outlineNodeId = doc.outlineNodeId,
                updatedAt = now()
            )
        )
        // Linking a document to a scene has to move its words there too, or the outline reports the
        // scene as unwritten until the next time somebody types in it.
        counts.refreshDocCount(doc.id)
        existing.outlineNodeId?.let { counts.syncOutlineWords(it) }
    }

    /**
     * Re-file a document under another one, or back to the top level.
     *
     * Refused when the new parent is the document itself or one of its own descendants — the move
     * that detaches a branch and leaves it reachable only by the orphan handling in [Tree]. The
     * picker already hides those, so this is the belt to that pair of braces.
     */
    suspend fun moveDoc(projectId: String, docId: String, newParentId: String?) {
        val docs = dao.docsOf(projectId)
        if (!Tree.canReparent(docs, { it.id }, { it.parentDocId }, docId, newParentId)) return
        val existing = docs.firstOrNull { it.id == docId } ?: return
        dao.upsertDoc(existing.copy(parentDocId = newParentId, updatedAt = now()))
        dao.touchProject(projectId)
    }

    suspend fun deleteDoc(docId: String) {
        val existing = dao.getDoc(docId) ?: return
        dao.deleteDocWithLinks(docId)
        existing.outlineNodeId?.let { counts.syncOutlineWords(it) }
        dao.touchProject(existing.projectId)
    }

    suspend fun addBlock(docId: String, type: BlockType, afterBlockId: String?): String {
        val blocks = dao.getBlocks(docId)
        val at = afterBlockId?.let { id -> blocks.indexOfFirst { it.id == id } } ?: (blocks.size - 1)
        val id = newId()
        val inserted = blocks.toMutableList()
        // A table starts as a table: an empty one is a grid you can type into, whereas an empty
        // string is a block with nothing to draw and no hint of what belongs in it.
        val seed = if (type == BlockType.TABLE) MarkdownTables.blank() else ""
        inserted.add((at + 1).coerceIn(0, blocks.size), DocBlockEntity(id, docId, type.key, seed, false, 0))
        dao.upsertBlocks(inserted.mapIndexed { index, block -> block.copy(sortOrder = index) })
        counts.refreshDocCount(docId)
        return id
    }

    suspend fun updateBlock(docId: String, block: DocBlock) {
        dao.upsertBlocks(
            dao.getBlocks(docId).map { row ->
                if (row.id == block.id) {
                    row.copy(type = block.type.key, text = block.text, checked = block.checked)
                } else {
                    row
                }
            }
        )
        counts.refreshDocCount(docId)
    }

    suspend fun deleteBlock(docId: String, blockId: String) {
        val remaining = dao.getBlocks(docId).filterNot { it.id == blockId }
        dao.deleteBlock(blockId)
        dao.upsertBlocks(remaining.mapIndexed { index, block -> block.copy(sortOrder = index) })
        counts.refreshDocCount(docId)
    }

    /** Move a block one place up (-1) or down (+1). */
    suspend fun moveBlock(docId: String, blockId: String, delta: Int) {
        val blocks = dao.getBlocks(docId).toMutableList()
        val index = blocks.indexOfFirst { it.id == blockId }
        val target = index + delta
        if (index < 0 || target !in blocks.indices) return
        blocks.add(target, blocks.removeAt(index))
        dao.upsertBlocks(blocks.mapIndexed { position, block -> block.copy(sortOrder = position) })
        counts.refreshDocCount(docId)
    }

    /**
     * Replace a document's contents with the blocks that Markdown parses to — what "paste a chapter
     * in" does. The old blocks go; this is an import, not a merge.
     *
     * Which is why a version is kept first. This is the one edit in the app that can throw away a
     * morning's writing in a single tap, on data that may have no second copy anywhere, and a
     * confirmation dialog does not help somebody who meant to paste and pasted the wrong thing.
     */
    suspend fun replaceDocFromMarkdown(docId: String, markdown: String) {
        versions.saveRevision(docId, RevisionReason.IMPORT)
        val blocks = DocBlocks.parse(markdown) { newId() }
            .ifEmpty { listOf(DocBlock(newId(), BlockType.PARAGRAPH, "")) }
        dao.replaceBlocks(
            docId,
            blocks.mapIndexed { index, block ->
                DocBlockEntity(block.id, docId, block.type.key, block.text, block.checked, index)
            }
        )
        counts.refreshDocCount(docId)
    }

    /**
     * Turn the flattened tables in a document back into tables, and say how many there were.
     *
     * The repair for documents written before tables were blocks, where a table pasted in arrived as
     * one paragraph of pipes. Doing it a block at a time through the block menu works, but a stat
     * block of forty rows is not something anybody should have to find by hand — and the shape is
     * recoverable, so it may as well be recovered.
     */
    suspend fun repairTables(docId: String): Int {
        // Rewrites every flattened table in the document at once. Recoverable in principle — the
        // text is still there in a different shape — but "in principle" is not a thing to offer
        // somebody looking at forty rewritten rows, so it keeps a version like any bulk edit.
        versions.saveRevision(docId, RevisionReason.REPAIR)
        val repaired = dao.getBlocks(docId).mapNotNull { row ->
            if (row.type == BlockType.TABLE.key || !MarkdownTables.isFlattened(row.text)) return@mapNotNull null
            MarkdownTables.recover(row.text)
                ?.let { row.copy(type = BlockType.TABLE.key, text = it.render()) }
        }
        if (repaired.isEmpty()) return 0
        dao.upsertBlocks(repaired)
        counts.refreshDocCount(docId)
        return repaired.size
    }

    /** The document as Markdown — the export half of the same round trip. */
    suspend fun docAsMarkdown(docId: String): String =
        DocBlocks.render(dao.getBlocks(docId).map { it.toLogic() })
}
