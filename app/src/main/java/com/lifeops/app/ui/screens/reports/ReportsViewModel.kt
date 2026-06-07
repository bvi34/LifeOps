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
                _uiState.update {
                    it.copy(
                        snapshots = snapshots,
                        aspects = aspects.associateBy { a -> a.id },
                        categories = categories.associateBy { c -> c.id },
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
            val total = snap.completedCount + snap.incompleteCount + snap.expiredCount +
                    snap.skippedCount + snap.carriedForwardCount
            WeeklyCompletionPoint(
                snap.createdAt.take(10),
                if (total > 0) snap.completedCount.toFloat() / total else 0f
            )
        }

        val totalByAspect = mutableMapOf<String, Int>()
        snapshots.forEach { snap ->
            snap.aspectBreakdown.forEach { (k, v) -> totalByAspect[k] = (totalByAspect[k] ?: 0) + v }
        }
        val grandTotal = totalByAspect.values.sum().takeIf { it > 0 } ?: 1
        val aspectShares = totalByAspect.map { (id, earned) ->
            val aspect = state.aspects[id]
            AspectResourceShare(aspect?.name ?: id, aspect?.color ?: "#6200EE", earned.toFloat() / grandTotal)
        }.sortedByDescending { it.share }

        // Category slip rate: slip = (incomplete + expired) tasks / (all non-skipped tasks)
        // categorySlipBreakdown tracks slip counts; categoryBreakdown tracks completed resource values.
        // We need both to compute a task count denominator. Use slip + completed proxy from breakdown keys.
        val catSlip = mutableMapOf<String, Pair<Int, Int>>() // categoryId → (slipCount, completedCount)
        snapshots.forEach { snap ->
            snap.categorySlipBreakdown.forEach { (catId, slip) ->
                val cur = catSlip[catId] ?: Pair(0, 0)
                catSlip[catId] = Pair(cur.first + slip, cur.second)
            }
            // categoryBreakdown maps categoryId → resource value; we use presence as "had completed tasks"
            snap.categoryBreakdown.keys.forEach { catId ->
                val cur = catSlip[catId] ?: Pair(0, 0)
                catSlip[catId] = Pair(cur.first, cur.second + 1)
            }
        }
        val categorySlipRates = catSlip.map { (id, counts) ->
            val totalTasks = counts.first + counts.second
            CategorySlipRate(
                state.categories[id]?.name ?: id,
                if (totalTasks > 0) counts.first.toFloat() / totalTasks else 0f
            )
        }.sortedByDescending { it.rate }

        // HD hit rate: only among tasks with hard deadlines
        val hdCompleted = snapshots.sumOf { it.hardDeadlineCompletedCount }
        val hdExpired = snapshots.sumOf { it.hardDeadlineExpiredCount }
        val hdTotal = hdCompleted + hdExpired
        val hdHitRate = if (hdTotal > 0) hdCompleted.toFloat() / hdTotal else 0f

        val totalTasks = snapshots.sumOf {
            it.completedCount + it.incompleteCount + it.expiredCount + it.carriedForwardCount + it.skippedCount
        }
        val cfRate = if (totalTasks > 0) snapshots.sumOf { it.carriedForwardCount }.toFloat() / totalTasks else 0f

        _uiState.update {
            it.copy(
                completionTrend = trend,
                aspectResourceShares = aspectShares,
                categorySlipRates = categorySlipRates,
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
