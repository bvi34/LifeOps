package com.lifeops.app.ui.screens.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.repository.BusyBlockRepository
import com.lifeops.app.data.repository.PersonRepository
import com.lifeops.app.data.repository.TaskRepository
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.RelationshipAnalytics
import com.lifeops.app.util.RelationshipImbalance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class CalendarUiState(
    val weekStart: LocalDate = DateUtil.currentWeekStart(),
    /** The user's own busy blocks (personId == null). */
    val blocks: List<BusyBlock> = emptyList(),
    /** Tasks that carry a due date, for overlaying the week as all-day items. */
    val dueTasks: List<Task> = emptyList(),
    /** The household roster, for tagging people onto an event. */
    val allPeople: List<Person> = emptyList(),
    /** Relationship-balance nudges (see RelationshipAnalytics), most-overdue first. */
    val imbalances: List<RelationshipImbalance> = emptyList()
)

class CalendarViewModel(
    private val busyBlockRepository: BusyBlockRepository,
    private val taskRepository: TaskRepository,
    private val personRepository: PersonRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(busyBlockRepository.observeMine(), personRepository.observeAll()) { blocks, people ->
                blocks to people.filter { !it.isArchived }
            }.collectLatest { (blocks, people) ->
                _uiState.update {
                    it.copy(
                        blocks = blocks,
                        allPeople = people,
                        imbalances = RelationshipAnalytics.findImbalances(people, blocks)
                    )
                }
            }
        }
        refreshDueTasks()
    }

    // Due-dated tasks aren't behind a single cross-week flow, so load them on demand: at start and
    // whenever the visible week changes. Read-only on the calendar, so a snapshot is enough.
    private fun refreshDueTasks() {
        viewModelScope.launch {
            val due = taskRepository.getAllTasks().filter { !it.dueDate.isNullOrBlank() }
            _uiState.update { it.copy(dueTasks = due) }
        }
    }

    fun previousWeek() {
        _uiState.update { it.copy(weekStart = it.weekStart.minusWeeks(1)) }
        refreshDueTasks()
    }

    fun nextWeek() {
        _uiState.update { it.copy(weekStart = it.weekStart.plusWeeks(1)) }
        refreshDueTasks()
    }

    fun thisWeek() {
        _uiState.update { it.copy(weekStart = DateUtil.currentWeekStart()) }
        refreshDueTasks()
    }

    fun saveBlock(block: BusyBlock) {
        viewModelScope.launch { busyBlockRepository.upsert(block) }
    }

    fun deleteBlock(id: String) {
        viewModelScope.launch { busyBlockRepository.delete(id) }
    }
}

class CalendarViewModelFactory(
    private val busyBlockRepository: BusyBlockRepository,
    private val taskRepository: TaskRepository,
    private val personRepository: PersonRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CalendarViewModel(busyBlockRepository, taskRepository, personRepository) as T
}
