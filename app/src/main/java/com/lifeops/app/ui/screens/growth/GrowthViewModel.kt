package com.lifeops.app.ui.screens.growth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.repository.GrowthRepository
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
    val isLoading: Boolean = true
)

class GrowthViewModel(
    private val growthRepository: GrowthRepository
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
        val latest = assembled.weeks.lastOrNull()
        val totalHours = assembled.weeks.sumOf { it.hoursByAspect.values.sum() }
        _uiState.update {
            it.copy(
                scene = scene,
                legend = legendFor(latest?.hoursByAspect ?: emptyMap(), state.colorByHours),
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
}

class GrowthViewModelFactory(
    private val growthRepository: GrowthRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        GrowthViewModel(growthRepository) as T
}
