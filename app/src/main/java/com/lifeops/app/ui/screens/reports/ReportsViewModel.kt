package com.lifeops.app.ui.screens.reports

import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReportRange(val label: String, val days: Int) {
    DAYS_30("30 Days", 30),
    DAYS_90("90 Days", 90),
    LIFETIME("Lifetime", Int.MAX_VALUE)
}

data class WeeklyCompletionPoint(val weekLabel: String, val rate: Float)

data class AspectResourceShare(val aspectName: String, val color: String, val share: Float)

data class CategorySlipRate(val categoryName: String, val rate: Float)

data class AspectTimeRow(val aspectName: String, val color: String, val totalMinutes: Int)

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
    val timeByAspect: List<AspectTimeRow> = emptyList(),
    val totalTimeMinutes: Int = 0,
    val carryHistory: List<CarryForwardEntry> = emptyList(),
    val costUsage: List<CostUsageRow> = emptyList(),
    val isLoading: Boolean = true,
    val projectStats: List<ProjectStats> = emptyList(),
    val scoringTrend: List<ScoringPoint> = emptyList(),
    val priorityBreakdown: List<PriorityCompletionRow> = emptyList()
)

class ReportsViewModel(
    private val weekRepository: WeekRepository,
    private val aspectRepository: AspectRepository,
    private val taskRepository: TaskRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val costResourceRepository: CostResourceRepository,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private var weeksById: Map<String, Week> = emptyMap()

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                weekRepository.observeSnapshots(),
                weekRepository.observeAllWeeks(),
                aspectRepository.observeAllAspects(),
                aspectRepository.observeCategories()
            ) { snapshots, weeks, aspects, categories ->
                Pair(Pair(snapshots, weeks), Pair(aspects, categories))
            }.collectLatest { (snapshotsWeeks, aspectsCategories) ->
                val (snapshots, weeks) = snapshotsWeeks
                val (aspects, categories) = aspectsCategories
                weeksById = weeks.associateBy { it.id }
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
        viewModelScope.launch { computeStats() }
    }

    // Runs the snapshot/time/cost aggregation off the main thread; the suspend DB reads and the
    // O(n) grouping loops below would otherwise block the UI on every range change.
    private suspend fun computeStats() = withContext(Dispatchers.Default) {
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

        val catSlip = mutableMapOf<String, Int>()
        val catTotal = mutableMapOf<String, Int>()
        snapshots.forEach { snap ->
            snap.categorySlipBreakdown.forEach { (catId, slip) ->
                catSlip[catId] = (catSlip[catId] ?: 0) + slip
            }
            snap.categoryTotalBreakdown.forEach { (catId, total) ->
                catTotal[catId] = (catTotal[catId] ?: 0) + total
            }
        }
        val categorySlipRates = catTotal.map { (id, total) ->
            CategorySlipRate(
                state.categories[id]?.name ?: id,
                if (total > 0) (catSlip[id] ?: 0).toFloat() / total else 0f
            )
        }.sortedByDescending { it.rate }

        val hdCompleted = snapshots.sumOf { it.hardDeadlineCompletedCount }
        val hdExpired = snapshots.sumOf { it.hardDeadlineExpiredCount }
        val hdTotal = hdCompleted + hdExpired
        val hdHitRate = if (hdTotal > 0) hdCompleted.toFloat() / hdTotal else 0f

        val totalTasks = snapshots.sumOf {
            it.completedCount + it.incompleteCount + it.expiredCount + it.carriedForwardCount + it.skippedCount
        }
        val cfRate = if (totalTasks > 0) snapshots.sumOf { it.carriedForwardCount }.toFloat() / totalTasks else 0f

        // Time by aspect: join time entries with tasks
        val cutoff = if (state.range == ReportRange.LIFETIME) "1970-01-01T00:00:00Z"
                     else DateUtil.sinceDate(state.range.days)
        val timeEntries = timeEntryRepository.getAllSince(cutoff)
        val tasks = taskRepository.getAllSince(cutoff)
        val taskAspectMap = tasks.associate { it.id to it.aspectId }
        val minutesByAspect = mutableMapOf<String, Int>()
        timeEntries.forEach { entry ->
            val aspectId = taskAspectMap[entry.taskId] ?: return@forEach
            minutesByAspect[aspectId] = (minutesByAspect[aspectId] ?: 0) + entry.durationMinutes
        }
        val timeRows = minutesByAspect.map { (id, minutes) ->
            val aspect = state.aspects[id]
            AspectTimeRow(aspect?.name ?: id, aspect?.color ?: "#6200EE", minutes)
        }.sortedByDescending { it.totalMinutes }

        // Carry history
        val carryTasks = taskRepository.getTasksWithCarryHistory()
        val carryEntries = carryTasks.map { task ->
            val week = weeksById[task.weekId]
            val weekLabel = week?.startDate?.take(10) ?: task.weekId.take(10)
            CarryForwardEntry(
                taskTitle = task.title,
                carriedCount = task.carriedCount,
                finalStatus = task.status,
                weekLabel = weekLabel
            )
        }.sortedByDescending { it.carriedCount }

        // Cost usage
        val allCostEntries = costResourceRepository.getAllEntries()
        val allCostResources = costResourceRepository.getAllSync().associateBy { it.id }
        val filteredCostEntries = if (state.range == ReportRange.LIFETIME) allCostEntries
            else allCostEntries.filter { it.recordedAt >= cutoff }
        val amountByResource = mutableMapOf<String, Int>()
        filteredCostEntries.forEach { entry ->
            amountByResource[entry.resourceId] = (amountByResource[entry.resourceId] ?: 0) + entry.amount
        }
        val costUsageRows = amountByResource.mapNotNull { (resourceId, total) ->
            val resource = allCostResources[resourceId] ?: return@mapNotNull null
            CostUsageRow(resource.name, resource.resetCycle, resource.capacity, total)
        }.sortedByDescending { it.totalAmount }

        // Scoring trend (resources earned per week from snapshots)
        val scoringTrend = snapshots.map { snap ->
            ScoringPoint(snap.createdAt.take(10), snap.totalResourcesEarned)
        }

        // Priority breakdown
        val priorityStats = Priority.entries.mapNotNull { p ->
            val pTasks = tasks.filter { it.priority == p }
            val completed = pTasks.count { it.status == TaskStatus.COMPLETED }
            val total = pTasks.count {
                it.status in listOf(TaskStatus.COMPLETED, TaskStatus.INCOMPLETE, TaskStatus.EXPIRED)
            }
            if (total == 0) null else PriorityCompletionRow(p, completed, total)
        }

        // Project stats
        val allProjects = projectRepository.getAll()
        val taskProjectMap = tasks.associate { it.id to it.projectId }
        val minutesByProject = mutableMapOf<String, Int>()
        timeEntries.forEach { entry ->
            val pid = taskProjectMap[entry.taskId] ?: return@forEach
            minutesByProject[pid] = (minutesByProject[pid] ?: 0) + entry.durationMinutes
        }
        val tasksByProject = tasks.groupBy { it.projectId }
        val projectStatsList = allProjects
            .filter { proj -> tasksByProject.containsKey(proj.id) || proj.status == ProjectStatus.ACTIVE }
            .map { proj ->
                val projTasks = tasksByProject[proj.id] ?: emptyList()
                ProjectStats(
                    proj,
                    projTasks.size,
                    projTasks.count { it.status == TaskStatus.COMPLETED },
                    minutesByProject[proj.id] ?: 0
                )
            }
            .sortedWith(compareBy({ it.project.status.value }, { -(it.completedCount) }))

        _uiState.update {
            it.copy(
                completionTrend = trend,
                aspectResourceShares = aspectShares,
                categorySlipRates = categorySlipRates,
                hardDeadlineHitRate = hdHitRate,
                carryForwardRate = cfRate,
                timeByAspect = timeRows,
                totalTimeMinutes = minutesByAspect.values.sum(),
                carryHistory = carryEntries,
                costUsage = costUsageRows,
                projectStats = projectStatsList,
                scoringTrend = scoringTrend,
                priorityBreakdown = priorityStats
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
    private val aspectRepository: AspectRepository,
    private val taskRepository: TaskRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val costResourceRepository: CostResourceRepository,
    private val projectRepository: ProjectRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ReportsViewModel(weekRepository, aspectRepository, taskRepository, timeEntryRepository, costResourceRepository, projectRepository) as T
}
