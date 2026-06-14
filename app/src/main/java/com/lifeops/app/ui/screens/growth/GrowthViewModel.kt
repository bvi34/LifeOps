package com.lifeops.app.ui.screens.growth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Week
import com.lifeops.app.data.model.WeekSnapshot
import com.lifeops.app.data.repository.AspectRepository
import com.lifeops.app.data.repository.TaskRepository
import com.lifeops.app.data.repository.TimeEntryRepository
import com.lifeops.app.data.repository.WeekRepository
import com.lifeops.app.util.GrowthColor
import com.lifeops.app.util.GrowthData
import com.lifeops.app.util.GrowthExport
import com.lifeops.app.util.GrowthRings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GrowthLegendRow(
    val aspectId: String,
    val name: String,
    val swatchHex: String,
    val hours: Double
)

data class GrowthUiState(
    val scene: GrowthRings.Scene? = null,
    val legend: List<GrowthLegendRow> = emptyList(),
    val latestWeekLabel: String? = null,
    val totalWeeks: Int = 0,
    val totalHours: Double = 0.0,
    val colorByHours: Boolean = true,
    val glowEnabled: Boolean = true,
    val selectedWeekId: String? = null,
    val selectedWeekDetail: List<GrowthLegendRow> = emptyList(),
    val isLoading: Boolean = true,
    val pendingExportCsv: String? = null,
    val pendingExportSvg: String? = null
)

class GrowthViewModel(
    private val weekRepository: WeekRepository,
    private val aspectRepository: AspectRepository,
    private val taskRepository: TaskRepository,
    private val timeEntryRepository: TimeEntryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GrowthUiState())
    val uiState: StateFlow<GrowthUiState> = _uiState.asStateFlow()

    // Cached so the colour/glow toggles can rebuild the scene without re-querying the DB.
    private var assembled: GrowthData.Assembled = GrowthData.Assembled(emptyList(), emptyList())

    init {
        viewModelScope.launch {
            combine(
                weekRepository.observeAllWeeks(),
                weekRepository.observeSnapshots(),
                aspectRepository.observeAllAspects()
            ) { weeks, snapshots, aspects ->
                Triple(weeks, snapshots, aspects.map { GrowthData.LiveAspect(it.id, it.name, it.color) })
            }.collectLatest { (weeks, snapshots, liveAspects) ->
                assembled = withContext(Dispatchers.Default) { assemble(weeks, snapshots, liveAspects) }
                rebuildScene()
            }
        }
    }

    private suspend fun assemble(
        weeks: List<Week>,
        snapshots: List<WeekSnapshot>,
        liveAspects: List<GrowthData.LiveAspect>
    ): GrowthData.Assembled {
        val snapshotByWeek = snapshots.associateBy { it.weekId }

        // Live fallback: weekId -> aspectId -> minutes, joined from tasks + time entries.
        val tasks = taskRepository.getAllTasks()
        val taskById = tasks.associateBy { it.id }
        val minutesByTaskWeekAspect = HashMap<String, HashMap<String, Int>>()
        timeEntryRepository.getAllSince(EPOCH).forEach { entry ->
            val task = taskById[entry.taskId] ?: return@forEach
            val aspectId = task.aspectId ?: return@forEach
            val perWeek = minutesByTaskWeekAspect.getOrPut(task.weekId) { HashMap() }
            perWeek[aspectId] = (perWeek[aspectId] ?: 0) + entry.durationMinutes
        }

        val sources = weeks.map { week ->
            val sealedHistory = snapshotByWeek[week.id]?.aspectHistory.orEmpty().mapValues {
                GrowthData.AspectHist(it.value.minutes, it.value.name, it.value.colorHex)
            }
            GrowthData.WeekSource(
                weekId = week.id,
                startDate = week.startDate,
                isClosed = week.isClosed,
                aspectHistory = sealedHistory,
                liveMinutesByAspect = minutesByTaskWeekAspect[week.id] ?: emptyMap()
            )
        }
        return GrowthData.assemble(liveAspects, sources)
    }

    private fun rebuildScene() {
        val state = _uiState.value
        val scene = GrowthRings.computeScene(
            aspects = assembled.aspects,
            weeks = assembled.weeks,
            colorByHours = state.colorByHours,
            glowEnabled = state.glowEnabled
        )
        val latest = assembled.weeks.lastOrNull()
        val legend = legendFor(latest?.hoursByAspect ?: emptyMap(), state.colorByHours)
        val totalHours = assembled.weeks.sumOf { it.hoursByAspect.values.sum() }
        _uiState.update {
            it.copy(
                scene = scene,
                legend = legend,
                latestWeekLabel = latest?.label,
                totalWeeks = assembled.weeks.size,
                totalHours = totalHours,
                isLoading = false,
                selectedWeekDetail = it.selectedWeekId?.let { id -> detailFor(id) } ?: emptyList()
            )
        }
    }

    private fun legendFor(hoursByAspect: Map<String, Double>, colorByHours: Boolean): List<GrowthLegendRow> =
        assembled.aspects.map { a ->
            val h = hoursByAspect[a.id] ?: 0.0
            val swatch = if (colorByHours && h > 0.0) GrowthColor.intensify(a.colorHex, h) else a.colorHex
            GrowthLegendRow(a.id, a.name, swatch, h)
        }

    private fun detailFor(weekId: String): List<GrowthLegendRow> {
        val week = assembled.weeks.firstOrNull { it.weekId == weekId } ?: return emptyList()
        return legendFor(week.hoursByAspect, _uiState.value.colorByHours).filter { it.hours > 0.0 }
    }

    fun setColorByHours(enabled: Boolean) {
        _uiState.update { it.copy(colorByHours = enabled) }
        rebuildScene()
    }

    fun setGlow(enabled: Boolean) {
        _uiState.update { it.copy(glowEnabled = enabled) }
        rebuildScene()
    }

    fun selectWeek(weekId: String?) {
        _uiState.update {
            it.copy(
                selectedWeekId = weekId,
                selectedWeekDetail = weekId?.let { id -> detailFor(id) } ?: emptyList()
            )
        }
    }

    fun prepareCsvExport() {
        _uiState.update { it.copy(pendingExportCsv = GrowthExport.buildRingsCsv(assembled.aspects, assembled.weeks)) }
    }

    fun prepareSvgExport() {
        val scene = _uiState.value.scene ?: GrowthRings.computeScene(assembled.aspects, assembled.weeks)
        _uiState.update { it.copy(pendingExportSvg = GrowthExport.buildSvg(scene)) }
    }

    fun consumeCsvExport(): String? = _uiState.value.pendingExportCsv
        ?.also { _uiState.update { s -> s.copy(pendingExportCsv = null) } }

    fun consumeSvgExport(): String? = _uiState.value.pendingExportSvg
        ?.also { _uiState.update { s -> s.copy(pendingExportSvg = null) } }

    fun cancelPendingExport() {
        _uiState.update { it.copy(pendingExportCsv = null, pendingExportSvg = null) }
    }

    companion object {
        private const val EPOCH = "1970-01-01T00:00:00Z"
    }
}

class GrowthViewModelFactory(
    private val weekRepository: WeekRepository,
    private val aspectRepository: AspectRepository,
    private val taskRepository: TaskRepository,
    private val timeEntryRepository: TimeEntryRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        GrowthViewModel(weekRepository, aspectRepository, taskRepository, timeEntryRepository) as T
}
