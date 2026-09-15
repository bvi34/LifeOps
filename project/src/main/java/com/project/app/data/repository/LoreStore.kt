package com.project.app.data.repository

import com.project.app.data.db.dao.ProjectDao
import com.project.app.data.db.entities.LoreEntryEntity
import com.project.app.data.model.LoreEntryView
import com.project.app.logic.Lore
import com.project.app.logic.LoreCategory
import com.project.app.logic.LoreEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The lore: the people, places and things a project keeps notes about, and the links
 * `logic/Lore` finds between them.
 */
class LoreStore(
    private val dao: ProjectDao,
    private val labels: RecordLabels
) {

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
                colorArgb = ProjectRepository.PROJECT_COLORS[order.mod(ProjectRepository.PROJECT_COLORS.size)],
                sortOrder = order,
                createdAt = timestamp,
                updatedAt = timestamp,
                aliases = null
            )
        )
        dao.touchProject(projectId)
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
        if (existing.name != entry.name.trim()) labels.relabelRecord(projectId, entry.id, entry.name.trim())
        dao.touchProject(projectId)
    }

    suspend fun deleteLoreEntry(projectId: String, id: String) {
        dao.deleteLoreEntry(id)
        dao.touchProject(projectId)
    }
}
