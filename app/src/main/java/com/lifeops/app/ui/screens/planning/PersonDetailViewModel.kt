package com.lifeops.app.ui.screens.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.PersonNote
import com.lifeops.app.data.model.SunSensitivity
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.repository.PersonRepository
import com.lifeops.app.data.repository.TaskRepository
import com.lifeops.app.data.repository.WeekRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonDetailUiState(
    val person: Person? = null,
    val notes: List<PersonNote> = emptyList(),
    val involvedTasks: List<Task> = emptyList(),
    /** Current-week tasks, the pool the "attach a task" picker draws from. */
    val weekTasks: List<Task> = emptyList()
)

class PersonDetailViewModel(
    private val personId: String,
    private val personRepository: PersonRepository,
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonDetailUiState())
    val uiState: StateFlow<PersonDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            personRepository.observePerson(personId).collectLatest { person ->
                _uiState.update { it.copy(person = person) }
            }
        }
        viewModelScope.launch {
            personRepository.observeNotes(personId).collectLatest { notes ->
                _uiState.update { it.copy(notes = notes) }
            }
        }
        viewModelScope.launch {
            personRepository.observeTasksForPerson(personId).collectLatest { tasks ->
                _uiState.update { it.copy(involvedTasks = tasks) }
            }
        }
        viewModelScope.launch {
            val week = weekRepository.getOrCreateCurrentWeek()
            taskRepository.observeTasksForWeek(week.id).collectLatest { tasks ->
                _uiState.update { it.copy(weekTasks = tasks) }
            }
        }
    }

    /** Save the full profile in one shot from the editor. Blank name is ignored. */
    fun saveProfile(
        name: String,
        heatToleranceMaxF: Int?,
        coldToleranceMinF: Int?,
        uvMax: Int?,
        windMaxMph: Int?,
        maxPrecipitationPct: Int?,
        sunSensitivity: SunSensitivity,
        activityPreferences: String?
    ) {
        val person = _uiState.value.person ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            personRepository.update(
                person.copy(
                    name = name.trim(),
                    heatToleranceMaxF = heatToleranceMaxF,
                    coldToleranceMinF = coldToleranceMinF,
                    uvMax = uvMax,
                    windMaxMph = windMaxMph,
                    maxPrecipitationPct = maxPrecipitationPct,
                    sunSensitivity = sunSensitivity,
                    activityPreferences = activityPreferences?.trim()?.ifBlank { null }
                )
            )
        }
    }

    fun addNote(content: String) {
        viewModelScope.launch { personRepository.addNote(personId, content) }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch { personRepository.deleteNote(noteId) }
    }

    fun attachTask(taskId: String) {
        viewModelScope.launch { personRepository.attach(taskId, personId) }
    }

    fun detachTask(taskId: String) {
        viewModelScope.launch { personRepository.detach(taskId, personId) }
    }

    fun delete(onDeleted: () -> Unit) {
        val person = _uiState.value.person ?: return
        viewModelScope.launch {
            personRepository.delete(person)
            onDeleted()
        }
    }
}

class PersonDetailViewModelFactory(
    private val personId: String,
    private val personRepository: PersonRepository,
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PersonDetailViewModel(personId, personRepository, weekRepository, taskRepository) as T
}
