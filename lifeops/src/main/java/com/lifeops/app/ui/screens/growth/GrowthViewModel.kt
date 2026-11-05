package com.lifeops.app.ui.screens.growth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.connection.service.WeekService
import com.lifeops.app.data.repository.GrowthRepository
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.WeekReset
import java.time.LocalDate
import com.lifeops.app.util.GrowthBars
import com.lifeops.app.util.GrowthColor
import com.lifeops.app.util.GrowthData
import com.lifeops.app.util.GrowthRings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GrowthLegendRow(
    val aspectId: String,
    val name: String,
    val swatchHex: String,
    val hours: Double
)

enum class GrowthView { RINGS, BARS }

data class GrowthUiState(
    val scene: GrowthRings.Scene? = null,
    val chart: GrowthBars.Chart? = null,
    val view: GrowthView = GrowthView.RINGS,
    val legend: List<GrowthLegendRow> = emptyList(),
    val latestWeekLabel: String? = null,
    val totalWeeks: Int = 0,
    val totalHours: Double = 0.0,
    val colorByHours: Boolean = true,
    val glowEnabled: Boolean = true,
    val selectedWeekId: String? = null,
    val selectedWeekDetail: List<GrowthLegendRow> = emptyList(),
    /** The selected week was written off as skipped ("Scar past weeks"). */
    val selectedWeekSkipped: Boolean = false,
    /** The selected skipped week's reason (blank when none was given). */
    val selectedWeekNote: String? = null,
    /** How many weeks in the record are skipped scars. */
    val skippedWeeks: Int = 0,
    /**
     * Picking weeks to scar on the bars: tap a bar and it plus every week after it, up to last
     * week, is selected — "the last X weeks". Null when not picking.
     */
    val scarPick: ScarPick? = null,
    val isLoading: Boolean = true
)

/** The "scar the last X weeks" selection on the Growth Record. */
data class ScarPick(
    val plan: WeekReset.Plan,
    /** How many of the weeks before this one are selected (0 = none yet). */
    val count: Int,
    val inFlight: Boolean = false
) {
    val range: IntRange get() = plan.range(count)

    /** Whether the bar starting [startDate] is in the selection. */
    fun includes(startDate: String): Boolean =
        runCatching { DateUtil.weekIndexFor(LocalDate.parse(startDate)) in range }.getOrDefault(false)
}

class GrowthViewModel(
    private val growthRepository: GrowthRepository,
    private val weekService: WeekService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(GrowthUiState())
    val uiState: StateFlow<GrowthUiState> = _uiState.asStateFlow()

    // Cached so the colour/glow toggles can rebuild the scene without re-querying the DB.
    private var assembled: GrowthData.Assembled = GrowthData.Assembled(emptyList(), emptyList())

    init {
        viewModelScope.launch {
            growthRepository.observeChanges().collectLatest {
                assembled = growthRepository.assemble()
                rebuildScene()
            }
        }
    }

    private fun rebuildScene() {
        val state = _uiState.value
        val scene = GrowthRings.computeScene(
            aspects = assembled.aspects,
            weeks = assembled.weeks,
            colorByHours = state.colorByHours,
            glowEnabled = state.glowEnabled
        )
        val chart = GrowthBars.computeChart(
            aspects = assembled.aspects,
            weeks = assembled.weeks,
            colorByHours = state.colorByHours
        )
        val latest = assembled.weeks.lastOrNull()
        val totalHours = assembled.weeks.sumOf { it.hoursByAspect.values.sum() }
        _uiState.update {
            it.copy(
                scene = scene,
                chart = chart,
                legend = legendFor(latest?.hoursByAspect ?: emptyMap(), state.colorByHours),
                latestWeekLabel = latest?.label,
                totalWeeks = assembled.weeks.size,
                totalHours = totalHours,
                isLoading = false,
                selectedWeekDetail = it.selectedWeekId?.let { id -> detailFor(id) } ?: emptyList(),
                selectedWeekSkipped = it.selectedWeekId?.let { id -> weekFor(id)?.skipped } ?: false,
                selectedWeekNote = it.selectedWeekId?.let { id -> weekFor(id)?.note },
                skippedWeeks = assembled.weeks.count { w -> w.skipped }
            )
        }
    }

    private fun legendFor(hoursByAspect: Map<String, Double>, colorByHours: Boolean): List<GrowthLegendRow> =
        assembled.aspects.map { a ->
            val h = hoursByAspect[a.id] ?: 0.0
            val swatch = if (colorByHours && h > 0.0) GrowthColor.intensify(a.colorHex, h) else a.colorHex
            GrowthLegendRow(a.id, a.name, swatch, h)
        }

    private fun weekFor(weekId: String): GrowthRings.WeekInput? =
        assembled.weeks.firstOrNull { it.weekId == weekId }

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

    fun setView(view: GrowthView) {
        // Picking weeks to scar happens on the bars; leaving them ends the pick.
        _uiState.update { it.copy(view = view, scarPick = if (view == GrowthView.BARS) it.scarPick else null) }
    }

    /** Start picking weeks to scar; preselects the quiet run right before this week. */
    fun startScarPick() {
        val service = weekService ?: return
        viewModelScope.launch {
            val plan = runCatching { service.planReset() }.getOrNull() ?: return@launch
            _uiState.update {
                it.copy(view = GrowthView.BARS, scarPick = ScarPick(plan, plan.suggestedCount))
            }
        }
    }

    fun cancelScarPick() = _uiState.update { it.copy(scarPick = null) }

    /**
     * Tap a bar while picking: select it and every week after it up to last week. Tapping the
     * oldest selected bar again clears the selection; this week's bar can't be scarred.
     */
    fun pickScarFrom(startDate: String) {
        val pick = _uiState.value.scarPick ?: return
        val index = runCatching { DateUtil.weekIndexFor(LocalDate.parse(startDate)) }.getOrNull() ?: return
        val count = (pick.plan.currentIndex - index).coerceIn(0, pick.plan.availableCount)
        if (count == 0) return
        val next = if (count == pick.count) 0 else count
        _uiState.update { it.copy(scarPick = pick.copy(count = next)) }
    }

    fun confirmScar(count: Int, reason: String, clearRecurring: Boolean) {
        val service = weekService ?: return
        val pick = _uiState.value.scarPick ?: return
        if (pick.inFlight) return
        _uiState.update { it.copy(scarPick = pick.copy(inFlight = true)) }
        viewModelScope.launch {
            try {
                service.scarPastWeeks(count, reason.trim(), clearRecurring)
                _uiState.update { it.copy(scarPick = null) }
            } catch (e: Exception) {
                _uiState.update { s -> s.copy(scarPick = s.scarPick?.copy(inFlight = false)) }
            }
        }
    }

    fun selectWeek(weekId: String?) {
        _uiState.update {
            it.copy(
                selectedWeekId = weekId,
                selectedWeekDetail = weekId?.let { id -> detailFor(id) } ?: emptyList(),
                selectedWeekSkipped = weekId?.let { id -> weekFor(id)?.skipped } ?: false,
                selectedWeekNote = weekId?.let { id -> weekFor(id)?.note }
            )
        }
    }
}

class GrowthViewModelFactory(
    private val growthRepository: GrowthRepository,
    private val weekService: WeekService? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        GrowthViewModel(growthRepository, weekService) as T
}
