package com.lifeops.app.ui.screens.thisweek

import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ImportParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class ActiveTimer(
    val taskId: String,
    val startMillis: Long,
    val elapsedSeconds: Int = 0
)

data class GroupedTasks(
    val aspect: Aspect?,
    val aspectColor: String,
    val categories: List<CategoryGroup>
)

data class CategoryGroup(
    val category: Category?,
    val tasks: List<Task>
)

data class ThisWeekUiState(
    val week: Week? = null,
    val groupedTasks: List<GroupedTasks> = emptyList(),
    val aspects: Map<String, Aspect> = emptyMap(),
    val categories: Map<String, Category> = emptyMap(),
    val taskNotes: Map<String, List<TaskNote>> = emptyMap(),
    val taskTimeMinutes: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = true,
    val importDialogOpen: Boolean = false,
    val importPreview: ImportPreview? = null,
    val importError: String? = null,
    val importJson: String = "",
    val editingTask: Task? = null,
    val showCreateTaskDialog: Boolean = false,
    val activeTimer: ActiveTimer? = null,
    val detailTaskId: String? = null
)

class ThisWeekViewModel(
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository,
    private val aspectRepository: AspectRepository,
    private val importRepository: ImportRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val notificationRepository: NotificationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThisWeekUiState())
    val uiState: StateFlow<ThisWeekUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                weekRepository.observeCurrentWeek(),
                aspectRepository.observeAspects(),
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
                        timeEntryRepository.observeByWeek(week.id)
                    ) { tasks, notes, timeEntries ->
                        Triple(tasks, notes, timeEntries)
                    }.collectLatest { (tasks, notes, timeEntries) ->
                        val notesByTask = notes.groupBy { it.taskId }
                        val timeByTask = timeEntries
                            .groupBy { it.taskId }
                            .mapValues { (_, entries) -> entries.sumOf { it.durationMinutes } }
                        val grouped = groupTasks(tasks, aspectMap, categoryMap)
                        _uiState.update {
                            it.copy(
                                week = week,
                                groupedTasks = grouped,
                                aspects = aspectMap,
                                categories = categoryMap,
                                taskNotes = notesByTask,
                                taskTimeMinutes = timeByTask,
                                isLoading = false
                            )
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            week = null,
                            groupedTasks = emptyList(),
                            aspects = aspectMap,
                            categories = categoryMap,
                            taskNotes = emptyMap(),
                            taskTimeMinutes = emptyMap(),
                            isLoading = false
                        )
                    }
                }
            }
        }
    }

    private fun groupTasks(
        tasks: List<Task>,
        aspects: Map<String, Aspect>,
        categories: Map<String, Category>
    ): List<GroupedTasks> {
        val byAspect = tasks.groupBy { it.aspectId }
        return byAspect.map { (aspectId, aspectTasks) ->
            val aspect = aspectId?.let { aspects[it] }
            val byCategory = aspectTasks.groupBy { it.categoryId }
            val categoryGroups = byCategory.map { (catId, catTasks) ->
                CategoryGroup(catId?.let { categories[it] }, catTasks)
            }
            GroupedTasks(aspect, aspect?.color ?: "#6200EE", categoryGroups)
        }
    }

    fun onCompleteTask(task: Task) {
        viewModelScope.launch { taskRepository.completeTask(task) }
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

    fun onCloseWeek() {
        viewModelScope.launch {
            val week = _uiState.value.week ?: return@launch
            stopTimer(saveEntry = true)
            taskRepository.closeWeek(week.id)
            weekRepository.getOrCreateCurrentWeek()
        }
    }

    // Timer
    fun startTimer(taskId: String) {
        stopTimer(saveEntry = true)
        val startMillis = System.currentTimeMillis()
        _uiState.update { it.copy(activeTimer = ActiveTimer(taskId, startMillis)) }
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val elapsed = ((System.currentTimeMillis() - startMillis) / 1000).toInt()
                _uiState.update { state ->
                    state.copy(activeTimer = state.activeTimer?.copy(elapsedSeconds = elapsed))
                }
            }
        }
    }

    fun stopTimer(saveEntry: Boolean = true) {
        val timer = _uiState.value.activeTimer ?: return
        timerJob?.cancel()
        timerJob = null
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
        it.copy(importDialogOpen = false, importPreview = null, importJson = "", importError = null)
    }

    fun onImportJsonChange(json: String) = _uiState.update { it.copy(importJson = json) }

    fun previewImport() {
        val json = _uiState.value.importJson
        viewModelScope.launch {
            importRepository.previewImport(json)
                .onSuccess { preview -> _uiState.update { it.copy(importPreview = preview, importError = null) } }
                .onFailure { e -> _uiState.update { it.copy(importError = e.message, importPreview = null) } }
        }
    }

    fun commitImport() {
        val json = _uiState.value.importJson
        viewModelScope.launch {
            importRepository.commitImport(json)
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
        hardDeadline: Boolean
    ) {
        viewModelScope.launch {
            val week = weekRepository.getOrCreateCurrentWeek()
            val resourceValue = ImportParser.computeResourceValue(priority.label, hardDeadline)
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
                createdAt = DateUtil.now()
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

    fun startEditTask(task: Task) = _uiState.update { it.copy(editingTask = task) }
    fun cancelEditTask() = _uiState.update { it.copy(editingTask = null) }

    fun saveTaskEdit(title: String, priority: Priority, dueDate: String?, hardDeadline: Boolean) {
        val task = _uiState.value.editingTask ?: return
        viewModelScope.launch {
            val newResourceValue = ImportParser.computeResourceValue(priority.label, hardDeadline)
            taskRepository.updateTask(
                task.copy(
                    title = title,
                    priority = priority,
                    dueDate = dueDate,
                    hardDeadline = hardDeadline,
                    resourceValue = newResourceValue
                )
            )
            _uiState.update { it.copy(editingTask = null) }
        }
    }
}

class ThisWeekViewModelFactory(
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository,
    private val aspectRepository: AspectRepository,
    private val importRepository: ImportRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val notificationRepository: NotificationRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ThisWeekViewModel(
            weekRepository, taskRepository, aspectRepository, importRepository,
            taskNoteRepository, timeEntryRepository, notificationRepository
        ) as T
}
