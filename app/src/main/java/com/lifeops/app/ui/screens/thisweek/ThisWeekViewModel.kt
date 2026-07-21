package com.lifeops.app.ui.screens.thisweek

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import com.lifeops.app.util.toSlug
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ImportParser
import com.lifeops.app.util.SevereWeatherIntel
import com.lifeops.app.util.TaskWeatherFit
import com.lifeops.app.util.TaskWeatherFitCalculator
import com.lifeops.app.util.WeatherAdvisory
import com.lifeops.app.widget.LifeOpsWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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

data class UndoEvent(val message: String, val taskId: String, val action: UndoEventAction)
enum class UndoEventAction { UNSKIP, UN_CARRY_FORWARD, UN_UNSUCCESSFUL }

data class ThisWeekUiState(
    val week: Week? = null,
    val groupedTasks: List<GroupedTasks> = emptyList(),
    val rawTasks: List<Task> = emptyList(),
    val aspects: Map<String, Aspect> = emptyMap(),
    val categories: Map<String, Category> = emptyMap(),
    val taskNotes: Map<String, List<TaskNote>> = emptyMap(),
    val ancestorNotesByTask: Map<String, List<TaskNote>> = emptyMap(),
    val taskTimeMinutes: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = true,
    val importDialogOpen: Boolean = false,
    val importPreview: ImportPreview? = null,
    val importError: String? = null,
    val importJson: String = "",
    val includeUnknownAsNotes: Boolean = false,
    val editingTask: Task? = null,
    val showCreateTaskDialog: Boolean = false,
    val detailTaskId: String? = null,
    val sortOrder: SortOrder = SortOrder.DEFAULT,
    val searchQuery: String = "",
    val weekProgress: WeekProgress = WeekProgress(0, 0, 0),
    val costResources: List<CostResource> = emptyList(),
    val taskCostEntries: Map<String, List<TaskCostEntry>> = emptyMap(),
    val projects: List<Project> = emptyList(),
    val showOverdueOnly: Boolean = false,
    val showTemplatePickerDialog: Boolean = false,
    val availableTemplates: List<TemplateWithTasks> = emptyList(),
    val runbooks: List<RunbookWithSteps> = emptyList(),
    val detailSubtasks: List<Subtask> = emptyList(),
    val counters: List<Counter> = emptyList(),
    /** Daytime periods of the tracked location's cached forecast — powers the weekly forecast
     *  strip above the task list. Empty when no weather location/report exists. */
    val weeklyForecast: List<ForecastPeriod> = emptyList(),
    /** Name of the location the forecast is for, shown as the strip's label. */
    val weatherLocationName: String? = null,
    /** Per-task weather requirements, kept so the fit can be recomputed as tasks change. */
    val weatherRequirements: Map<String, TaskWeatherRequirement> = emptyMap(),
    /** taskId → weather fit for tasks that carry a weather profile — drives the "not today" badge. */
    val taskWeatherFit: Map<String, TaskWeatherFit> = emptyMap(),
    /** Active severe-weather alerts for the tracked location — headlines the week's alert banner. */
    val weatherAlerts: List<WeatherAlert> = emptyList(),
    /** Actionable severe-weather advisories (e.g. "Storm approaching · delay ~90 min"). */
    val weatherAdvisories: List<WeatherAdvisory> = emptyList(),
    /** All (non-archived) household people, for the task detail people picker. */
    val people: List<Person> = emptyList(),
    /** taskId → involved person ids. */
    val taskPeople: Map<String, List<String>> = emptyMap(),
    /** counterId → total logged this week, for counter chips and the task counter section. */
    val counterWeeklyTotals: Map<String, Int> = emptyMap(),
    /** taskId → (checked, total) subtask counts, for the row progress chip. */
    val subtaskCounts: Map<String, Pair<Int, Int>> = emptyMap()
)

@OptIn(ExperimentalCoroutinesApi::class)
class ThisWeekViewModel(
    private val appContext: Context,
    private val saveScope: CoroutineScope,
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository,
    private val aspectRepository: AspectRepository,
    private val importRepository: ImportRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val notificationRepository: NotificationRepository,
    private val costResourceRepository: CostResourceRepository,
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: PreferencesRepository,
    private val runbookRepository: RunbookRepository,
    private val templateRepository: TemplateRepository,
    private val counterRepository: CounterRepository,
    private val weatherRepository: WeatherRepository,
    private val personRepository: PersonRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThisWeekUiState())
    val uiState: StateFlow<ThisWeekUiState> = _uiState.asStateFlow()

    // The running timer is kept out of uiState so its per-second tick doesn't churn the whole
    // screen state. `activeTimer` changes only on start/stop; `timerElapsedSeconds` ticks every
    // second and is read with a deferred lambda so only the active row's clock recomposes.
    private val _activeTimer = MutableStateFlow<ActiveTimer?>(null)
    val activeTimer: StateFlow<ActiveTimer?> = _activeTimer.asStateFlow()

    private val _timerElapsedSeconds = MutableStateFlow(0)
    val timerElapsedSeconds: StateFlow<Int> = _timerElapsedSeconds.asStateFlow()

    private var timerJob: Job? = null

    private val _undoChannel = Channel<UndoEvent>(Channel.CONFLATED)
    val undoEvents = _undoChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            costResourceRepository.observeActiveResources().collectLatest { resources ->
                _uiState.update { it.copy(costResources = resources) }
            }
        }
        viewModelScope.launch {
            counterRepository.observeActive().collectLatest { counters ->
                _uiState.update { it.copy(counters = counters) }
            }
        }
        viewModelScope.launch {
            projectRepository.observeActive().collectLatest { projects ->
                _uiState.update { it.copy(projects = projects) }
            }
        }
        // Weather is read cache-only (never triggers a network refresh — the WeatherRefreshWorker
        // owns that). We track the first tracked location's cached forecast and every task's weather
        // requirement, so the list can show a weekly strip and per-task "best day" badges.
        viewModelScope.launch {
            weatherRepository.observeLocations()
                .flatMapLatest { locs ->
                    val primary = locs.firstOrNull()
                    if (primary == null) flowOf(null)
                    else weatherRepository.observeReport(primary.id)
                }
                .combine(weatherRepository.observeRequirements()) { report, reqs -> report to reqs }
                .collectLatest { (report, reqs) ->
                    val dayPeriods = report?.daily?.filter { it.isDaytime } ?: emptyList()
                    val alerts = report?.alerts ?: emptyList()
                    val advisories = if (report != null)
                        SevereWeatherIntel.advise(alerts, report.hourly, DateUtil.now())
                    else emptyList()
                    _uiState.update { state ->
                        state.copy(
                            weeklyForecast = dayPeriods,
                            weatherLocationName = report?.location?.name?.takeIf { it.isNotBlank() },
                            weatherRequirements = reqs,
                            taskWeatherFit = computeWeatherFit(state.rawTasks, reqs, dayPeriods),
                            weatherAlerts = alerts,
                            weatherAdvisories = advisories
                        )
                    }
                }
        }
        // People: the roster (for the detail picker) and each task's involved-person links.
        viewModelScope.launch {
            personRepository.observeAll().collect { people ->
                _uiState.update { it.copy(people = people.filter { p -> !p.isArchived }) }
            }
        }
        viewModelScope.launch {
            personRepository.observeTaskPeople().collect { links ->
                _uiState.update { it.copy(taskPeople = links) }
            }
        }
        // Weekly counter totals for the current week — powers the dashboard chips and the task
        // counter section's "N this week".
        viewModelScope.launch {
            val weekKey = DateUtil.weekIndexFor(DateUtil.currentWeekStart())
            counterRepository.observeWeeklyTotalsByCounter(weekKey).collect { totals ->
                _uiState.update { it.copy(counterWeeklyTotals = totals) }
            }
        }
        // Live subtask progress for every task in the week (re-subscribes as the task set changes).
        viewModelScope.launch {
            weekRepository.observeCurrentWeek()
                .flatMapLatest { week ->
                    if (week == null) flowOf(emptyList())
                    else taskRepository.observeTasksForWeek(week.id)
                }
                .map { tasks -> tasks.map { it.id } }
                .distinctUntilChanged()
                .flatMapLatest { ids ->
                    if (ids.isEmpty()) flowOf(emptyMap())
                    else runbookRepository.observeSubtaskCountsByTasks(ids)
                }
                .collect { counts -> _uiState.update { it.copy(subtaskCounts = counts) } }
        }
        viewModelScope.launch {
            // Refresh the runbook list (with steps) whenever runbooks change, so the
            // create-task picker and detail-sheet "add checklist" picker stay current.
            runbookRepository.observeRunbooks().collectLatest {
                _uiState.update { it.copy(runbooks = runbookRepository.getAllRunbooksWithSteps()) }
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
                        // Fetch ancestor notes for carried tasks
                        val ancestorNotes = mutableMapOf<String, List<TaskNote>>()
                        for (task in tasks.filter { it.carriedCount > 0 && it.carriedFromTaskId != null }) {
                            val lineageIds = taskRepository.getLineageIds(task.id)
                            val ancestorIds = lineageIds.filter { it != task.id }
                            if (ancestorIds.isNotEmpty()) {
                                ancestorNotes[task.id] = taskNoteRepository.getByTaskIds(ancestorIds)
                            }
                        }
                        _uiState.update { state ->
                            val grouped = groupAndFilterTasks(tasks, aspectMap, categoryMap, state.sortOrder, state.searchQuery, state.showOverdueOnly)
                            state.copy(
                                week = week,
                                rawTasks = tasks,
                                groupedTasks = grouped,
                                aspects = aspectMap,
                                categories = categoryMap,
                                taskNotes = notesByTask,
                                ancestorNotesByTask = ancestorNotes,
                                taskTimeMinutes = timeByTask,
                                taskCostEntries = costByTask,
                                isLoading = false,
                                weekProgress = computeProgress(tasks, timeByTask),
                                taskWeatherFit = computeWeatherFit(tasks, state.weatherRequirements, state.weeklyForecast)
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
        // Restore saved sort order after tasks load
        viewModelScope.launch {
            val savedSort = SortOrder.values().firstOrNull { it.name == preferencesRepository.savedSortOrder } ?: SortOrder.DEFAULT
            _uiState.first { !it.isLoading }
            _uiState.update { state ->
                state.copy(
                    sortOrder = savedSort,
                    groupedTasks = groupAndFilterTasks(state.rawTasks, state.aspects, state.categories, savedSort, state.searchQuery, state.showOverdueOnly)
                )
            }
        }
    }

    /** Build the per-task weather fit map for tasks that carry a (non-empty) weather profile. */
    private fun computeWeatherFit(
        tasks: List<Task>,
        requirements: Map<String, TaskWeatherRequirement>,
        dayPeriods: List<ForecastPeriod>
    ): Map<String, TaskWeatherFit> {
        if (requirements.isEmpty() || dayPeriods.isEmpty()) return emptyMap()
        return tasks.mapNotNull { task ->
            val req = requirements[task.id] ?: return@mapNotNull null
            TaskWeatherFitCalculator.compute(req, dayPeriods)?.let { task.id to it }
        }.toMap()
    }

    private fun computeProgress(tasks: List<Task>, timeByTask: Map<String, Int>): WeekProgress {
        val relevant = tasks.filter { it.status != TaskStatus.CARRIED_FORWARD && it.status != TaskStatus.QUEUED }
        val completed = relevant.count { it.status == TaskStatus.COMPLETED }
        val total = relevant.size
        val totalTime = timeByTask.values.sum()
        return WeekProgress(completed, total, totalTime)
    }

    fun setSortOrder(order: SortOrder) {
        preferencesRepository.savedSortOrder = order.name
        _uiState.update { state ->
            state.copy(
                sortOrder = order,
                groupedTasks = groupAndFilterTasks(state.rawTasks, state.aspects, state.categories, order, state.searchQuery, state.showOverdueOnly)
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                groupedTasks = groupAndFilterTasks(state.rawTasks, state.aspects, state.categories, state.sortOrder, query, state.showOverdueOnly)
            )
        }
    }

    fun toggleOverdueFilter() {
        _uiState.update { state ->
            val newFlag = !state.showOverdueOnly
            state.copy(
                showOverdueOnly = newFlag,
                groupedTasks = groupAndFilterTasks(
                    state.rawTasks, state.aspects, state.categories,
                    state.sortOrder, state.searchQuery, newFlag
                )
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
        searchQuery: String,
        overdueOnly: Boolean = false
    ): List<GroupedTasks> {
        // Queued (future-week) tasks live in the Planning tab's Future Tasks screen, not here.
        var filtered = tasks.filter { it.status != TaskStatus.QUEUED }
        if (searchQuery.isNotBlank()) {
            filtered = filtered.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
        if (overdueOnly) {
            val today = java.time.LocalDate.now().toString()
            filtered = filtered.filter { task ->
                task.status == TaskStatus.PENDING &&
                task.dueDate != null && task.dueDate <= today
            }
        }

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

    private fun refreshWidget() {
        viewModelScope.launch { try { LifeOpsWidget().updateAll(appContext) } catch (_: Exception) {} }
    }

    fun onCompleteTask(task: Task) {
        viewModelScope.launch {
            taskRepository.completeTask(task)
            refreshWidget()
        }
    }

    fun onUnCompleteTask(taskId: String) {
        viewModelScope.launch {
            taskRepository.unCompleteTask(taskId)
            refreshWidget()
        }
    }

    fun onUnSkipTask(taskId: String) {
        viewModelScope.launch {
            taskRepository.unSkipTask(taskId)
            refreshWidget()
        }
    }

    fun onSkipTask(taskId: String) {
        viewModelScope.launch {
            taskRepository.skipTask(taskId)
            refreshWidget()
            _undoChannel.trySend(UndoEvent("Task skipped", taskId, UndoEventAction.UNSKIP))
        }
    }

    fun onUnsuccessTask(taskId: String) {
        viewModelScope.launch {
            taskRepository.unsuccessTask(taskId)
            refreshWidget()
            _undoChannel.trySend(UndoEvent("Marked unsuccessful", taskId, UndoEventAction.UN_UNSUCCESSFUL))
        }
    }

    fun onUnUnsuccessTask(taskId: String) {
        viewModelScope.launch {
            taskRepository.unUnsuccessTask(taskId)
            refreshWidget()
        }
    }

    fun onCarryForward(task: Task, reason: CarryForwardReason) {
        viewModelScope.launch {
            taskRepository.carryForward(task, reason)
            refreshWidget()
            _undoChannel.trySend(UndoEvent("Carried forward", task.id, UndoEventAction.UN_CARRY_FORWARD))
        }
    }

    fun onUnCarryForward(taskId: String) {
        viewModelScope.launch {
            taskRepository.unCarryForward(taskId)
            refreshWidget()
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

    fun onReorderTask(fromIndex: Int, toIndex: Int, currentList: List<Task>) {
        val reordered = currentList.toMutableList()
        val moved = reordered.removeAt(fromIndex)
        reordered.add(toIndex, moved)
        viewModelScope.launch {
            for ((idx, task) in reordered.withIndex()) {
                if (task.sortOrder != idx) taskRepository.updateTaskSortOrder(task.id, idx)
            }
        }
    }

    private var weekCloseInFlight = false

    fun onCloseWeek(selfRating: Int? = null, selfRatingNote: String? = null) {
        if (weekCloseInFlight) return
        viewModelScope.launch {
            weekCloseInFlight = true
            try {
                val week = _uiState.value.week ?: return@launch
                stopTimer(saveEntry = true)
                val newWeek = weekRepository.createNextWeek(week)
                taskRepository.closeWeek(week.id, newWeek.id, selfRating, selfRatingNote)
                taskRepository.seedRecurringTasks(week.id, newWeek.id)
                refreshWidget()
            } finally {
                weekCloseInFlight = false
            }
        }
    }

    fun onPromoteToProject(taskId: String) {
        viewModelScope.launch {
            val task = taskRepository.getById(taskId) ?: return@launch
            val projectId = java.util.UUID.randomUUID().toString()
            projectRepository.createProject(projectId, task.title, task.aspectId)
            taskRepository.promoteTaskToProject(taskId, projectId)
        }
    }

    // Timer
    fun startTimer(taskId: String, isPomodoro: Boolean = false) {
        stopTimer(saveEntry = true)
        val startMillis = System.currentTimeMillis()
        _activeTimer.value = ActiveTimer(taskId, startMillis, isPomodoro = isPomodoro)
        _timerElapsedSeconds.value = 0
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val elapsed = ((System.currentTimeMillis() - startMillis) / 1000).toInt()
                _timerElapsedSeconds.value = elapsed
                // Auto-stop Pomodoro at 25 minutes
                if (isPomodoro && elapsed >= 1500) {
                    stopTimer(saveEntry = true)
                    break
                }
            }
        }
    }

    fun stopTimer(saveEntry: Boolean = true) {
        val timer = _activeTimer.value ?: return
        timerJob?.cancel()
        timerJob = null
        // Always log actual elapsed time. For a completed Pomodoro elapsed ≈ 1500s → 25min.
        val minutes = _timerElapsedSeconds.value / 60
        if (saveEntry && minutes > 0) {
            viewModelScope.launch { timeEntryRepository.logTime(timer.taskId, minutes) }
        }
        _activeTimer.value = null
        _timerElapsedSeconds.value = 0
    }

    // Detail sheet
    private var subtaskJob: Job? = null

    fun openDetail(taskId: String) {
        _uiState.update { it.copy(detailTaskId = taskId, detailSubtasks = emptyList()) }
        subtaskJob?.cancel()
        subtaskJob = viewModelScope.launch {
            runbookRepository.observeSubtasks(taskId).collectLatest { subs ->
                _uiState.update { it.copy(detailSubtasks = subs) }
            }
        }
    }

    fun closeDetail() {
        subtaskJob?.cancel()
        subtaskJob = null
        _uiState.update { it.copy(detailTaskId = null, detailSubtasks = emptyList()) }
    }

    fun onToggleSubtask(subtaskId: String, checked: Boolean) {
        viewModelScope.launch { runbookRepository.setSubtaskChecked(subtaskId, checked) }
    }

    fun onAttachRunbook(taskId: String, runbookId: String) {
        viewModelScope.launch { runbookRepository.stampRunbookById(taskId, runbookId) }
    }

    fun onDeleteSubtask(subtaskId: String) {
        viewModelScope.launch { runbookRepository.deleteSubtask(subtaskId) }
    }

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
                .onSuccess {
                    refreshWidget()
                    closeImportDialog()
                }
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
        estimatedMinutes: Int? = null,
        projectId: String? = null,
        runbookId: String? = null,
        counterId: String? = null,
        recurrenceIntervalWeeks: Int = 1,
        recurrenceDayOfMonth: Int? = null
    ) {
        viewModelScope.launch {
            val week = weekRepository.getOrCreateCurrentWeek()
            val slug = title.toSlug()
            // Phase 4 merge: incumbent survives
            if (slug in taskRepository.getSlugsByWeek(week.id)) {
                _uiState.update { it.copy(showCreateTaskDialog = false) }
                return@launch
            }
            val resourceValue = ImportParser.computeResourceValue(
                priority.label, hardDeadline, estimatedMinutes, isManuallyAdded = true
            )
            // Due beyond this week? Park it in the Future Tasks queue; it becomes pending
            // once a week containing its due date opens (see TaskRepository.closeWeek).
            val validDueDate = dueDate?.takeIf { DateUtil.isValidDate(it) }
            val status = if (validDueDate != null && validDueDate > week.endDate) TaskStatus.QUEUED
                         else TaskStatus.PENDING
            val task = Task(
                id = UUID.randomUUID().toString(),
                weekId = week.id,
                title = title,
                aspectId = aspectId,
                categoryId = categoryId,
                priority = priority,
                dueDate = validDueDate,
                hardDeadline = hardDeadline,
                status = status,
                resourceValue = resourceValue,
                createdAt = DateUtil.now(),
                isRecurring = isRecurring,
                estimatedMinutes = estimatedMinutes,
                isManuallyAdded = true,
                projectId = projectId,
                slug = slug,
                counterId = counterId,
                recurrenceIntervalWeeks = if (isRecurring) recurrenceIntervalWeeks.coerceAtLeast(1) else 1,
                recurrenceDayOfMonth = if (isRecurring) recurrenceDayOfMonth else null
            )
            taskRepository.upsertTask(task)
            note?.let { taskNoteRepository.addNote(task.id, it) }
            // Phase 8: optional runbook stamp on one-off tasks
            runbookId?.let { runbookRepository.stampRunbookById(task.id, it) }
            notificationRepository.scheduleForTask(task)
            _uiState.update { it.copy(showCreateTaskDialog = false) }
        }
    }

    // Template picker (Phase 8 — third entry path)
    fun showTemplatePicker() {
        viewModelScope.launch {
            val templates = templateRepository.getAllWithTasks()
            _uiState.update { it.copy(showTemplatePickerDialog = true, availableTemplates = templates) }
        }
    }

    fun hideTemplatePicker() = _uiState.update { it.copy(showTemplatePickerDialog = false) }

    fun applyTemplate(templateId: String) {
        viewModelScope.launch {
            val week = weekRepository.getOrCreateCurrentWeek()
            templateRepository.applyTemplate(templateId, week.id)
            _uiState.update { it.copy(showTemplatePickerDialog = false) }
        }
    }

    fun onCreateProject(id: String, title: String, aspectId: String?) {
        viewModelScope.launch { projectRepository.createProject(id, title, aspectId) }
    }

    fun onAssignProject(taskId: String, projectId: String?) {
        viewModelScope.launch {
            val task = taskRepository.getById(taskId) ?: return@launch
            taskRepository.updateTask(task.copy(projectId = projectId))
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
        val timer = _activeTimer.value ?: return
        timerJob?.cancel()
        timerJob = null
        val minutes = _timerElapsedSeconds.value / 60
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
        categoryId: String? = null,
        projectId: String? = null,
        counterId: String? = null,
        recurrenceIntervalWeeks: Int = 1,
        recurrenceDayOfMonth: Int? = null
    ) {
        val task = _uiState.value.editingTask ?: return
        viewModelScope.launch {
            val validatedDueDate = dueDate?.takeIf { DateUtil.isValidDate(it) }
            val newResourceValue = ImportParser.computeResourceValue(
                priority.label, hardDeadline, estimatedMinutes, task.isManuallyAdded
            )
            // Editing the due date across the week boundary moves the task in/out of the queue.
            val weekEnd = _uiState.value.week?.endDate
            val newStatus = when {
                task.status == TaskStatus.PENDING && weekEnd != null &&
                    validatedDueDate != null && validatedDueDate > weekEnd -> TaskStatus.QUEUED
                task.status == TaskStatus.QUEUED &&
                    (validatedDueDate == null || weekEnd == null || validatedDueDate <= weekEnd) -> TaskStatus.PENDING
                else -> task.status
            }
            val updatedTask = task.copy(
                title = title,
                priority = priority,
                dueDate = validatedDueDate,
                status = newStatus,
                hardDeadline = hardDeadline,
                resourceValue = newResourceValue,
                isRecurring = isRecurring,
                estimatedMinutes = estimatedMinutes,
                aspectId = aspectId,
                categoryId = categoryId,
                projectId = projectId,
                counterId = counterId,
                recurrenceIntervalWeeks = if (isRecurring) recurrenceIntervalWeeks.coerceAtLeast(1) else 1,
                recurrenceDayOfMonth = if (isRecurring) recurrenceDayOfMonth else null
            )
            taskRepository.updateTask(updatedTask)
            if (task.dueDate != validatedDueDate || task.hardDeadline != hardDeadline) {
                notificationRepository.cancelForTask(task.id)
                notificationRepository.scheduleForTask(updatedTask)
            }
            _uiState.update { it.copy(editingTask = null) }
        }
    }

    // --- Task composite actions (people / counters) ---

    fun attachPerson(taskId: String, personId: String) {
        viewModelScope.launch { personRepository.attach(taskId, personId) }
    }

    fun detachPerson(taskId: String, personId: String) {
        viewModelScope.launch { personRepository.detach(taskId, personId) }
    }

    /** Log one occurrence of the counter this task ticks — the composite "did it" action. */
    fun logCounter(counterId: String) {
        viewModelScope.launch { counterRepository.logEvent(counterId) }
    }
}

class ThisWeekViewModelFactory(
    private val appContext: Context,
    private val saveScope: CoroutineScope,
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository,
    private val aspectRepository: AspectRepository,
    private val importRepository: ImportRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val notificationRepository: NotificationRepository,
    private val costResourceRepository: CostResourceRepository,
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: PreferencesRepository,
    private val runbookRepository: RunbookRepository,
    private val templateRepository: TemplateRepository,
    private val counterRepository: CounterRepository,
    private val weatherRepository: WeatherRepository,
    private val personRepository: PersonRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ThisWeekViewModel(
            appContext, saveScope, weekRepository, taskRepository, aspectRepository, importRepository,
            taskNoteRepository, timeEntryRepository, notificationRepository, costResourceRepository,
            projectRepository, preferencesRepository, runbookRepository, templateRepository, counterRepository,
            weatherRepository, personRepository
        ) as T
}
