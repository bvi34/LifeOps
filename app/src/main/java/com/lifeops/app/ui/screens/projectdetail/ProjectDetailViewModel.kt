package com.lifeops.app.ui.screens.projectdetail

import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ProjectDetailUiState(
    val project: Project? = null,
    val tasks: List<Task> = emptyList(),
    val tasksByWeek: Map<Week, List<Task>> = emptyMap(),
    val aspects: Map<String, Aspect> = emptyMap(),
    val notes: List<Pair<String, TaskNote>> = emptyList(), // Pair(taskTitle, note)
    val totalTimeMinutes: Int = 0,
    val totalPoints: Int = 0,
    val isLoading: Boolean = true
)

class ProjectDetailViewModel(
    private val projectId: String,
    private val projectRepository: ProjectRepository,
    private val taskRepository: TaskRepository,
    private val weekRepository: WeekRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val aspectRepository: AspectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectDetailUiState())
    val uiState: StateFlow<ProjectDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val project = projectRepository.getProjectById(projectId) ?: run {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        val allTasks = taskRepository.getAllTasks().filter { it.projectId == projectId }
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

        // Group tasks by week, newest first
        val tasksByWeek = allTasks.groupBy { task -> weekById[task.weekId] }
            .filterKeys { it != null }
            .mapKeys { it.key!! }
            .entries
            .sortedByDescending { it.key.startDate }
            .associate { it.key to it.value }

        _uiState.update {
            it.copy(
                project = project,
                tasks = allTasks,
                tasksByWeek = tasksByWeek,
                aspects = aspects.associateBy { a -> a.id },
                notes = allNotes,
                totalTimeMinutes = totalMins,
                totalPoints = totalPoints,
                isLoading = false
            )
        }
    }

    fun setProjectStatus(status: ProjectStatus) {
        viewModelScope.launch {
            projectRepository.setProjectStatus(projectId, status)
            load()
        }
    }
}

class ProjectDetailViewModelFactory(
    private val projectId: String,
    private val projectRepository: ProjectRepository,
    private val taskRepository: TaskRepository,
    private val weekRepository: WeekRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val aspectRepository: AspectRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ProjectDetailViewModel(
            projectId, projectRepository, taskRepository, weekRepository,
            timeEntryRepository, taskNoteRepository, aspectRepository
        ) as T
}
