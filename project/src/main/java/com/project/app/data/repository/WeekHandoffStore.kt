package com.project.app.data.repository

import com.project.app.data.db.dao.ProjectDao
import com.project.app.logic.Board
import com.project.app.logic.CardStore
import com.project.app.logic.CardTasks
import kotlinx.coroutines.flow.map

/**
 * The seam to the LifeOps week: publishing a card as a task, and hearing back when the
 * task is ticked.
 *
 * Implements [CardStore] because `logic/CardRound` drives the hand-off and should not know that a
 * database is on the other side of it.
 */
class WeekHandoffStore(
    private val dao: ProjectDao,
    private val board: BoardStore
) : CardStore {

    /**
     * Every card that could have anything to do with the week, with the facts to decide on.
     *
     * Read in one go across every project rather than per board: a round is about the *week*, and
     * the week does not care which project a deadline came from. Cards with neither a date nor a
     * link are dropped here rather than in the round — they can never produce an action, and
     * carrying every card in the household through a decision to reach Idle is work for nothing.
     */
    override suspend fun cardSnapshots(): List<CardTasks.CardSnapshot> {
        val projects = dao.allProjects().associateBy { it.id }
        val doneColumns = dao.allColumns().filter { it.isDone }.mapTo(mutableSetOf()) { it.id }

        return dao.allCards()
            .filter { it.dueOn != null || it.lifeOpsTaskId != null }
            .mapNotNull { row ->
                val project = projects[row.projectId] ?: return@mapNotNull null
                CardTasks.CardSnapshot(
                    card = row.toLogic(),
                    projectName = project.name,
                    // A card in a column the board calls finished has nothing left to ask of a
                    // planner — and one whose column was deleted is stranded rather than finished,
                    // which is why this asks the column and not merely whether the id is known.
                    inDoneColumn = row.columnId in doneColumns,
                    link = CardTasks.TaskLink(row.lifeOpsTaskId, row.publishedDue)
                )
            }
    }

    override suspend fun setCardLink(cardId: String, taskId: String?, publishedDue: Long?) {
        val existing = dao.getCard(cardId) ?: return
        dao.upsertCard(existing.copy(lifeOpsTaskId = taskId, publishedDue = publishedDue))
    }

    /**
     * Finish the card a tick in LifeOps stands for.
     *
     * "Finished" on a board means being in the column the board calls finished, so the card is
     * *moved* — through the same `Board.move` a drag goes through, so the position arithmetic and
     * the stamping of `doneAt` happen exactly once, in one place. A board whose finished column has
     * been deleted still has to be able to take the tick, so the card is stamped where it stands
     * rather than the completion being dropped on the floor.
     *
     * Returns whether anything actually moved: a card already finished here reports false, so a
     * round that runs twice over the same tick counts it once.
     */
    override suspend fun completeFromWeek(cardId: String, completedAt: Long): Boolean {
        val row = dao.getCard(cardId) ?: return false
        // Let go of the task: it has been ticked, and nothing here should ask about it again. Done
        // first, so a failure to move the card still cannot leave the round chasing a finished task.
        dao.upsertCard(row.copy(lifeOpsTaskId = null, publishedDue = null))
        return board.moveToDone(row.projectId, cardId, completedAt)
    }

    /**
     * The LifeOps tasks standing for the cards of a project — read *before* it is deleted, so the
     * caller can take them off the week afterwards.
     *
     * A project's cards cascade with it, which means the round can never see them again to work out
     * that their tasks should go. Somebody would be left with a week full of tasks for a project
     * that no longer exists and no way to tell where they came from.
     */
    suspend fun publishedTaskIdsOf(projectId: String): List<String> =
        dao.getCards(projectId).mapNotNull { it.lifeOpsTaskId }
}
