package com.lifeops.app.ui.screens.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.repository.BusyBlockRepository
import com.lifeops.app.data.repository.PersonRepository
import com.lifeops.app.util.PersonBookingStats
import com.lifeops.app.util.RelationshipAnalytics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PeopleUiState(
    val people: List<Person> = emptyList(),
    val taskCounts: Map<String, Int> = emptyMap(), // personId -> involved task count
    /** personId -> booking stats, for people with a relationship set (see RelationshipAnalytics). */
    val bookingStats: Map<String, PersonBookingStats> = emptyMap()
)

class PeopleViewModel(
    private val personRepository: PersonRepository,
    private val busyBlockRepository: BusyBlockRepository
) : ViewModel() {

    private val personService = com.lifeops.app.connection.service.PersonService(personRepository)

    private val _uiState = MutableStateFlow(PeopleUiState())
    val uiState: StateFlow<PeopleUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                personRepository.observeAll(),
                personRepository.observeTaskCounts(),
                busyBlockRepository.observeMine()
            ) { people, counts, blocks ->
                val trackedIds = people.filter { it.relationship != null }.map { it.id }
                PeopleUiState(
                    people = people,
                    taskCounts = counts,
                    bookingStats = RelationshipAnalytics.bookingStats(blocks, trackedIds)
                )
            }.collect { _uiState.value = it }
        }
    }

    fun createPerson(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { personService.create(name) }
    }

    fun setArchived(person: Person, archived: Boolean) {
        viewModelScope.launch { personService.setArchived(person.id, archived) }
    }

    fun delete(person: Person) {
        viewModelScope.launch { personService.delete(person.id) }
    }
}

class PeopleViewModelFactory(
    private val personRepository: PersonRepository,
    private val busyBlockRepository: BusyBlockRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PeopleViewModel(personRepository, busyBlockRepository) as T
}
