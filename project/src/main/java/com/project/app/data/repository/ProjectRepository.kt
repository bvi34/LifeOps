package com.project.app.data.repository

import com.project.app.data.db.dao.ProjectDao
import com.project.app.logic.CardStore
import com.project.app.logic.CardTasks

/**
 * Project's composition root: one object the screens and the connection routes are handed, holding
 * one store per thing a project is made of.
 *
 * This class decides nothing. It owns no queries and no timestamps — it wires the stores together
 * and hands them out. Three collaborators are built here because they belong to no single store:
 *
 *  - [RecordLabels], because five stores rename something and every rename has to reach the
 *    household shelf.
 *  - [DocCounts], because editing a block and restoring a version change the same three numbers.
 *  - [BoardStore], which [WeekHandoffStore] needs in order to finish a card the board's way when
 *    the LifeOps task published from it is ticked.
 *
 * It implements [CardStore] by forwarding to [week], so that `logic/CardRound` can drive the
 * hand-off without knowing a database is on the other side of it.
 */
class ProjectRepository(
    dao: ProjectDao,
    /**
     * How a rename reaches the household's shelf — see [RecordLabels], which is the only thing that
     * calls it.
     *
     * A lambda rather than a dependency on `:repository`'s runtime, so this class stays a thing that
     * takes a DAO — every test in `ProjectRepositoryTest` builds one without a document store, and
     * the two tests that care about labels pass a recorder instead of standing up another database.
     * The default does nothing, which is the truth in a host with no shelf.
     */
    relabelDocuments: suspend (recordKey: String, label: String) -> Unit = { _, _ -> }
) : CardStore {

    private val labels = RecordLabels(dao, relabelDocuments)
    private val counts = DocCounts(dao)

    val shelf = ProjectShelfStore(dao, labels)
    val outline = OutlineStore(dao, labels)
    val versions = DocVersionStore(dao, counts)
    val docs = DocStore(dao, counts, versions)
    val lore = LoreStore(dao, labels)
    val timeline = TimelineStore(dao)
    val board = BoardStore(dao, labels)
    val files = RecordFileStore(dao, labels)
    val lookups = LookupStore(dao, board)
    val week = WeekHandoffStore(dao, board)
    val search = ProjectSearchStore(dao)

    // `logic/CardRound` is handed this object as its CardStore; the week is what actually answers.
    override suspend fun cardSnapshots(): List<CardTasks.CardSnapshot> = week.cardSnapshots()

    override suspend fun setCardLink(cardId: String, taskId: String?, publishedDue: Long?) =
        week.setCardLink(cardId, taskId, publishedDue)

    override suspend fun completeFromWeek(cardId: String, completedAt: Long): Boolean =
        week.completeFromWeek(cardId, completedAt)

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
