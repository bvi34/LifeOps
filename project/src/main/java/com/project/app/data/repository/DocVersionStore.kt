package com.project.app.data.repository

import com.project.app.data.db.dao.ProjectDao
import com.project.app.data.db.entities.DocBlockEntity
import com.project.app.data.db.entities.DocRevisionBlockEntity
import com.project.app.data.db.entities.DocRevisionEntity
import com.project.app.logic.BlockType
import com.project.app.logic.DocBlock
import com.project.app.logic.DocRevision
import com.project.app.logic.RevisionReason
import com.project.app.logic.Revisions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Saved versions of a document, and restoring one.

 * Separate from [DocStore] because a version is a snapshot rather than a draft: nothing here is
 * edited, only taken, kept, restored from, or thrown away.
 */
class DocVersionStore(
    private val dao: ProjectDao,
    private val counts: DocCounts
) {

    /**
     * The versions kept for a document, newest first.
     *
     * Headers only — the blocks of a version are read when one is opened, not to draw a list of
     * them. That is what the stored word count on each header is for.
     */
    fun observeRevisions(docId: String): Flow<List<DocRevision>> =
        dao.observeRevisions(docId).map { rows -> rows.map { it.toLogic() } }

    /** How many versions a document has, for the menu that offers to show them. */
    fun observeRevisionCount(docId: String): Flow<Int> = dao.observeRevisionCount(docId)

    /** The blocks of one version, for reading it before deciding to go back to it. */
    suspend fun revisionBlocks(revisionId: String): List<DocBlock> =
        dao.getRevisionBlocks(revisionId).map { it.toLogic() }

    /**
     * Keep what the document says right now, and say whether anything was kept.
     *
     * Two cases file nothing, and both are about not burying the versions that matter under ones
     * that do not. A document with nothing written in it has nothing to lose — and a new document
     * starts life holding a single empty paragraph, so without this, opening one and pasting into
     * it would file a version of nothing. And a document identical to the version already at the
     * top files nothing either: restoring twice, or pasting back exactly what was there, should not
     * push four real versions off the end of the list with copies of each other.
     *
     * Public because it is also the manual "keep this" — the same act, asked for rather than
     * inferred.
     */
    suspend fun saveRevision(docId: String, reason: RevisionReason): String? {
        val doc = dao.getDoc(docId) ?: return null
        val blocks = dao.getBlocks(docId).map { it.toLogic() }
        if (!Revisions.worthKeeping(blocks)) return null

        val existing = dao.getRevisions(docId).map { it.toLogic() }
        val newest = existing.firstOrNull()
        if (newest != null && !Revisions.differ(blocks, revisionBlocks(newest.id))) return null

        val id = newId()
        val saved = DocRevision(
            id = id,
            docId = docId,
            reason = reason,
            wordCount = doc.wordCount,
            savedAt = now()
        )
        dao.writeRevision(
            revision = DocRevisionEntity(
                id = id,
                docId = docId,
                reason = reason.key,
                wordCount = saved.wordCount,
                savedAt = saved.savedAt
            ),
            blocks = blocks.mapIndexed { index, block ->
                DocRevisionBlockEntity(
                    id = newId(),
                    revisionId = id,
                    type = block.type.key,
                    text = block.text,
                    checked = block.checked,
                    sortOrder = index
                )
            },
            // Computed over the list *including* the one being filed, or the cap would be off by one
            // and the table would settle at KEEP + 1.
            pruned = Revisions.prunable(existing + saved)
        )
        return id
    }

    /**
     * Put a document back to an earlier version, and say whether it happened.
     *
     * The current text is kept first, because a restore is a whole-document rewrite and the moment
     * you most want the thing you just replaced is the moment straight after replacing it. Going
     * back is therefore itself undoable, which is what stops the history being a one-way door.
     *
     * The blocks are copied out with new ids rather than moved: a version is not consumed by being
     * restored, so the same one can be returned to twice.
     */
    suspend fun restoreRevision(docId: String, revisionId: String): Boolean {
        val revision = dao.getRevision(revisionId) ?: return false
        // A version belongs to one document. Restoring another document's text into this one would
        // be a data-loss bug wearing the clothes of a safety feature.
        if (revision.docId != docId) return false

        // Read before the snapshot, deliberately. Filing a version can push the oldest off the end
        // of the cap, and the oldest is exactly the one somebody is most likely to be restoring —
        // so the text is in hand before anything can prune the row it came from.
        val restored = dao.getRevisionBlocks(revisionId)
        saveRevision(docId, RevisionReason.RESTORE)
        dao.replaceBlocks(
            docId,
            restored
                .mapIndexed { index, block ->
                    DocBlockEntity(newId(), docId, block.type, block.text, block.checked, index)
                }
                // An editor with no block in it has nowhere to put the cursor; a version can only be
                // empty if one was written before this rule existed, but the restore still has to
                // leave a document somebody can type into.
                .ifEmpty { listOf(DocBlockEntity(newId(), docId, BlockType.PARAGRAPH.key, "", false, 0)) }
        )
        counts.refreshDocCount(docId)
        return true
    }
}
