package com.lifeops.app.data.repository

import com.lifeops.app.util.GrowthData
import com.lifeops.app.util.GrowthExport
import com.lifeops.app.util.GrowthRings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * Single source of truth that adapts the app's live aspects + sealed week snapshots (and a
 * live time-entry fallback) into the Growth Record render/export model. Shared by the Growth
 * screen and the unified export actions in Settings, so rings exports flow through the same
 * path as the JSON/CSV backups rather than a one-off mechanism.
 */
class GrowthRepository(
    private val weekRepository: WeekRepository,
    private val aspectRepository: AspectRepository,
    private val taskRepository: TaskRepository,
    private val timeEntryRepository: TimeEntryRepository
) {
    /** Emits whenever anything that affects the rings changes (weeks, snapshots or aspects). */
    fun observeChanges(): Flow<Unit> = combine(
        weekRepository.observeAllWeeks(),
        weekRepository.observeSnapshots(),
        aspectRepository.observeAllAspects()
    ) { _, _, _ -> }

    suspend fun assemble(): GrowthData.Assembled = withContext(Dispatchers.Default) {
        val liveAspects = aspectRepository.getAllAspectsSync()
            .map { GrowthData.LiveAspect(it.id, it.name, it.color) }
        val snapshots = weekRepository.getAllSnapshotsSync().associateBy { it.weekId }
        val taskById = taskRepository.getAllTasks().associateBy { it.id }

        // Live fallback: weekId -> aspectId -> minutes, for the open week and any pre-backfill week.
        val minutesByWeekAspect = HashMap<String, HashMap<String, Int>>()
        timeEntryRepository.getAllSince(EPOCH).forEach { entry ->
            val task = taskById[entry.taskId] ?: return@forEach
            val aspectId = task.aspectId ?: return@forEach
            val perWeek = minutesByWeekAspect.getOrPut(task.weekId) { HashMap() }
            perWeek[aspectId] = (perWeek[aspectId] ?: 0) + entry.durationMinutes
        }

        val sources = weekRepository.getAllWeeksSync().map { week ->
            val sealedHistory = snapshots[week.id]?.aspectHistory.orEmpty().mapValues {
                GrowthData.AspectHist(it.value.minutes, it.value.name, it.value.colorHex)
            }
            GrowthData.WeekSource(
                weekId = week.id,
                startDate = week.startDate,
                isClosed = week.isClosed,
                aspectHistory = sealedHistory,
                liveMinutesByAspect = minutesByWeekAspect[week.id] ?: emptyMap()
            )
        }
        GrowthData.assemble(liveAspects, sources)
    }

    suspend fun buildRingsCsv(): String {
        val assembled = assemble()
        return GrowthExport.buildRingsCsv(assembled.aspects, assembled.weeks)
    }

    suspend fun buildRingsSvg(colorByHours: Boolean = true, glow: Boolean = true): String {
        val assembled = assemble()
        return GrowthExport.buildSvg(GrowthRings.computeScene(assembled.aspects, assembled.weeks, colorByHours, glow))
    }

    companion object {
        private const val EPOCH = "1970-01-01T00:00:00Z"
    }
}
