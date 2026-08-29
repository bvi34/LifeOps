package com.lifeops.app.ui.screens.operationdetail

import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class OperationDetailUiState(
    val operation: Operation? = null,
    val tasks: List<Task> = emptyList(),
    val tasksByWeek: Map<Week, List<Task>> = emptyMap(),
    val aspects: Map<String, Aspect> = emptyMap(),
    val notes: List<Pair<String, TaskNote>> = emptyList(), // Pair(taskTitle, note)
    // Notes carried over from the future operation this one was promoted from, if any.
    val brainstormNotes: List<FutureOperationNote> = emptyList(),
    val totalTimeMinutes: Int = 0,
    val totalPoints: Int = 0,
    val isLoading: Boolean = true
)

class OperationDetailViewModel(
    private val operationId: String,
    private val operationRepository: OperationRepository,
    private val taskRepository: TaskRepository,
    private val weekRepository: WeekRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val aspectRepository: AspectRepository,
    private val futureOperationRepository: FutureOperationRepository
) : ViewModel() {

    private val operationService = com.lifeops.app.connection.service.OperationService(operationRepository)

    private val _uiState = MutableStateFlow(OperationDetailUiState())
    val uiState: StateFlow<OperationDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val operation = operationRepository.getOperationById(operationId) ?: run {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        val allTasks = taskRepository.getAllTasks().filter { it.operationId == operationId }
        val aspects = aspectRepository.getAllAspectsSync()
        val allWeeks = weekRepository.getAllWeeksSync()
        val weekById = allWeeks.associateBy { it.id }

        val taskIds = allTasks.map { it.id }.toSet()
        val allTime = timeEntryRepository.getAllSince("1970-01-01T00:00:00Z")
            .filter { it.taskId in taskIds }
        val totalMins = allTime.sumOf { it.durationMinutes }

        val totalPoints = allTasks.filter { it.status == TaskStatus.COMPLETED }.sumOf { it.resourceValue }

        // Unified notes timeline: all notes across all tasks, sorted by createdAt
        val allNotes = mutableListOf<Pair<String, TaskNote>>()
        for (task in allTasks) {
            val notes = taskNoteRepository.getByTask(task.id)
            notes.forEach { note -> allNotes.add(Pair(task.title, note)) }
        }
        allNotes.sortBy { it.second.createdAt }

        val brainstormNotes = operation.sourceFutureOperationId
            ?.let { futureOperationRepository.getNotes(it) }
            .orEmpty()

        // Group tasks by week, newest first
        val tasksByWeek = allTasks.groupBy { task -> weekById[task.weekId] }
            .filterKeys { it != null }
            .mapKeys { it.key!! }
            .entries
            .sortedByDescending { it.key.startDate }
            .associate { it.key to it.value }

        _uiState.update {
            it.copy(
                operation = operation,
                tasks = allTasks,
                tasksByWeek = tasksByWeek,
                aspects = aspects.associateBy { a -> a.id },
                notes = allNotes,
                brainstormNotes = brainstormNotes,
                totalTimeMinutes = totalMins,
                totalPoints = totalPoints,
                isLoading = false
            )
        }
    }

    fun setOperationStatus(status: OperationStatus) {
        viewModelScope.launch {
            operationService.setStatus(operationId, status)
            load()
        }
    }
}

class OperationDetailViewModelFactory(
    private val operationId: String,
    private val operationRepository: OperationRepository,
    private val taskRepository: TaskRepository,
    private val weekRepository: WeekRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val aspectRepository: AspectRepository,
    private val futureOperationRepository: FutureOperationRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        OperationDetailViewModel(
            operationId, operationRepository, taskRepository, weekRepository,
            timeEntryRepository, taskNoteRepository, aspectRepository, futureOperationRepository
        ) as T
}
