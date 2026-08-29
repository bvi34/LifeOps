package com.lifeops.app.ui.screens.taskdetail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.CarryForwardReason
import com.lifeops.app.data.model.Category
import com.lifeops.app.data.model.CostResource
import com.lifeops.app.data.model.Counter
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.Priority
import com.lifeops.app.data.model.Operation
import com.lifeops.app.data.model.RunbookWithSteps
import com.lifeops.app.data.model.Subtask
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskAttachment
import com.lifeops.app.data.model.TaskCostEntry
import com.lifeops.app.data.model.TaskNote
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.data.model.TaskWeatherRequirement
import com.lifeops.app.data.repository.AspectRepository
import com.lifeops.app.data.repository.CostResourceRepository
import com.lifeops.app.data.repository.CounterRepository
import com.lifeops.app.data.repository.NotificationRepository
import com.lifeops.app.data.repository.PersonRepository
import com.lifeops.app.data.repository.OperationRepository
import com.lifeops.app.data.repository.RunbookRepository
import com.lifeops.app.data.repository.TaskAttachmentRepository
import com.lifeops.app.data.repository.TaskNoteRepository
import com.lifeops.app.data.repository.TaskRepository
import com.lifeops.app.data.repository.TimeEntryRepository
import com.lifeops.app.data.repository.TimerController
import com.lifeops.app.data.repository.WeatherRepository
import com.lifeops.app.data.repository.WeekRepository
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ImportParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Backs the standalone task detail route (`task_detail/{id}`). It loads any task by id — including
 * ones from past, closed weeks the This Week screen no longer holds — and drives the shared
 * [com.lifeops.app.ui.components.TaskDetailContent]. Tasks in the current open week are fully
 * editable here (the same operations the old This Week sheet offered); tasks from a closed week
 * load with [editable] = false, so they render read-only.
 */
data class TaskDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val task: Task? = null,
    /** True when the task belongs to the current open week; false for closed/past weeks. */
    val editable: Boolean = false,
    val weekLabel: String? = null,
    val notes: List<TaskNote> = emptyList(),
    val ancestorNotes: List<TaskNote> = emptyList(),
    val totalTimeMinutes: Int = 0,
    val subtasks: List<Subtask> = emptyList(),
    val runbooks: List<RunbookWithSteps> = emptyList(),
    val operations: List<Operation> = emptyList(),
    val counterWeeklyTotals: Map<String, Int> = emptyMap(),
    val involvedPeople: List<Person> = emptyList(),
    val allPeople: List<Person> = emptyList(),
    val costEntries: List<TaskCostEntry> = emptyList(),
    val costResources: List<CostResource> = emptyList(),
    val attachments: List<TaskAttachment> = emptyList(),
    val weatherRequirement: TaskWeatherRequirement? = null,
    val aspects: List<Aspect> = emptyList(),
    val categories: Map<String, Category> = emptyMap(),
    val counters: List<Counter> = emptyList(),
    val editingTask: Task? = null
)

class TaskDetailViewModel(
    private val appContext: Context,
    private val taskId: String,
    private val taskRepository: TaskRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val costResourceRepository: CostResourceRepository,
    private val runbookRepository: RunbookRepository,
    private val operationRepository: OperationRepository,
    private val counterRepository: CounterRepository,
    private val personRepository: PersonRepository,
    private val taskAttachmentRepository: TaskAttachmentRepository,
    private val weatherRepository: WeatherRepository,
    private val weekRepository: WeekRepository,
    private val aspectRepository: AspectRepository,
    private val notificationRepository: NotificationRepository,
    private val timerController: TimerController
) : ViewModel() {

    // Write paths delegate to the connection service layer (same services the /v1/LifeOps/local/*
    // routes call), so this screen and the connection functions behave identically.
    private val noteService = com.lifeops.app.connection.service.NoteService(taskNoteRepository)
    private val timeEntryService =
        com.lifeops.app.connection.service.TimeEntryService(timeEntryRepository, taskRepository)
    private val costService = com.lifeops.app.connection.service.CostService(costResourceRepository)
    private val runbookService = com.lifeops.app.connection.service.RunbookService(runbookRepository)
    private val operationService = com.lifeops.app.connection.service.OperationService(operationRepository)
    private val personService = com.lifeops.app.connection.service.PersonService(personRepository)
    private val counterService = com.lifeops.app.connection.service.CounterService(counterRepository)

    private val _uiState = MutableStateFlow(TaskDetailUiState())
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    val activeTimer = timerController.activeTimer
    val timerElapsedSeconds = timerController.elapsedSeconds

    /** End date of the current open week, cached for the queued/pending transition on edit. */
    private var currentWeekEndDate: String? = null

    init {
        viewModelScope.launch {
            val current = weekRepository.getCurrentWeek()
            currentWeekEndDate = current?.endDate
            val first = taskRepository.getById(taskId)
            if (first == null) {
                _uiState.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            val editable = current?.id == first.weekId
            val weekLabel = weekRepository.getAllWeeksSync()
                .firstOrNull { it.id == first.weekId }
                ?.let { "Week of ${DateUtil.formatDate(it.startDate)}" }

            // Ancestor notes (carried-forward lineage) — computed once; they don't change here.
            val lineage = taskRepository.getLineageIds(taskId).filter { it != taskId }
            val ancestorNotes = if (lineage.isEmpty()) emptyList()
                else taskNoteRepository.getByTaskIds(lineage)

            _uiState.update {
                it.copy(
                    loading = false,
                    editable = editable,
                    weekLabel = weekLabel,
                    ancestorNotes = ancestorNotes
                )
            }
            _uiState.update { it.copy(runbooks = runbookRepository.getAllRunbooksWithSteps()) }
        }

        // The task itself, streamed so status/title/assignment edits reflect immediately.
        viewModelScope.launch {
            taskRepository.observeById(taskId).collect { task ->
                _uiState.update { it.copy(task = task, notFound = task == null && !it.loading) }
            }
        }
        viewModelScope.launch {
            taskNoteRepository.observeByTask(taskId).collect { notes ->
                _uiState.update { it.copy(notes = notes) }
            }
        }
        viewModelScope.launch {
            timeEntryRepository.observeByTask(taskId).collect { entries ->
                _uiState.update { it.copy(totalTimeMinutes = entries.sumOf { e -> e.durationMinutes }) }
            }
        }
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
            costResourceRepository.observeActiveResources().collect { resources ->
                _uiState.update { it.copy(costResources = resources) }
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
        viewModelScope.launch {
            operationRepository.observeActive().collect { operations ->
                _uiState.update { it.copy(operations = operations) }
            }
        }
        viewModelScope.launch {
            counterRepository.observeActive().collect { counters ->
                _uiState.update { it.copy(counters = counters) }
            }
        }
        // Involved people (combine roster with this task's links so chips stay named historically).
        viewModelScope.launch {
            combine(
                personRepository.observeAll(),
                personRepository.observePersonIdsForTask(taskId)
            ) { people, ids -> people to people.filter { it.id in ids.toSet() } }
                .collect { (all, involved) ->
                    _uiState.update { it.copy(allPeople = all, involvedPeople = involved) }
                }
        }
        viewModelScope.launch {
            combine(
                aspectRepository.observeAllAspects(),
                aspectRepository.observeCategories()
            ) { aspects, categories -> aspects to categories }
                .collect { (aspects, categories) ->
                    _uiState.update {
                        it.copy(
                            aspects = aspects.filter { a -> !a.isArchived },
                            categories = categories.associateBy { c -> c.id }
                        )
                    }
                }
        }
        // Counter weekly totals for the current week (the counter section shows one while editable).
        viewModelScope.launch {
            val weekKey = DateUtil.weekIndexFor(DateUtil.currentWeekStart())
            counterRepository.observeWeeklyTotalsByCounter(weekKey).collect { totals ->
                _uiState.update { it.copy(counterWeeklyTotals = totals) }
            }
        }
    }

    // --- Timer (shared controller) ---
    fun startTimer(isPomodoro: Boolean = false) = timerController.start(taskId, isPomodoro)
    fun stopTimer() = timerController.stop(saveEntry = true)

    // --- Task operations (current-week / editable only) ---
    fun onAssignOperation(operationId: String?) {
        viewModelScope.launch {
            val task = taskRepository.getById(taskId) ?: return@launch
            taskRepository.updateTask(task.copy(operationId = operationId))
        }
    }

    fun onAddNote(content: String) {
        viewModelScope.launch { noteService.add(taskId, content) }
    }

    fun onLogTime(minutes: Int, note: String?) {
        viewModelScope.launch { timeEntryService.log(taskId, minutes, note) }
    }

    fun onLogCost(resourceId: String, amount: Int, note: String?) {
        viewModelScope.launch { costService.logCost(taskId, resourceId, amount, note) }
    }

    fun onDeleteCostEntry(id: String) {
        viewModelScope.launch { costService.deleteEntry(id) }
    }

    /** Mark (or unmark) this task as part of the week's commitment. See [TaskRepository.setCommitment]. */
    fun onToggleCommitment() {
        viewModelScope.launch {
            val task = taskRepository.getById(taskId) ?: return@launch
            taskRepository.setCommitment(taskId, !task.isCommitment)
        }
    }

    fun onToggleSubtask(subtaskId: String, checked: Boolean) {
        viewModelScope.launch { runbookService.setSubtaskChecked(subtaskId, checked) }
    }

    fun onAttachRunbook(runbookId: String) {
        viewModelScope.launch { runbookService.stamp(taskId, runbookId) }
    }

    fun onDeleteSubtask(subtaskId: String) {
        viewModelScope.launch { runbookService.deleteSubtask(subtaskId) }
    }

    fun onAddAttachment(uri: Uri) {
        viewModelScope.launch { taskAttachmentRepository.addFromUri(appContext, taskId, uri) }
    }

    fun onDeleteAttachment(id: String) {
        viewModelScope.launch { taskAttachmentRepository.delete(id) }
    }

    fun attachPerson(personId: String) {
        viewModelScope.launch { personService.attach(taskId, personId) }
    }

    fun detachPerson(personId: String) {
        viewModelScope.launch { personService.detach(taskId, personId) }
    }

    fun logCounter(counterId: String) {
        viewModelScope.launch { counterService.log(counterId) }
    }

    fun onCarryForward(reason: CarryForwardReason) {
        viewModelScope.launch {
            val task = taskRepository.getById(taskId) ?: return@launch
            taskRepository.carryForward(task, reason)
        }
    }

    fun onUnCarryForward() {
        viewModelScope.launch { taskRepository.unCarryForward(taskId) }
    }

    fun onUnsuccess() {
        viewModelScope.launch { taskRepository.unsuccessTask(taskId) }
    }

    fun onUnUnsuccess() {
        viewModelScope.launch { taskRepository.unUnsuccessTask(taskId) }
    }

    fun onPromoteToOperation() {
        viewModelScope.launch {
            val task = taskRepository.getById(taskId) ?: return@launch
            val operationId = UUID.randomUUID().toString()
            operationService.create(title = task.title, aspectId = task.aspectId, id = operationId)
            taskRepository.promoteTaskToOperation(taskId, operationId)
        }
    }

    fun onCreateOperation(id: String, title: String, aspectId: String?) {
        viewModelScope.launch { operationService.create(title = title, aspectId = aspectId, id = id) }
    }

    // --- Edit dialog ---
    fun startEdit() = _uiState.update { it.copy(editingTask = it.task) }
    fun cancelEdit() = _uiState.update { it.copy(editingTask = null) }

    fun saveEdit(
        title: String,
        priority: Priority,
        dueDate: String?,
        hardDeadline: Boolean,
        isRecurring: Boolean,
        estimatedMinutes: Int?,
        aspectId: String?,
        categoryId: String?,
        operationId: String?,
        counterId: String?,
        recurrenceIntervalWeeks: Int,
        recurrenceDayOfMonth: Int?
    ) {
        val task = _uiState.value.editingTask ?: return
        viewModelScope.launch {
            val validatedDueDate = dueDate?.takeIf { DateUtil.isValidDate(it) }
            val newResourceValue = ImportParser.computeResourceValue(
                priority.label, hardDeadline, estimatedMinutes, task.isManuallyAdded
            )
            val weekEnd = currentWeekEndDate
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
                operationId = operationId,
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
}

class TaskDetailViewModelFactory(
    private val appContext: Context,
    private val taskId: String,
    private val taskRepository: TaskRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val costResourceRepository: CostResourceRepository,
    private val runbookRepository: RunbookRepository,
    private val operationRepository: OperationRepository,
    private val counterRepository: CounterRepository,
    private val personRepository: PersonRepository,
    private val taskAttachmentRepository: TaskAttachmentRepository,
    private val weatherRepository: WeatherRepository,
    private val weekRepository: WeekRepository,
    private val aspectRepository: AspectRepository,
    private val notificationRepository: NotificationRepository,
    private val timerController: TimerController
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TaskDetailViewModel(
            appContext, taskId, taskRepository, taskNoteRepository, timeEntryRepository,
            costResourceRepository, runbookRepository, operationRepository, counterRepository,
            personRepository, taskAttachmentRepository, weatherRepository, weekRepository,
            aspectRepository, notificationRepository, timerController
        ) as T
}
