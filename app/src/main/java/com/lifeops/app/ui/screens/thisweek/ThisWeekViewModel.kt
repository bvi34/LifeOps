package com.lifeops.app.ui.screens.thisweek

import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
    val isLoading: Boolean = true,
    val importDialogOpen: Boolean = false,
    val importPreview: ImportPreview? = null,
    val importError: String? = null,
    val importJson: String = "",
    val editingTask: Task? = null
)

class ThisWeekViewModel(
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository,
    private val aspectRepository: AspectRepository,
    private val importRepository: ImportRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThisWeekUiState())
    val uiState: StateFlow<ThisWeekUiState> = _uiState.asStateFlow()

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
                    taskRepository.observeTasksForWeek(week.id).collectLatest { tasks ->
                        val grouped = groupTasks(tasks, aspectMap, categoryMap)
                        _uiState.update {
                            it.copy(
                                week = week.toModel(),
                                groupedTasks = grouped,
                                aspects = aspectMap,
                                categories = categoryMap,
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
                            isLoading = false
                        )
                    }
                }
            }
        }
    }

    private fun Week.toModel() = this

    private fun groupTasks(
        tasks: List<Task>,
        aspects: Map<String, Aspect>,
        categories: Map<String, Category>
    ): List<GroupedTasks> {
        val byAspect = tasks.groupBy { it.aspectId }
        val result = mutableListOf<GroupedTasks>()
        byAspect.forEach { (aspectId, aspectTasks) ->
            val aspect = aspectId?.let { aspects[it] }
            val byCategory = aspectTasks.groupBy { it.categoryId }
            val categoryGroups = byCategory.map { (catId, catTasks) ->
                CategoryGroup(catId?.let { categories[it] }, catTasks)
            }
            result.add(GroupedTasks(aspect, aspect?.color ?: "#6200EE", categoryGroups))
        }
        return result
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
            taskRepository.closeWeek(week.id)
            // Create the next week so the UI doesn't show an empty screen
            weekRepository.getOrCreateCurrentWeek()
        }
    }

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

    fun startEditTask(task: Task) = _uiState.update { it.copy(editingTask = task) }

    fun cancelEditTask() = _uiState.update { it.copy(editingTask = null) }

    fun saveTaskEdit(
        title: String,
        notes: String?,
        priority: com.lifeops.app.data.model.Priority,
        dueDate: String?,
        hardDeadline: Boolean
    ) {
        val task = _uiState.value.editingTask ?: return
        viewModelScope.launch {
            val newResourceValue = com.lifeops.app.util.ImportParser.computeResourceValue(priority.label, hardDeadline)
            taskRepository.updateTask(
                task.copy(
                    title = title,
                    notes = notes,
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
    private val importRepository: ImportRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ThisWeekViewModel(weekRepository, taskRepository, aspectRepository, importRepository) as T
}
