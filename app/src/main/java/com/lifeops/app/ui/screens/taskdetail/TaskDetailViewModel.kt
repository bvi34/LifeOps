package com.lifeops.app.ui.screens.taskdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.CostResource
import com.lifeops.app.data.model.Counter
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.Project
import com.lifeops.app.data.model.Subtask
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskAttachment
import com.lifeops.app.data.model.TaskCostEntry
import com.lifeops.app.data.model.TaskNote
import com.lifeops.app.data.model.TaskWeatherRequirement
import com.lifeops.app.data.repository.CostResourceRepository
import com.lifeops.app.data.repository.CounterRepository
import com.lifeops.app.data.repository.PersonRepository
import com.lifeops.app.data.repository.ProjectRepository
import com.lifeops.app.data.repository.RunbookRepository
import com.lifeops.app.data.repository.TaskAttachmentRepository
import com.lifeops.app.data.repository.TaskNoteRepository
import com.lifeops.app.data.repository.TaskRepository
import com.lifeops.app.data.repository.TimeEntryRepository
import com.lifeops.app.data.repository.WeatherRepository
import com.lifeops.app.data.repository.WeekRepository
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Read-only snapshot of a single task, loaded by id from the repositories rather than from any
 * one screen's live state. This is what lets a task open as its own screen from search (or any
 * other entry point) — including tasks from **past, closed weeks** that the This Week screen no
 * longer holds. It never mutates anything: the live editing surface stays the This Week sheet.
 */
data class TaskDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val task: Task? = null,
    /** True when the task belongs to the current open week; false for closed/past weeks. */
    val isCurrentWeek: Boolean = false,
    val weekLabel: String? = null,
    val notes: List<TaskNote> = emptyList(),
    val totalTimeMinutes: Int = 0,
    val subtasks: List<Subtask> = emptyList(),
    val project: Project? = null,
    val counter: Counter? = null,
    val involvedPeople: List<Person> = emptyList(),
    val costEntries: List<TaskCostEntry> = emptyList(),
    val costResources: List<CostResource> = emptyList(),
    val attachments: List<TaskAttachment> = emptyList(),
    val weatherRequirement: TaskWeatherRequirement? = null
)

class TaskDetailViewModel(
    private val taskId: String,
    private val taskRepository: TaskRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val costResourceRepository: CostResourceRepository,
    private val runbookRepository: RunbookRepository,
    private val projectRepository: ProjectRepository,
    private val counterRepository: CounterRepository,
    private val personRepository: PersonRepository,
    private val taskAttachmentRepository: TaskAttachmentRepository,
    private val weatherRepository: WeatherRepository,
    private val weekRepository: WeekRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskDetailUiState())
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val task = taskRepository.getById(taskId)
            if (task == null) {
                _uiState.update { it.copy(loading = false, notFound = true) }
                return@launch
            }

            val currentWeek = weekRepository.getCurrentWeek()
            val isCurrentWeek = currentWeek?.id == task.weekId
            val weekLabel = weekRepository.getAllWeeksSync()
                .firstOrNull { it.id == task.weekId }
                ?.let { "Week of ${DateUtil.formatDate(it.startDate)}" }

            val notes = taskNoteRepository.getByTask(taskId)
            val totalTime = timeEntryRepository.getByTask(taskId).sumOf { it.durationMinutes }
            val project = task.projectId?.let { projectRepository.getProjectById(it) }
            val counter = task.counterId?.let { counterRepository.getById(it) }
            val costResources = costResourceRepository.getAllSync()

            _uiState.update {
                it.copy(
                    loading = false,
                    notFound = false,
                    task = task,
                    isCurrentWeek = isCurrentWeek,
                    weekLabel = weekLabel,
                    notes = notes,
                    totalTimeMinutes = totalTime,
                    project = project,
                    counter = counter,
                    costResources = costResources
                )
            }
        }

        // Involved people: combine the full roster with this task's links so the chips stay named
        // even for a historical task.
        viewModelScope.launch {
            combine(
                personRepository.observeAll(),
                personRepository.observePersonIdsForTask(taskId)
            ) { people, ids -> people.filter { it.id in ids.toSet() } }
                .collect { involved -> _uiState.update { it.copy(involvedPeople = involved) } }
        }

        // Subtasks, cost entries, attachments, and the weather requirement stream so the read-only
        // view still reflects edits made in the live This Week sheet while it's open.
        viewModelScope.launch {
            runbookRepository.observeSubtasks(taskId).collect { subs ->
                _uiState.update { it.copy(subtasks = subs) }
            }
        }
        viewModelScope.launch {
            costResourceRepository.observeEntriesByTask(taskId).collect { entries ->
                _uiState.update { it.copy(costEntries = entries) }
            }
        }
        viewModelScope.launch {
            taskAttachmentRepository.observeByTask(taskId).collect { atts ->
                _uiState.update { it.copy(attachments = atts) }
            }
        }
        viewModelScope.launch {
            weatherRepository.observeRequirement(taskId).collect { req ->
                _uiState.update { it.copy(weatherRequirement = req) }
            }
        }
    }
}

class TaskDetailViewModelFactory(
    private val taskId: String,
    private val taskRepository: TaskRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val costResourceRepository: CostResourceRepository,
    private val runbookRepository: RunbookRepository,
    private val projectRepository: ProjectRepository,
    private val counterRepository: CounterRepository,
    private val personRepository: PersonRepository,
    private val taskAttachmentRepository: TaskAttachmentRepository,
    private val weatherRepository: WeatherRepository,
    private val weekRepository: WeekRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TaskDetailViewModel(
            taskId, taskRepository, taskNoteRepository, timeEntryRepository, costResourceRepository,
            runbookRepository, projectRepository, counterRepository, personRepository,
            taskAttachmentRepository, weatherRepository, weekRepository
        ) as T
}
