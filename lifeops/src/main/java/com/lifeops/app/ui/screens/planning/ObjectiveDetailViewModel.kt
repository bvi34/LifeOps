package com.lifeops.app.ui.screens.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.ObjectiveNote
import com.lifeops.app.data.model.ObjectiveWithSteps
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskNote
import com.lifeops.app.data.repository.AspectRepository
import com.lifeops.app.data.repository.ObjectiveRepository
import com.lifeops.app.data.repository.TaskNoteRepository
import com.lifeops.app.data.repository.TimeEntryRepository
import com.lifeops.app.data.repository.WeekRepository
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One step's share of the objective's work: its week tasks, their time and their notes. */
data class StepWork(
    /** Every task that has been the week's work on this step, newest week first. */
    val tasks: List<Task> = emptyList(),
    val minutes: Int = 0,
    val noteCount: Int = 0
) {
    /** The task to open for this step: this week's, else the latest one. */
    fun taskToOpen(currentWeekId: String?): Task? =
        tasks.firstOrNull { it.weekId == currentWeekId } ?: tasks.firstOrNull()
}

/** A note on the objective's timeline: on the objective itself ([stepTitle] null) or on a step's task. */
data class ObjectiveTimelineNote(
    val id: String,
    val content: String,
    val createdAt: String,
    val stepTitle: String?,
    /** Set for a note on the objective itself — only those are deleted from here. */
    val objectiveNoteId: String?
)

data class ObjectiveDetailUiState(
    val item: ObjectiveWithSteps? = null,
    val aspect: Aspect? = null,
    val workByStep: Map<String, StepWork> = emptyMap(),
    val totalMinutes: Int = 0,
    val timeline: List<ObjectiveTimelineNote> = emptyList(),
    val currentWeekId: String? = null,
    val today: String = DateUtil.todayKey(),
    val isLoading: Boolean = true
)

/**
 * One objective, the way an operation's detail shows an operation: what's been put into it. The
 * work happens on tasks — each step gets one in the weeks it's worked on — so a step's notes,
 * photos and time live on those tasks and are rolled up here, beside notes on the objective itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObjectiveDetailViewModel(
    private val objectiveId: String,
    private val objectiveRepository: ObjectiveRepository,
    aspectRepository: AspectRepository,
    weekRepository: WeekRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val taskNoteRepository: TaskNoteRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ObjectiveDetailUiState())
    val uiState: StateFlow<ObjectiveDetailUiState> = _uiState.asStateFlow()

    // Time and task notes are read, not observed (their tables aren't part of the flows below), so
    // the screen bumps this when it comes back into view — after logging time on a step's task.
    private val refreshTick = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            val item = objectiveRepository.observeAll()
                .map { list -> list.firstOrNull { it.objective.id == objectiveId } }
                .distinctUntilChanged()
            item.flatMapLatest { current ->
                val stepIds = current?.steps?.map { it.id }.orEmpty()
                combine(
                    objectiveRepository.observeStepTasks(stepIds),
                    objectiveRepository.observeNotes(objectiveId),
                    aspectRepository.observeAllAspects(),
                    weekRepository.observeCurrentWeek(),
                    refreshTick
                ) { tasks, notes, aspects, week, _ ->
                    Inputs(current, tasks, notes, aspects.firstOrNull { it.id == current?.objective?.aspectId }, week?.id)
                }
            }.collectLatest { load(it) }
        }
    }

    private data class Inputs(
        val item: ObjectiveWithSteps?,
        val tasks: List<Task>,
        val notes: List<ObjectiveNote>,
        val aspect: Aspect?,
        val currentWeekId: String?
    )

    private suspend fun load(inputs: Inputs) {
        val item = inputs.item
        if (item == null) {
            _uiState.update { it.copy(item = null, isLoading = false) }
            return
        }
        val minutesByTask = inputs.tasks.associate { t ->
            t.id to timeEntryRepository.getByTask(t.id).sumOf { it.durationMinutes }
        }
        val taskNotes: List<TaskNote> =
            if (inputs.tasks.isEmpty()) emptyList()
            else taskNoteRepository.getByTaskIds(inputs.tasks.map { it.id })
        val notesByTask = taskNotes.groupBy { it.taskId }
        val stepTitle = item.steps.associate { it.id to it.title }

        val workByStep = inputs.tasks
            .groupBy { it.objectiveStepId.orEmpty() }
            .mapValues { (_, tasks) ->
                StepWork(
                    tasks = tasks.sortedByDescending { it.createdAt },
                    minutes = tasks.sumOf { minutesByTask[it.id] ?: 0 },
                    noteCount = tasks.sumOf { notesByTask[it.id]?.size ?: 0 }
                )
            }

        val stepOfTask = inputs.tasks.associate { it.id to it.objectiveStepId }
        val timeline = (
            inputs.notes.map { ObjectiveTimelineNote(it.id, it.content, it.createdAt, null, it.id) } +
                taskNotes.map { n ->
                    ObjectiveTimelineNote(n.id, n.content, n.createdAt, stepOfTask[n.taskId]?.let { stepTitle[it] }, null)
                }
            ).sortedByDescending { it.createdAt }

        _uiState.update {
            it.copy(
                item = item,
                aspect = inputs.aspect,
                workByStep = workByStep,
                totalMinutes = minutesByTask.values.sum(),
                timeline = timeline,
                currentWeekId = inputs.currentWeekId,
                today = DateUtil.todayKey(),
                isLoading = false
            )
        }
    }

    fun refresh() = refreshTick.update { it + 1 }

    fun addNote(content: String) {
        viewModelScope.launch { objectiveRepository.addNote(objectiveId, content) }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch { objectiveRepository.deleteNote(noteId) }
    }
}

class ObjectiveDetailViewModelFactory(
    private val objectiveId: String,
    private val objectiveRepository: ObjectiveRepository,
    private val aspectRepository: AspectRepository,
    private val weekRepository: WeekRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val taskNoteRepository: TaskNoteRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ObjectiveDetailViewModel(
            objectiveId, objectiveRepository, aspectRepository, weekRepository,
            timeEntryRepository, taskNoteRepository
        ) as T
}
