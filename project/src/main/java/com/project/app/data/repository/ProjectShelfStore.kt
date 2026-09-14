package com.project.app.data.repository

import com.project.app.data.db.dao.ProjectDao
import com.project.app.data.db.entities.BoardColumnEntity
import com.project.app.data.db.entities.ProjectEntity
import com.project.app.data.model.Project
import com.project.app.logic.Board
import com.project.app.logic.Lore
import com.project.app.logic.Outline
import com.project.app.logic.ProjectDestination
import com.project.app.logic.ProjectKind
import com.project.app.logic.ProjectPulse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * The shelf: the projects themselves, and what is true of a project as a whole.

 * Renaming one is the only write here that reaches outside the module — a project's name is baked
 * into every drawer label its records own, so [RecordLabels] re-stamps all of them.
 */
class ProjectShelfStore(
    private val dao: ProjectDao,
    private val labels: RecordLabels
) {

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
                colorArgb = ProjectRepository.PROJECT_COLORS[existing.mod(ProjectRepository.PROJECT_COLORS.size)],
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
        val renamed = existing.name != project.name.trim()
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
        // Every drawer, not just the project's own: a record's label leads with the project, so
        // renaming "The Kestrel" leaves every scene and card in it saying the old name otherwise.
        if (renamed) labels.relabelEveryRecordOf(project.id)
    }

    suspend fun setArchived(projectId: String, archived: Boolean) {
        val existing = dao.getProject(projectId) ?: return
        dao.upsertProject(existing.copy(archived = archived, updatedAt = now()))
    }

    /** Delete a project outright. Everything in it cascades — that is what the foreign keys are for. */
    suspend fun deleteProject(projectId: String) = dao.deleteProject(projectId)
}
