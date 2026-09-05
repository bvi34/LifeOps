package com.project.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.project.app.data.db.entities.BoardCardEntity
import com.project.app.data.db.entities.BoardColumnEntity
import com.project.app.data.db.entities.DocBlockEntity
import com.project.app.data.db.entities.DocEntity
import com.project.app.data.db.entities.LoreEntryEntity
import com.project.app.data.db.entities.OutlineNodeEntity
import com.project.app.data.db.entities.ProjectEntity
import com.project.app.data.db.entities.TimelineEventEntity
import kotlinx.coroutines.flow.Flow

/** One `GROUP BY projectId` row — how many of something a project holds. */
data class CountByProject(val projectId: String, val total: Int)

/**
 * Project's one DAO.
 *
 * It is one interface rather than six because every query in it is scoped by the same `projectId`
 * and the interesting reads cross sections — the shelf's pulse needs the outline, the board and the
 * counts together, and a card needs the title of the outline node it points at. Splitting that into
 * a DAO per section would mean a repository that stitched six flows together for every screen.
 *
 * Ordering is always `(sortOrder, <name>)` rather than `sortOrder` alone: rows restored from a
 * backup can share a position, and a list that reorders itself between two identical reads is the
 * kind of bug nobody reproduces on purpose.
 */
@Dao
interface ProjectDao {

    // --- projects ---

    @Query("SELECT * FROM projects WHERE archived = 0 ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeActiveProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY archived, sortOrder, name COLLATE NOCASE")
    fun observeAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeProject(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProject(id: String): ProjectEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM projects")
    suspend fun nextProjectSortOrder(): Int

    @Upsert
    suspend fun upsertProject(project: ProjectEntity)

    /** Everything under the project cascades; only the row itself has to be named. */
    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: String)

    // --- the shelf ---
    //
    // The shelf draws every project with a line saying where it has got to, and that line is
    // assembled from the outline, the board and three counts. Asking those per project would be a
    // query per project per section; these read the lot in one go and the repository groups them.
    // The tables are small — an outline is hundreds of rows, not millions — so the honest simple
    // thing is also the fast one.

    @Query("SELECT * FROM outline_nodes ORDER BY sortOrder, title COLLATE NOCASE")
    fun observeAllOutlineNodes(): Flow<List<OutlineNodeEntity>>

    @Query("SELECT * FROM board_columns ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAllColumns(): Flow<List<BoardColumnEntity>>

    @Query("SELECT * FROM board_cards ORDER BY sortOrder, title COLLATE NOCASE")
    fun observeAllCards(): Flow<List<BoardCardEntity>>

    @Query("SELECT projectId, COUNT(*) AS total FROM docs GROUP BY projectId")
    fun observeDocCounts(): Flow<List<CountByProject>>

    @Query("SELECT projectId, COUNT(*) AS total FROM lore_entries GROUP BY projectId")
    fun observeLoreCounts(): Flow<List<CountByProject>>

    @Query("SELECT projectId, COUNT(*) AS total FROM timeline_events GROUP BY projectId")
    fun observeEventCounts(): Flow<List<CountByProject>>

    // --- the whole shelf, once ---
    //
    // One-shot reads of every row of a section, across every project. They exist for readers that
    // want the shelf entire rather than one project at a time — Advisor's knowledge source flattens
    // the lot into its retrieval corpus on each question — and they are suspend rather than Flow
    // because such a reader takes a snapshot and is done, with nothing to keep observing.

    @Query("SELECT * FROM projects ORDER BY archived, sortOrder, name COLLATE NOCASE")
    suspend fun allProjects(): List<ProjectEntity>

    @Query("SELECT * FROM outline_nodes ORDER BY projectId, sortOrder, title COLLATE NOCASE")
    suspend fun allOutlineNodes(): List<OutlineNodeEntity>

    @Query("SELECT * FROM docs ORDER BY projectId, sortOrder, title COLLATE NOCASE")
    suspend fun allDocs(): List<DocEntity>

    @Query("SELECT * FROM doc_blocks ORDER BY docId, sortOrder")
    suspend fun allBlocks(): List<DocBlockEntity>

    @Query("SELECT * FROM lore_entries ORDER BY projectId, sortOrder, name COLLATE NOCASE")
    suspend fun allLoreEntries(): List<LoreEntryEntity>

    @Query("SELECT * FROM timeline_events ORDER BY projectId, sortOrder, title COLLATE NOCASE")
    suspend fun allTimelineEvents(): List<TimelineEventEntity>

    @Query("SELECT * FROM board_columns ORDER BY projectId, sortOrder, name COLLATE NOCASE")
    suspend fun allColumns(): List<BoardColumnEntity>

    @Query("SELECT * FROM board_cards ORDER BY projectId, sortOrder, title COLLATE NOCASE")
    suspend fun allCards(): List<BoardCardEntity>

    // --- outline ---

    @Query("SELECT * FROM outline_nodes WHERE projectId = :projectId ORDER BY sortOrder, title COLLATE NOCASE")
    fun observeOutline(projectId: String): Flow<List<OutlineNodeEntity>>

    @Query("SELECT * FROM outline_nodes WHERE projectId = :projectId ORDER BY sortOrder, title COLLATE NOCASE")
    suspend fun getOutline(projectId: String): List<OutlineNodeEntity>

    @Query("SELECT * FROM outline_nodes WHERE id = :id")
    suspend fun getOutlineNode(id: String): OutlineNodeEntity?

    @Upsert
    suspend fun upsertOutlineNode(node: OutlineNodeEntity)

    @Upsert
    suspend fun upsertOutlineNodes(nodes: List<OutlineNodeEntity>)

    @Query("DELETE FROM outline_nodes WHERE id IN (:ids)")
    suspend fun deleteOutlineNodes(ids: List<String>)

    /**
     * Cut the soft links that pointed at deleted outline nodes.
     *
     * Run as part of the same transaction as the delete, because the alternative — leaving them to
     * dangle — means a card whose "linked scene" silently resolves to nothing for ever, with no way
     * for the user to tell a broken link from one that was never made.
     */
    @Query("UPDATE board_cards SET outlineNodeId = NULL WHERE outlineNodeId IN (:ids)")
    suspend fun unlinkCardsFromOutline(ids: List<String>)

    @Query("UPDATE docs SET outlineNodeId = NULL WHERE outlineNodeId IN (:ids)")
    suspend fun unlinkDocsFromOutline(ids: List<String>)

    @Query("UPDATE timeline_events SET outlineNodeId = NULL WHERE outlineNodeId IN (:ids)")
    suspend fun unlinkEventsFromOutline(ids: List<String>)

    @Transaction
    suspend fun deleteOutlineSubtree(ids: List<String>) {
        if (ids.isEmpty()) return
        unlinkCardsFromOutline(ids)
        unlinkDocsFromOutline(ids)
        unlinkEventsFromOutline(ids)
        deleteOutlineNodes(ids)
    }

    // --- docs ---

    @Query("SELECT * FROM docs WHERE projectId = :projectId ORDER BY sortOrder, title COLLATE NOCASE")
    fun observeDocs(projectId: String): Flow<List<DocEntity>>

    @Query("SELECT * FROM docs WHERE id = :id")
    fun observeDoc(id: String): Flow<DocEntity?>

    @Query("SELECT * FROM docs WHERE id = :id")
    suspend fun getDoc(id: String): DocEntity?

    /** Every document in a project — the sum behind a scene's word count. */
    @Query("SELECT * FROM docs WHERE projectId = :projectId ORDER BY sortOrder, title COLLATE NOCASE")
    suspend fun docsOf(projectId: String): List<DocEntity>

    @Query("SELECT COUNT(*) FROM docs WHERE projectId = :projectId")
    fun observeDocCount(projectId: String): Flow<Int>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM docs WHERE projectId = :projectId")
    suspend fun nextDocSortOrder(projectId: String): Int

    @Upsert
    suspend fun upsertDoc(doc: DocEntity)

    @Query("DELETE FROM docs WHERE id = :id")
    suspend fun deleteDoc(id: String)

    @Query("UPDATE board_cards SET docId = NULL WHERE docId = :docId")
    suspend fun unlinkCardsFromDoc(docId: String)

    @Transaction
    suspend fun deleteDocWithLinks(docId: String) {
        unlinkCardsFromDoc(docId)
        deleteDoc(docId)
    }

    // --- doc blocks ---

    @Query("SELECT * FROM doc_blocks WHERE docId = :docId ORDER BY sortOrder")
    fun observeBlocks(docId: String): Flow<List<DocBlockEntity>>

    @Query("SELECT * FROM doc_blocks WHERE docId = :docId ORDER BY sortOrder")
    suspend fun getBlocks(docId: String): List<DocBlockEntity>

    /**
     * Every block in a project, joined through its document.
     *
     * Blocks carry no `projectId` of their own — they belong to a document, which belongs to a
     * project — so this is the one query that has to reach across. Both readers of it want the whole
     * corpus anyway: search matches over all of it, and a compile renders all of it.
     */
    @Query(
        "SELECT b.* FROM doc_blocks b INNER JOIN docs d ON d.id = b.docId " +
            "WHERE d.projectId = :projectId ORDER BY b.docId, b.sortOrder"
    )
    fun observeBlocksOfProject(projectId: String): Flow<List<DocBlockEntity>>

    @Query(
        "SELECT b.* FROM doc_blocks b INNER JOIN docs d ON d.id = b.docId " +
            "WHERE d.projectId = :projectId ORDER BY b.docId, b.sortOrder"
    )
    suspend fun blocksOfProject(projectId: String): List<DocBlockEntity>

    @Upsert
    suspend fun upsertBlock(block: DocBlockEntity)

    @Upsert
    suspend fun upsertBlocks(blocks: List<DocBlockEntity>)

    @Query("DELETE FROM doc_blocks WHERE id = :id")
    suspend fun deleteBlock(id: String)

    @Query("DELETE FROM doc_blocks WHERE docId = :docId")
    suspend fun deleteBlocksOf(docId: String)

    /** Replace a document's contents wholesale — what pasting Markdown into it does. */
    @Transaction
    suspend fun replaceBlocks(docId: String, blocks: List<DocBlockEntity>) {
        deleteBlocksOf(docId)
        upsertBlocks(blocks)
    }

    // --- lore ---

    @Query("SELECT * FROM lore_entries WHERE projectId = :projectId ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeLore(projectId: String): Flow<List<LoreEntryEntity>>

    @Query("SELECT * FROM lore_entries WHERE id = :id")
    fun observeLoreEntry(id: String): Flow<LoreEntryEntity?>

    @Query("SELECT * FROM lore_entries WHERE projectId = :projectId ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun getLore(projectId: String): List<LoreEntryEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM lore_entries WHERE projectId = :projectId")
    suspend fun nextLoreSortOrder(projectId: String): Int

    @Upsert
    suspend fun upsertLoreEntry(entry: LoreEntryEntity)

    @Query("DELETE FROM lore_entries WHERE id = :id")
    suspend fun deleteLoreEntry(id: String)

    // --- timeline ---

    @Query("SELECT * FROM timeline_events WHERE projectId = :projectId ORDER BY sortOrder, title COLLATE NOCASE")
    fun observeTimeline(projectId: String): Flow<List<TimelineEventEntity>>

    @Query("SELECT * FROM timeline_events WHERE projectId = :projectId ORDER BY sortOrder, title COLLATE NOCASE")
    suspend fun getTimeline(projectId: String): List<TimelineEventEntity>

    @Upsert
    suspend fun upsertEvent(event: TimelineEventEntity)

    @Upsert
    suspend fun upsertEvents(events: List<TimelineEventEntity>)

    @Query("DELETE FROM timeline_events WHERE id = :id")
    suspend fun deleteEvent(id: String)

    // --- board ---

    @Query("SELECT * FROM board_columns WHERE projectId = :projectId ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeColumns(projectId: String): Flow<List<BoardColumnEntity>>

    @Query("SELECT * FROM board_columns WHERE projectId = :projectId ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun getColumns(projectId: String): List<BoardColumnEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM board_columns WHERE projectId = :projectId")
    suspend fun nextColumnSortOrder(projectId: String): Int

    @Upsert
    suspend fun upsertColumn(column: BoardColumnEntity)

    @Upsert
    suspend fun upsertColumns(columns: List<BoardColumnEntity>)

    /**
     * Delete a column and leave its cards where they are.
     *
     * Deliberately *not* a cascade: the cards become orphans that `Board.orphans` surfaces and the
     * board screen offers to re-file. Losing a column is an organisational decision; losing the work
     * that was in it is never one anybody made on purpose.
     */
    @Query("DELETE FROM board_columns WHERE id = :id")
    suspend fun deleteColumn(id: String)

    @Query("SELECT * FROM board_cards WHERE projectId = :projectId ORDER BY sortOrder, title COLLATE NOCASE")
    fun observeCards(projectId: String): Flow<List<BoardCardEntity>>

    @Query("SELECT * FROM board_cards WHERE projectId = :projectId ORDER BY sortOrder, title COLLATE NOCASE")
    suspend fun getCards(projectId: String): List<BoardCardEntity>

    @Query("SELECT * FROM board_cards WHERE id = :id")
    suspend fun getCard(id: String): BoardCardEntity?

    @Upsert
    suspend fun upsertCard(card: BoardCardEntity)

    @Upsert
    suspend fun upsertCards(cards: List<BoardCardEntity>)

    @Query("DELETE FROM board_cards WHERE id = :id")
    suspend fun deleteCard(id: String)
}
