package com.project.app.data.repository

import com.project.app.data.db.dao.CountByProject
import com.project.app.data.db.dao.ProjectDao
import com.project.app.data.db.entities.BoardCardEntity
import com.project.app.data.db.entities.BoardColumnEntity
import com.project.app.data.db.entities.DocBlockEntity
import com.project.app.data.db.entities.DocEntity
import com.project.app.data.db.entities.DocRevisionBlockEntity
import com.project.app.data.db.entities.DocRevisionEntity
import com.project.app.data.db.entities.LoreEntryEntity
import com.project.app.data.db.entities.OutlineNodeEntity
import com.project.app.data.db.entities.ProjectEntity
import com.project.app.data.db.entities.TimelineEventEntity
import com.project.app.data.model.Doc
import com.project.app.data.model.DocContent
import com.project.app.data.model.LoreEntryView
import com.project.app.logic.DocRevision
import com.project.app.logic.ProjectDestination
import com.project.app.logic.RevisionReason
import com.project.app.logic.Revisions
import com.project.app.data.model.Project
import com.project.app.logic.Board
import com.project.app.logic.BoardCard
import com.project.app.logic.BoardColumn
import com.project.app.logic.BlockType
import com.project.app.logic.DocBlock
import com.project.app.logic.DocBlocks
import com.project.app.logic.MarkdownTables
import com.project.app.logic.CompileOptions
import com.project.app.logic.Lore
import com.project.app.logic.LoreCategory
import com.project.app.logic.LoreEntry
import com.project.app.logic.Manuscript
import com.project.app.logic.ManuscriptDoc
import com.project.app.logic.Outline
import com.project.app.logic.OutlineNode
import com.project.app.logic.OutlineStatus
import com.project.app.logic.ProjectKind
import com.project.app.logic.ProjectPulse
import com.project.app.logic.ProjectSearch
import com.project.app.logic.SearchCorpus
import com.project.app.logic.SearchDoc
import com.project.app.logic.Timeline
import com.project.app.logic.Tree
import com.project.app.logic.TimelineEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Project's one repository: entity rows in, logic types out, and every write that spans more than
 * one row.
 *
 * Two rules run through the whole file.
 *
 * **The structural edits are decided in `logic/`, applied here.** Moving an outline node, indenting
 * it, dropping a card into another column — each of those is a pure function that returns *the rows
 * that changed*, and this class's job is to write exactly those rows. That is why there is no
 * reordering arithmetic below: doing it here would mean doing it again, differently, for each of the
 * three places a reorder can start from.
 *
 * **Derived numbers are written down, not recomputed on read.** A document's word count is stored on
 * the document, and a scene's word count is stored on the scene, because the shelf draws two hundred
 * of them at once and neither screen can afford to load every block to answer "how long is this". So
 * every write that can change a count updates it in the same breath — see [refreshDocCount], which
 * is called from every block edit and is the single place that keeps the two in step.
 */
class ProjectRepository(private val dao: ProjectDao) {

    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString()

    // ------------------------------------------------------------------ the shelf

    fun observeProjects(): Flow<List<Project>> =
        dao.observeAllProjects().map { rows -> rows.map { it.toModel() } }

    fun observeProject(id: String): Flow<Project?> = dao.observeProject(id).map { it?.toModel() }

    /** One project, read once — what the "reopen where I left off" check asks before navigating. */
    suspend fun getProject(id: String): Project? = dao.getProject(id)?.toModel()

    /**
     * A destination somebody asked to open at, or `null` if it is not there any more.
     *
     * Every way into this app from outside — an intent from another app in the suite, and the app's
     * own memory of where you were — names rows by id, and by the time it is opened a row may have
     * been deleted. Advisor can quote a document from a snapshot taken before it was thrown away;
     * "reopen the project I had last time" can name one archived on another screen a minute ago.
     *
     * So the address is checked before it is navigated to, and a stale one falls back to the shelf
     * rather than to a workspace for something that does not exist. A document is checked against
     * *its project*, not merely for existing: an address pairing a real document with a different
     * real project would otherwise open the editor with a back stack leading somewhere it was never
     * filed.
     */
    suspend fun resolve(destination: ProjectDestination): ProjectDestination? = when (destination) {
        is ProjectDestination.Shelf -> destination

        is ProjectDestination.Workspace ->
            destination.takeIf { dao.getProject(it.projectId) != null }

        is ProjectDestination.Document -> {
            val doc = dao.getDoc(destination.docId)
            if (doc != null && doc.projectId == destination.projectId) destination else null
        }
    }

    /**
     * Every project with the line that says where it has got to.
     *
     * Assembled from six streams rather than six queries per project: the tables are small, and a
     * shelf that re-queried per row would re-query on every keystroke in any of them.
     */
    fun observeShelf(): Flow<List<Pair<Project, ProjectPulse>>> {
        val counts = combine(
            dao.observeDocCounts(),
            dao.observeLoreCounts(),
            dao.observeEventCounts()
        ) { docs, lore, events -> Triple(docs.byProject(), lore.byProject(), events.byProject()) }

        val board = combine(dao.observeAllColumns(), dao.observeAllCards()) { columns, cards ->
            columns to cards
        }

        return combine(
            dao.observeAllProjects(),
            dao.observeAllOutlineNodes(),
            board,
            counts
        ) { projects, nodes, (columns, cards), (docCounts, loreCounts, eventCounts) ->
            val nodesByProject = nodes.groupBy { it.projectId }
            val columnsByProject = columns.groupBy { it.projectId }
            val cardsByProject = cards.groupBy { it.projectId }

            projects.map { row ->
                val project = row.toModel()
                val projectNodes = nodesByProject[row.id].orEmpty().map { it.toLogic() }
                val lanes = Board.lanes(
                    columnsByProject[row.id].orEmpty().map { it.toLogic() },
                    cardsByProject[row.id].orEmpty().map { it.toLogic() }
                )
                project to ProjectPulse(
                    kind = project.kind,
                    totals = Outline.projectTotals(projectNodes),
                    board = Board.stats(lanes),
                    docs = docCounts[row.id] ?: 0,
                    loreEntries = loreCounts[row.id] ?: 0,
                    events = eventCounts[row.id] ?: 0,
                    // The shelf does not spend a wiki index per project on every emission; broken
                    // links are a thing the Lore screen reports, where they can be acted on.
                    brokenLinks = 0,
                    updatedAt = project.updatedAt
                )
            }
        }
    }

    /**
     * Create a project, and give it a board to work on.
     *
     * The default columns are seeded here rather than lazily on first visit, because a board screen
     * that is empty until you invent your own columns is a board screen most people close again.
     * They are named for the kind of project (see [Board.defaultColumns]).
     */
    suspend fun addProject(name: String, kind: ProjectKind, summary: String?): String {
        val id = newId()
        val timestamp = now()
        val existing = dao.nextProjectSortOrder()
        dao.upsertProject(
            ProjectEntity(
                id = id,
                name = name.trim(),
                kind = kind.key,
                summary = summary.clean(),
                colorArgb = PROJECT_COLORS[existing.mod(PROJECT_COLORS.size)],
                archived = false,
                sortOrder = existing,
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        dao.upsertColumns(
            Board.defaultColumns(kind).mapIndexed { index, (columnName, isDone) ->
                BoardColumnEntity(
                    id = newId(),
                    projectId = id,
                    name = columnName,
                    sortOrder = index,
                    wipLimit = null,
                    isDone = isDone
                )
            }
        )
        return id
    }

    suspend fun updateProject(project: Project) {
        val existing = dao.getProject(project.id) ?: return
        dao.upsertProject(
            existing.copy(
                name = project.name.trim(),
                kind = project.kind.key,
                summary = project.summary.clean(),
                colorArgb = project.colorArgb,
                archived = project.archived,
                updatedAt = now()
            )
        )
    }

    suspend fun setArchived(projectId: String, archived: Boolean) {
        val existing = dao.getProject(projectId) ?: return
        dao.upsertProject(existing.copy(archived = archived, updatedAt = now()))
    }

    /** Delete a project outright. Everything in it cascades — that is what the foreign keys are for. */
    suspend fun deleteProject(projectId: String) = dao.deleteProject(projectId)

    // ------------------------------------------------------------------ outline

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
        touchProject(projectId)
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
        touchProject(existing.projectId)
    }

    suspend fun setOutlineStatus(id: String, status: OutlineStatus) {
        val existing = dao.getOutlineNode(id) ?: return
        dao.upsertOutlineNode(existing.copy(status = status.key, updatedAt = now()))
        touchProject(existing.projectId)
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
        touchProject(projectId)
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
        touchProject(projectId)
    }

    /** How much a delete would take with it, for the confirmation the screen shows first. */
    suspend fun subtreeSize(projectId: String, id: String): Int =
        Outline.subtree(dao.getOutline(projectId).map { it.toLogic() }, id).size

    // ------------------------------------------------------------------ docs

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
        touchProject(projectId)
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
        refreshDocCount(doc.id)
        existing.outlineNodeId?.let { syncOutlineWords(it) }
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
        touchProject(projectId)
    }

    suspend fun deleteDoc(docId: String) {
        val existing = dao.getDoc(docId) ?: return
        dao.deleteDocWithLinks(docId)
        existing.outlineNodeId?.let { syncOutlineWords(it) }
        touchProject(existing.projectId)
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
        refreshDocCount(docId)
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
        refreshDocCount(docId)
    }

    suspend fun deleteBlock(docId: String, blockId: String) {
        val remaining = dao.getBlocks(docId).filterNot { it.id == blockId }
        dao.deleteBlock(blockId)
        dao.upsertBlocks(remaining.mapIndexed { index, block -> block.copy(sortOrder = index) })
        refreshDocCount(docId)
    }

    /** Move a block one place up (-1) or down (+1). */
    suspend fun moveBlock(docId: String, blockId: String, delta: Int) {
        val blocks = dao.getBlocks(docId).toMutableList()
        val index = blocks.indexOfFirst { it.id == blockId }
        val target = index + delta
        if (index < 0 || target !in blocks.indices) return
        blocks.add(target, blocks.removeAt(index))
        dao.upsertBlocks(blocks.mapIndexed { position, block -> block.copy(sortOrder = position) })
        refreshDocCount(docId)
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
        saveRevision(docId, RevisionReason.IMPORT)
        val blocks = DocBlocks.parse(markdown) { newId() }
            .ifEmpty { listOf(DocBlock(newId(), BlockType.PARAGRAPH, "")) }
        dao.replaceBlocks(
            docId,
            blocks.mapIndexed { index, block ->
                DocBlockEntity(block.id, docId, block.type.key, block.text, block.checked, index)
            }
        )
        refreshDocCount(docId)
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
        saveRevision(docId, RevisionReason.REPAIR)
        val repaired = dao.getBlocks(docId).mapNotNull { row ->
            if (row.type == BlockType.TABLE.key || !MarkdownTables.isFlattened(row.text)) return@mapNotNull null
            MarkdownTables.recover(row.text)
                ?.let { row.copy(type = BlockType.TABLE.key, text = it.render()) }
        }
        if (repaired.isEmpty()) return 0
        dao.upsertBlocks(repaired)
        refreshDocCount(docId)
        return repaired.size
    }

    /** The document as Markdown — the export half of the same round trip. */
    suspend fun docAsMarkdown(docId: String): String =
        DocBlocks.render(dao.getBlocks(docId).map { it.toLogic() })

    /**
     * Recompute a document's stored word count, and push it into the scene the document is the text
     * of.
     *
     * Everything that edits a block ends here. Storing the count is what lets a list of documents
     * and a whole outline be drawn without loading a single block; keeping *this* the only place it
     * is computed is what stops the stored number and the blocks drifting apart.
     */
    private suspend fun refreshDocCount(docId: String) {
        val doc = dao.getDoc(docId) ?: return
        val words = DocBlocks.wordCount(dao.getBlocks(docId).map { it.toLogic() })
        dao.upsertDoc(doc.copy(wordCount = words, updatedAt = now()))
        doc.outlineNodeId?.let { syncOutlineWords(it) }
        touchProject(doc.projectId)
    }

    /**
     * A scene's word count is the sum of the documents written for it.
     *
     * Summed rather than taken from one document because a scene can have more than one — a draft
     * and its rewrite, or the scene and the notes for it — and because a scene with no document at
     * all should read as zero rather than keep the last number it happened to be given.
     */
    private suspend fun syncOutlineWords(outlineNodeId: String) {
        val node = dao.getOutlineNode(outlineNodeId) ?: return
        val total = dao.docsOf(node.projectId)
            .filter { it.outlineNodeId == outlineNodeId }
            .sumOf { it.wordCount }
        if (total != node.actualWords) {
            dao.upsertOutlineNode(node.copy(actualWords = total, updatedAt = now()))
        }
    }

    // ------------------------------------------------------------------ versions of a document

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
        refreshDocCount(docId)
        return true
    }

    // ------------------------------------------------------------------ lore

    fun observeLore(projectId: String): Flow<List<LoreEntryView>> =
        dao.observeLore(projectId).map { rows -> rows.toViews() }

    /**
     * The links that point at nothing — the list of entries worth writing next.
     *
     * It is computed on the fly rather than stored because it is a fact about the *set* of entries:
     * writing one page can fix a dozen broken links at once, and a stored list would have to be
     * invalidated by every edit to every body.
     */
    fun observeBrokenLinks(projectId: String): Flow<List<String>> =
        dao.observeLore(projectId).map { rows -> Lore.brokenLinks(rows.map { it.toLogic() }) }

    suspend fun addLoreEntry(
        projectId: String,
        name: String,
        category: LoreCategory,
        summary: String? = null,
        body: String = ""
    ): String {
        val id = newId()
        val timestamp = now()
        val order = dao.nextLoreSortOrder(projectId)
        dao.upsertLoreEntry(
            LoreEntryEntity(
                id = id,
                projectId = projectId,
                name = name.trim().ifEmpty { "Untitled" },
                category = category.key,
                summary = summary.clean(),
                body = body,
                colorArgb = PROJECT_COLORS[order.mod(PROJECT_COLORS.size)],
                sortOrder = order,
                createdAt = timestamp,
                updatedAt = timestamp,
                aliases = null
            )
        )
        touchProject(projectId)
        return id
    }

    suspend fun updateLoreEntry(
        projectId: String,
        entry: LoreEntry,
        category: LoreCategory
    ) {
        val rows = dao.getLore(projectId)
        val existing = rows.firstOrNull { it.id == entry.id } ?: return
        dao.upsertLoreEntry(
            existing.copy(
                name = entry.name.trim().ifEmpty { "Untitled" },
                category = category.key,
                summary = entry.summary.clean(),
                body = entry.body,
                aliases = Lore.joinAliases(entry.aliases).ifEmpty { null },
                updatedAt = now()
            )
        )
        touchProject(projectId)
    }

    suspend fun deleteLoreEntry(projectId: String, id: String) {
        dao.deleteLoreEntry(id)
        touchProject(projectId)
    }

    // ------------------------------------------------------------------ timeline

    fun observeTimeline(projectId: String): Flow<List<TimelineEvent>> =
        dao.observeTimeline(projectId).map { rows -> rows.map { it.toLogic() } }

    suspend fun addEvent(
        projectId: String,
        title: String,
        whenLabel: String?,
        era: String?,
        detail: String? = null,
        outlineNodeId: String? = null
    ): String {
        val id = newId()
        val events = dao.getTimeline(projectId).map { it.toLogic() }
        dao.upsertEvent(
            TimelineEventEntity(
                id = id,
                projectId = projectId,
                title = title.trim().ifEmpty { "Untitled" },
                detail = detail.clean(),
                era = era.clean(),
                whenLabel = whenLabel.clean(),
                sortOrder = Timeline.nextOrder(events),
                outlineNodeId = outlineNodeId,
                createdAt = now()
            )
        )
        touchProject(projectId)
        return id
    }

    suspend fun updateEvent(projectId: String, event: TimelineEvent) {
        val existing = dao.getTimeline(projectId).firstOrNull { it.id == event.id } ?: return
        dao.upsertEvent(
            existing.copy(
                title = event.title.trim().ifEmpty { "Untitled" },
                detail = event.detail.clean(),
                era = event.era.clean(),
                whenLabel = event.whenLabel.clean(),
                outlineNodeId = event.outlineNodeId
            )
        )
        touchProject(projectId)
    }

    suspend fun deleteEvent(projectId: String, id: String) {
        dao.deleteEvent(id)
        touchProject(projectId)
    }

    suspend fun moveEvent(projectId: String, id: String, delta: Int) {
        val rows = dao.getTimeline(projectId)
        val changed = Timeline.move(rows.map { it.toLogic() }, id, delta)
        if (changed.isEmpty()) return
        writeEventOrder(rows, changed)
        touchProject(projectId)
    }

    /**
     * Re-order the timeline by what the "when" labels say.
     *
     * Offered rather than applied automatically, and only when every label reads on one scale — see
     * `logic/Timeline`. The author may well be right and the label a typo, and a timeline that
     * silently rearranges itself around a mistyped year is worse than one that points at it.
     */
    suspend fun autoSortTimeline(projectId: String) {
        val rows = dao.getTimeline(projectId)
        val events = rows.map { it.toLogic() }
        if (!Timeline.canAutoSort(events)) return
        val sorted = Timeline.autoSorted(events)
        val byId = events.associateBy { it.id }
        writeEventOrder(rows, sorted.filter { byId[it.id]?.order != it.order })
        touchProject(projectId)
    }

    private suspend fun writeEventOrder(rows: List<TimelineEventEntity>, changed: List<TimelineEvent>) {
        val byId = rows.associateBy { it.id }
        dao.upsertEvents(changed.mapNotNull { event -> byId[event.id]?.copy(sortOrder = event.order) })
    }

    // ------------------------------------------------------------------ board

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
        touchProject(projectId)
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
        touchProject(projectId)
    }

    suspend fun moveColumn(projectId: String, columnId: String, delta: Int) {
        val columns = dao.getColumns(projectId).toMutableList()
        val index = columns.indexOfFirst { it.id == columnId }
        val target = index + delta
        if (index < 0 || target !in columns.indices) return
        columns.add(target, columns.removeAt(index))
        dao.upsertColumns(columns.mapIndexed { position, column -> column.copy(sortOrder = position) })
        touchProject(projectId)
    }

    /** Delete a column. Its cards stay — see the DAO's note, and [refileOrphans]. */
    suspend fun deleteColumn(projectId: String, columnId: String) {
        dao.deleteColumn(columnId)
        touchProject(projectId)
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
        touchProject(projectId)
    }

    suspend fun addCard(
        projectId: String,
        columnId: String,
        title: String,
        notes: String? = null,
        outlineNodeId: String? = null,
        docId: String? = null
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
                createdAt = now(),
                doneAt = null
            )
        )
        touchProject(projectId)
        return id
    }

    suspend fun updateCard(projectId: String, card: BoardCard) {
        val existing = dao.getCard(card.id) ?: return
        dao.upsertCard(
            existing.copy(
                title = card.title.trim().ifEmpty { "Untitled" },
                notes = card.notes.clean(),
                outlineNodeId = card.outlineNodeId,
                docId = card.docId
            )
        )
        touchProject(projectId)
    }

    suspend fun deleteCard(projectId: String, id: String) {
        dao.deleteCard(id)
        touchProject(projectId)
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
        touchProject(projectId)
    }

    // ------------------------------------------------------------------ search & compile

    /**
     * Everything in a project that has text in it, in the shape the search matcher wants.
     *
     * The *query* is deliberately not part of this: it lives in the search screen, and filtering
     * happens in pure code over an already-loaded corpus. Pushing the query into SQL would mean a
     * round trip per keystroke, five `LIKE` scans a time, and a matching rule split between Kotlin
     * and SQLite that could disagree with itself. A project's text is small enough to hold.
     */
    fun observeSearchCorpus(projectId: String): Flow<SearchCorpus> {
        val documents = combine(
            dao.observeDocs(projectId),
            dao.observeBlocksOfProject(projectId)
        ) { docs, blocks ->
            val byDoc = blocks.groupBy { it.docId }
            docs.map { doc ->
                SearchDoc(
                    id = doc.id,
                    title = doc.title,
                    // The text as it reads, not as it is stored: a search for a word should find
                    // it whether or not somebody put asterisks round it, and a hit inside a table
                    // should show the cells rather than the pipes between them.
                    text = byDoc[doc.id].orEmpty()
                        .joinToString(" ") { DocBlocks.plainText(it.toLogic()) }
                )
            }
        }

        val rest = combine(
            dao.observeLore(projectId),
            dao.observeTimeline(projectId),
            dao.observeCards(projectId)
        ) { lore, events, cards ->
            Triple(lore.map { it.toLogic() }, events.map { it.toLogic() }, cards.map { it.toLogic() })
        }

        return combine(
            dao.observeOutline(projectId),
            documents,
            rest
        ) { outline, docs, (lore, events, cards) ->
            SearchCorpus(
                outline = outline.map { it.toLogic() },
                docs = docs,
                lore = lore,
                events = events,
                cards = cards
            )
        }
    }

    /** Hits for [query], matched over the corpus in pure code. */
    fun observeSearch(projectId: String, query: String): Flow<List<com.project.app.logic.SearchHit>> =
        observeSearchCorpus(projectId).map { corpus ->
            ProjectSearch.search(query, corpus.outline, corpus.docs, corpus.lore, corpus.events, corpus.cards)
        }

    /**
     * The whole project as one manuscript.
     *
     * Read once, on demand, and never stored: a compile is a *view* of the outline and the documents
     * it links, so caching it would only create a second answer to "how long is this" that could go
     * stale the moment somebody typed.
     */
    suspend fun compile(projectId: String, options: CompileOptions): Manuscript? {
        val project = dao.getProject(projectId) ?: return null
        val rows = Outline.flatten(dao.getOutline(projectId).map { it.toLogic() })
        val blocks = dao.blocksOfProject(projectId).groupBy { it.docId }
        val docs = dao.docsOf(projectId).map { doc ->
            ManuscriptDoc(
                id = doc.id,
                outlineNodeId = doc.outlineNodeId,
                title = doc.title,
                sortOrder = doc.sortOrder,
                blocks = blocks[doc.id].orEmpty().map { it.toLogic() }
            )
        }
        return Manuscript.compile(project.name, rows, docs, options)
    }

    // ------------------------------------------------------------------ shared

    /**
     * Stamp the project as touched.
     *
     * Every write goes through here, which is what makes "last worked on" mean *anything you did in
     * the project* rather than "the last time you renamed it". The shelf sorts nothing by it, but it
     * is the line under a project's name that tells you which of five you actually left half-done.
     */
    private suspend fun touchProject(projectId: String) {
        val existing = dao.getProject(projectId) ?: return
        dao.upsertProject(existing.copy(updatedAt = now()))
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun List<CountByProject>.byProject(): Map<String, Int> =
        associate { it.projectId to it.total }

    private fun List<LoreEntryEntity>.toViews(): List<LoreEntryView> {
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

    companion object {
        /**
         * The colours a project (and a lore entry) is given when it is created — handed out in
         * order so a shelf of six is six different colours rather than a coin toss that lands on
         * the same blue twice.
         */
        val PROJECT_COLORS = listOf(
            0xFFB45309L, 0xFF1D4ED8L, 0xFF047857L, 0xFF7C3AEDL,
            0xFFBE123CL, 0xFF0E7490L, 0xFF4D7C0FL, 0xFF9D174DL
        )
    }
}

// --- mapping ---

fun ProjectEntity.toModel() = Project(
    id = id,
    name = name,
    kind = ProjectKind.fromKey(kind),
    summary = summary,
    colorArgb = colorArgb,
    archived = archived,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun OutlineNodeEntity.toLogic() = OutlineNode(
    id = id,
    parentId = parentId,
    title = title,
    synopsis = synopsis,
    status = OutlineStatus.fromKey(status),
    targetWords = targetWords,
    actualWords = actualWords,
    sortOrder = sortOrder
)

fun DocEntity.toModel() = Doc(
    id = id,
    projectId = projectId,
    parentDocId = parentDocId,
    title = title,
    icon = icon,
    outlineNodeId = outlineNodeId,
    wordCount = wordCount,
    sortOrder = sortOrder,
    updatedAt = updatedAt
)

fun DocBlockEntity.toLogic() = DocBlock(
    id = id,
    type = BlockType.fromKey(type),
    text = text,
    checked = checked
)

fun DocRevisionEntity.toLogic() = DocRevision(
    id = id,
    docId = docId,
    reason = RevisionReason.fromKey(reason),
    wordCount = wordCount,
    savedAt = savedAt
)

fun DocRevisionBlockEntity.toLogic() = DocBlock(
    id = id,
    type = BlockType.fromKey(type),
    text = text,
    checked = checked
)

fun LoreEntryEntity.toLogic() = LoreEntry(
    id = id,
    name = name,
    category = LoreCategory.fromKey(category),
    summary = summary,
    body = body,
    aliases = Lore.parseAliases(aliases)
)

fun TimelineEventEntity.toLogic() = TimelineEvent(
    id = id,
    title = title,
    detail = detail,
    era = era,
    whenLabel = whenLabel,
    order = sortOrder,
    outlineNodeId = outlineNodeId
)

fun BoardColumnEntity.toLogic() = BoardColumn(
    id = id,
    name = name,
    sortOrder = sortOrder,
    wipLimit = wipLimit,
    isDone = isDone
)

fun BoardCardEntity.toLogic() = BoardCard(
    id = id,
    columnId = columnId,
    title = title,
    notes = notes,
    sortOrder = sortOrder,
    outlineNodeId = outlineNodeId,
    docId = docId,
    createdAt = createdAt,
    doneAt = doneAt
)
