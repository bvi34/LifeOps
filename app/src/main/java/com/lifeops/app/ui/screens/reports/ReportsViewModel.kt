package com.lifeops.app.ui.screens.reports

import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ReportRange(val label: String, val days: Int) {
    DAYS_30("30 Days", 30),
    DAYS_90("90 Days", 90),
    LIFETIME("Lifetime", Int.MAX_VALUE)
}

data class WeeklyCompletionPoint(val weekLabel: String, val rate: Float)

data class AspectResourceShare(val aspectName: String, val color: String, val share: Float)

data class CategorySlipRate(val categoryName: String, val rate: Float)

data class ReportsUiState(
    val range: ReportRange = ReportRange.DAYS_30,
    val snapshots: List<WeekSnapshot> = emptyList(),
    val aspects: Map<String, Aspect> = emptyMap(),
    val categories: Map<String, Category> = emptyMap(),
    val completionTrend: List<WeeklyCompletionPoint> = emptyList(),
    val aspectResourceShares: List<AspectResourceShare> = emptyList(),
    val categorySlipRates: List<CategorySlipRate> = emptyList(),
    val hardDeadlineHitRate: Float = 0f,
    val carryForwardRate: Float = 0f,
    val isLoading: Boolean = true
)

class ReportsViewModel(
    private val weekRepository: WeekRepository,
    private val aspectRepository: AspectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                weekRepository.observeSnapshots(),
                aspectRepository.observeAllAspects(),
                aspectRepository.observeCategories()
            ) { snapshots, aspects, categories ->
                Triple(snapshots, aspects, categories)
            }.collectLatest { (snapshots, aspects, categories) ->
                val aspectMap = aspects.associateBy { it.id }
                val categoryMap = categories.associateBy { it.id }
                _uiState.update {
                    it.copy(
                        snapshots = snapshots,
                        aspects = aspectMap,
                        categories = categoryMap,
                        isLoading = false
                    )
                }
                computeStats()
            }
        }
    }

    fun setRange(range: ReportRange) {
        _uiState.update { it.copy(range = range) }
        computeStats()
    }

    private fun computeStats() {
        val state = _uiState.value
        val snapshots = filteredSnapshots(state)
        val trend = snapshots.map { snap ->
            val total = snap.completedCount + snap.incompleteCount + snap.expiredCount + snap.skippedCount + snap.carriedForwardCount
            WeeklyCompletionPoint(snap.createdAt.take(10), if (total > 0) snap.completedCount.toFloat() / total else 0f)
        }
        val totalByAspect = mutableMapOf<String, Int>()
        snapshots.forEach { snap ->
            snap.aspectBreakdown.forEach { (k, v) -> totalByAspect[k] = (totalByAspect[k] ?: 0) + v }
        }
        val grandTotal = totalByAspect.values.sum().takeIf { it > 0 } ?: 1
        val aspectShares = totalByAspect.map { (id, earned) ->
            val aspect = state.aspects[id]
            AspectResourceShare(aspect?.name ?: id, aspect?.color ?: "#6200EE", earned.toFloat() / grandTotal)
        }
        val totalByCategory = mutableMapOf<String, Pair<Int, Int>>()
        snapshots.forEach { snap ->
            val total = snap.completedCount + snap.incompleteCount + snap.expiredCount
            snap.categoryBreakdown.forEach { (k, _) ->
                val cur = totalByCategory[k] ?: Pair(0, 0)
                totalByCategory[k] = Pair(cur.first + snap.incompleteCount + snap.expiredCount, cur.second + total)
            }
        }
        val catSlip = totalByCategory.map { (id, pair) ->
            val cat = state.categories[id]
            CategorySlipRate(cat?.name ?: id, if (pair.second > 0) pair.first.toFloat() / pair.second else 0f)
        }
        val totalHard = snapshots.sumOf { it.expiredCount + it.completedCount }
        val hdHitRate = if (totalHard > 0) snapshots.sumOf { it.completedCount }.toFloat() / totalHard else 0f
        val totalTasks = snapshots.sumOf { it.completedCount + it.incompleteCount + it.expiredCount + it.carriedForwardCount + it.skippedCount }
        val cfRate = if (totalTasks > 0) snapshots.sumOf { it.carriedForwardCount }.toFloat() / totalTasks else 0f

        _uiState.update {
            it.copy(
                completionTrend = trend,
                aspectResourceShares = aspectShares,
                categorySlipRates = catSlip,
                hardDeadlineHitRate = hdHitRate,
                carryForwardRate = cfRate
            )
        }
    }

    private fun filteredSnapshots(state: ReportsUiState): List<WeekSnapshot> {
        if (state.range == ReportRange.LIFETIME) return state.snapshots
        val cutoff = DateUtil.sinceDate(state.range.days)
        return state.snapshots.filter { it.createdAt >= cutoff }
    }
}

class ReportsViewModelFactory(
    private val weekRepository: WeekRepository,
    private val aspectRepository: AspectRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ReportsViewModel(weekRepository, aspectRepository) as T
}
