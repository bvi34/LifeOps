package com.lifeops.app.ui.screens.thisweek

import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ImportParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

enum class SortOrder(val label: String) {
    DEFAULT("Default"),
    DUE_DATE_ASC("Due ↑"),
    DUE_DATE_DESC("Due ↓"),
    PRIORITY_HIGH("Priority ↓"),
    PRIORITY_LOW("Priority ↑"),
    PLANNING("Planning")
}

data class ActiveTimer(
    val taskId: String,
    val startMillis: Long,
    val elapsedSeconds: Int = 0,
    val isPomodoro: Boolean = false
)

data class GroupedTasks(
    val aspect: Aspect?,
    val aspectColor: String,
    val categories: List<CategoryGroup>
)

data class CategoryGroup(
    val categoryId: String?,
    val category: Category?,
    val tasks: List<Task>,
    val dominantPriority: Priority?
)

data class ThisWeekUiState(
    val week: Week? = null,
    val groupedTasks: List<GroupedTasks> = emptyList(),
    val rawTasks: List<Task> = emptyList(),
    val aspects: Map<String, Aspect> = emptyMap(),
    val categories: Map<String, Category> = emptyMap(),
    val taskNotes: Map<String, List<TaskNote>> = emptyMap(),
    val taskTimeMinutes: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = true,
    val importDialogOpen: Boolean = false,
    val importPreview: ImportPreview? = null,
    val importError: String? = null,
    val importJson: String = "",
    val includeUnknownAsNotes: Boolean = false,
    val editingTask: Task? = null,
    val showCreateTaskDialog: Boolean = false,
    val activeTimer: ActiveTimer? = null,
    val detailTaskId: String? = null,
    val sortOrder: SortOrder = SortOrder.DEFAULT,
    val searchQuery: String = "",
    val weekProgress: WeekProgress = WeekProgress(0, 0, 0),
    val costResources: List<CostResource> = emptyList(),
    val taskCostEntries: Map<String, List<TaskCostEntry>> = emptyMap()
)

class ThisWeekViewModel(
    private val saveScope: CoroutineScope,
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository,
    private val aspectRepository: AspectRepository,
    private val importRepository: ImportRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val notificationRepository: NotificationRepository,
    private val costResourceRepository: CostResourceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThisWeekUiState())
    val uiState: StateFlow<ThisWeekUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            costResourceRepository.observeActiveResources().collectLatest { resources ->
                _uiState.update { it.copy(costResources = resources) }
            }
        }
        viewModelScope.launch {
            combine(
                weekRepository.observeCurrentWeek(),
                // Include archived aspects so tasks are grouped under their original aspect name
                aspectRepository.observeAllAspects(),
                aspectRepository.observeCategories()
            ) { week, aspects, categories ->
                Triple(week, aspects, categories)
            }.collectLatest { (week, aspects, categories) ->
                val aspectMap = aspects.associateBy { it.id }
                val categoryMap = categories.associateBy { it.id }
                if (week != null) {
                    combine(
                        taskRepository.observeTasksForWeek(week.id),
                        taskNoteRepository.observeByWeek(week.id),
                        timeEntryRepository.observeByWeek(week.id),
                        costResourceRepository.observeEntriesByWeek(week.id)
                    ) { tasks, notes, timeEntries, costEntries ->
                        Pair(Pair(tasks, notes), Pair(timeEntries, costEntries))
                    }.collectLatest { (tasksNotes, timeEntriesCostEntries) ->
                        val (tasks, notes) = tasksNotes
                        val (timeEntries, costEntries) = timeEntriesCostEntries
                        val notesByTask = notes.groupBy { it.taskId }
                        val timeByTask = timeEntries
                            .groupBy { it.taskId }
                            .mapValues { (_, entries) -> entries.sumOf { it.durationMinutes } }
                        val costByTask = costEntries.groupBy { it.taskId }
                        _uiState.update { state ->
                            val grouped = groupAndFilterTasks(tasks, aspectMap, categoryMap, state.sortOrder, state.searchQuery)
                            state.copy(
                                week = week,
                                rawTasks = tasks,
                                groupedTasks = grouped,
                                aspects = aspectMap,
                                categories = categoryMap,
                                taskNotes = notesByTask,
                                taskTimeMinutes = timeByTask,
                                taskCostEntries = costByTask,
                                isLoading = false,
                                weekProgress = computeProgress(tasks, timeByTask)
                            )
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            week = null,
                            rawTasks = emptyList(),
                            groupedTasks = emptyList(),
                            aspects = aspectMap,
                            categories = categoryMap,
                            taskNotes = emptyMap(),
                            taskTimeMinutes = emptyMap(),
                            isLoading = false,
                            weekProgress = WeekProgress(0, 0, 0)
                        )
                    }
                }
            }
        }
    }

    private fun computeProgress(tasks: List<Task>, timeByTask: Map<String, Int>): WeekProgress {
        val relevant = tasks.filter { it.status != TaskStatus.CARRIED_FORWARD }
        val completed = relevant.count { it.status == TaskStatus.COMPLETED }
        val total = relevant.size
        val totalTime = timeByTask.values.sum()
        return WeekProgress(completed, total, totalTime)
    }

    fun setSortOrder(order: SortOrder) {
        _uiState.update { state ->
            state.copy(
                sortOrder = order,
                groupedTasks = groupAndFilterTasks(state.rawTasks, state.aspects, state.categories, order, state.searchQuery)
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                groupedTasks = groupAndFilterTasks(state.rawTasks, state.aspects, state.categories, state.sortOrder, query)
            )
        }
    }

    private fun sortTasks(tasks: List<Task>, order: SortOrder): List<Task> = when (order) {
        SortOrder.DEFAULT -> tasks
        SortOrder.DUE_DATE_ASC -> tasks.sortedWith(
            compareBy<Task> { it.dueDate == null }.thenBy { it.dueDate }
        )
        SortOrder.DUE_DATE_DESC -> tasks.sortedWith(
            compareBy<Task> { it.dueDate == null }.thenByDescending { it.dueDate }
        )
        SortOrder.PRIORITY_HIGH -> tasks.sortedByDescending { it.priority.baseValue }
        SortOrder.PRIORITY_LOW -> tasks.sortedBy { it.priority.baseValue }
        SortOrder.PLANNING -> tasks.sortedBy { it.sortOrder }
    }

    private fun groupAndFilterTasks(
        tasks: List<Task>,
        aspects: Map<String, Aspect>,
        categories: Map<String, Category>,
        sortOrder: SortOrder,
        searchQuery: String
    ): List<GroupedTasks> {
        val filtered = if (searchQuery.isBlank()) tasks
        else tasks.filter { it.title.contains(searchQuery, ignoreCase = true) }

        val byAspect = filtered.groupBy { it.aspectId }
        return byAspect.map { (aspectId, aspectTasks) ->
            val aspect = aspectId?.let { aspects[it] }
            val byCategory = aspectTasks.groupBy { it.categoryId }
            val categoryGroups = byCategory.map { (catId, catTasks) ->
                val sorted = sortTasks(catTasks, sortOrder)
                val dominantPriority = catTasks
                    .filter { it.status == TaskStatus.PENDING }
                    .maxByOrNull { it.priority.baseValue }
                    ?.priority
                CategoryGroup(catId, catId?.let { categories[it] }, sorted, dominantPriority)
            }
            GroupedTasks(aspect, aspect?.color ?: "#6200EE", categoryGroups)
        }
    }

    fun onCompleteTask(task: Task) {
        viewModelScope.launch { taskRepository.completeTask(task) }
    }

    fun onUnCompleteTask(taskId: String) {
        viewModelScope.launch { taskRepository.unCompleteTask(taskId) }
    }

    fun onUnSkipTask(taskId: String) {
        viewModelScope.launch { taskRepository.unSkipTask(taskId) }
    }

    fun onSkipTask(taskId: String) {
        viewModelScope.launch { taskRepository.skipTask(taskId) }
    }

    fun onCarryForward(task: Task) {
        viewModelScope.launch {
            val week = weekRepository.getOrCreateCurrentWeek()
            taskRepository.carryForward(task, week.id)
        }
    }

    fun movePlanningTask(taskId: String, direction: Int) {
        val state = _uiState.value
        // Use stable secondary sort so tasks with sortOrder=0 have consistent positions
        val allFlat = state.rawTasks
            .sortedWith(compareBy<Task> { it.sortOrder }.thenBy { it.createdAt })
        val idx = allFlat.indexOfFirst { it.id == taskId }
        if (idx < 0) return
        val targetIdx = (idx + direction).coerceIn(0, allFlat.size - 1)
        if (targetIdx == idx) return

        // Rebuild the full ordering to avoid stale 0-valued sortOrders causing mis-swaps
        val reordered = allFlat.toMutableList()
        val moved = reordered.removeAt(idx)
        reordered.add(targetIdx, moved)

        viewModelScope.launch {
            for ((newIdx, task) in reordered.withIndex()) {
                if (task.sortOrder != newIdx) {
                    taskRepository.updateTaskSortOrder(task.id, newIdx)
                }
            }
        }
    }

    private var weekCloseInFlight = false

    fun onCloseWeek() {
        if (weekCloseInFlight) return
        viewModelScope.launch {
            weekCloseInFlight = true
            try {
                val week = _uiState.value.week ?: return@launch
                stopTimer(saveEntry = true)
                taskRepository.closeWeek(week.id)
                weekRepository.getOrCreateCurrentWeek()
            } finally {
                weekCloseInFlight = false
            }
        }
    }

    // Timer
    fun startTimer(taskId: String, isPomodoro: Boolean = false) {
        stopTimer(saveEntry = true)
        val startMillis = System.currentTimeMillis()
        _uiState.update { it.copy(activeTimer = ActiveTimer(taskId, startMillis, isPomodoro = isPomodoro)) }
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val elapsed = ((System.currentTimeMillis() - startMillis) / 1000).toInt()
                _uiState.update { state ->
                    state.copy(activeTimer = state.activeTimer?.copy(elapsedSeconds = elapsed))
                }
                // Auto-stop Pomodoro at 25 minutes
                if (isPomodoro && elapsed >= 1500) {
                    stopTimer(saveEntry = true)
                    break
                }
            }
        }
    }

    fun stopTimer(saveEntry: Boolean = true) {
        val timer = _uiState.value.activeTimer ?: return
        timerJob?.cancel()
        timerJob = null
        // Always log actual elapsed time. For a completed Pomodoro elapsed ≈ 1500s → 25min.
        val minutes = timer.elapsedSeconds / 60
        if (saveEntry && minutes > 0) {
            viewModelScope.launch { timeEntryRepository.logTime(timer.taskId, minutes) }
        }
        _uiState.update { it.copy(activeTimer = null) }
    }

    // Detail sheet
    fun openDetail(taskId: String) = _uiState.update { it.copy(detailTaskId = taskId) }
    fun closeDetail() = _uiState.update { it.copy(detailTaskId = null) }

    // Import
    fun openImportDialog() = _uiState.update { it.copy(importDialogOpen = true, importError = null) }

    fun closeImportDialog() = _uiState.update {
        it.copy(importDialogOpen = false, importPreview = null, importJson = "", importError = null, includeUnknownAsNotes = false)
    }

    fun onImportJsonChange(json: String) = _uiState.update { it.copy(importJson = json, importPreview = null, importError = null) }

    fun setIncludeUnknownAsNotes(include: Boolean) = _uiState.update { it.copy(includeUnknownAsNotes = include) }

    fun previewImport() {
        val json = _uiState.value.importJson
        viewModelScope.launch {
            importRepository.previewImport(json)
                .onSuccess { preview ->
                    _uiState.update { it.copy(importPreview = preview, importError = null, includeUnknownAsNotes = false) }
                }
                .onFailure { e -> _uiState.update { it.copy(importError = e.message, importPreview = null) } }
        }
    }

    fun commitImport() {
        val json = _uiState.value.importJson
        val includeUnknown = _uiState.value.includeUnknownAsNotes
        viewModelScope.launch {
            importRepository.commitImport(json, includeUnknown)
                .onSuccess { closeImportDialog() }
                .onFailure { e -> _uiState.update { it.copy(importError = e.message) } }
        }
    }

    fun showCreateTaskDialog() = _uiState.update { it.copy(showCreateTaskDialog = true) }
    fun hideCreateTaskDialog() = _uiState.update { it.copy(showCreateTaskDialog = false) }

    fun createTask(
        title: String,
        note: String?,
        aspectId: String?,
        categoryId: String?,
        priority: Priority,
        dueDate: String?,
        hardDeadline: Boolean,
        isRecurring: Boolean = false,
        estimatedMinutes: Int? = null
    ) {
        viewModelScope.launch {
            val week = weekRepository.getOrCreateCurrentWeek()
            val resourceValue = ImportParser.computeResourceValue(
                priority.label, hardDeadline, estimatedMinutes, isManuallyAdded = true
            )
            val task = Task(
                id = UUID.randomUUID().toString(),
                weekId = week.id,
                title = title,
                aspectId = aspectId,
                categoryId = categoryId,
                priority = priority,
                dueDate = dueDate,
                hardDeadline = hardDeadline,
                status = TaskStatus.PENDING,
                resourceValue = resourceValue,
                createdAt = DateUtil.now(),
                isRecurring = isRecurring,
                estimatedMinutes = estimatedMinutes,
                isManuallyAdded = true
            )
            taskRepository.upsertTask(task)
            note?.let { taskNoteRepository.addNote(task.id, it) }
            notificationRepository.scheduleForTask(task)
            _uiState.update { it.copy(showCreateTaskDialog = false) }
        }
    }

    fun onAddNote(taskId: String, content: String) {
        viewModelScope.launch { taskNoteRepository.addNote(taskId, content) }
    }

    fun onLogTime(taskId: String, minutes: Int, note: String?) {
        viewModelScope.launch { timeEntryRepository.logTime(taskId, minutes, note) }
    }

    fun onLogCost(taskId: String, resourceId: String, amount: Int, note: String?) {
        viewModelScope.launch { costResourceRepository.logCost(taskId, resourceId, amount, note) }
    }

    fun onDeleteCostEntry(id: String) {
        viewModelScope.launch { costResourceRepository.deleteCostEntry(id) }
    }

    override fun onCleared() {
        super.onCleared()
        val timer = _uiState.value.activeTimer ?: return
        timerJob?.cancel()
        timerJob = null
        val minutes = timer.elapsedSeconds / 60
        if (minutes > 0) {
            saveScope.launch { timeEntryRepository.logTime(timer.taskId, minutes) }
        }
    }

    fun startEditTask(task: Task) = _uiState.update { it.copy(editingTask = task) }
    fun cancelEditTask() = _uiState.update { it.copy(editingTask = null) }

    fun saveTaskEdit(
        title: String,
        priority: Priority,
        dueDate: String?,
        hardDeadline: Boolean,
        isRecurring: Boolean = false,
        estimatedMinutes: Int? = null,
        aspectId: String? = null,
        categoryId: String? = null
    ) {
        val task = _uiState.value.editingTask ?: return
        viewModelScope.launch {
            val newResourceValue = ImportParser.computeResourceValue(
                priority.label, hardDeadline, estimatedMinutes, task.isManuallyAdded
            )
            taskRepository.updateTask(
                task.copy(
                    title = title,
                    priority = priority,
                    dueDate = dueDate,
                    hardDeadline = hardDeadline,
                    resourceValue = newResourceValue,
                    isRecurring = isRecurring,
                    estimatedMinutes = estimatedMinutes,
                    aspectId = aspectId,
                    categoryId = categoryId
                )
            )
            _uiState.update { it.copy(editingTask = null) }
        }
    }
}

class ThisWeekViewModelFactory(
    private val saveScope: CoroutineScope,
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository,
    private val aspectRepository: AspectRepository,
    private val importRepository: ImportRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val notificationRepository: NotificationRepository,
    private val costResourceRepository: CostResourceRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ThisWeekViewModel(
            saveScope, weekRepository, taskRepository, aspectRepository, importRepository,
            taskNoteRepository, timeEntryRepository, notificationRepository, costResourceRepository
        ) as T
}
