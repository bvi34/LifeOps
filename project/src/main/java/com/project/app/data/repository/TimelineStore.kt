package com.project.app.data.repository

import com.project.app.data.db.dao.ProjectDao
import com.project.app.data.db.entities.TimelineEventEntity
import com.project.app.logic.Timeline
import com.project.app.logic.TimelineEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The timeline: dated events in the story's own chronology, not the calendar's.
 */
class TimelineStore(
    private val dao: ProjectDao
) {

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
        dao.touchProject(projectId)
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
        dao.touchProject(projectId)
    }

    suspend fun deleteEvent(projectId: String, id: String) {
        dao.deleteEvent(id)
        dao.touchProject(projectId)
    }

    suspend fun moveEvent(projectId: String, id: String, delta: Int) {
        val rows = dao.getTimeline(projectId)
        val changed = Timeline.move(rows.map { it.toLogic() }, id, delta)
        if (changed.isEmpty()) return
        writeEventOrder(rows, changed)
        dao.touchProject(projectId)
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
        dao.touchProject(projectId)
    }

    private suspend fun writeEventOrder(rows: List<TimelineEventEntity>, changed: List<TimelineEvent>) {
        val byId = rows.associateBy { it.id }
        dao.upsertEvents(changed.mapNotNull { event -> byId[event.id]?.copy(sortOrder = event.order) })
    }
}
