package com.lifeops.app.ui.screens.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.repository.PersonRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PeopleUiState(
    val people: List<Person> = emptyList(),
    val taskCounts: Map<String, Int> = emptyMap() // personId -> involved task count
)

class PeopleViewModel(
    private val personRepository: PersonRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PeopleUiState())
    val uiState: StateFlow<PeopleUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                personRepository.observeAll(),
                personRepository.observeTaskCounts()
            ) { people, counts ->
                PeopleUiState(people = people, taskCounts = counts)
            }.collect { _uiState.value = it }
        }
    }

    fun createPerson(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { personRepository.createPerson(name) }
    }

    fun setArchived(person: Person, archived: Boolean) {
        viewModelScope.launch { personRepository.setArchived(person, archived) }
    }

    fun delete(person: Person) {
        viewModelScope.launch { personRepository.delete(person) }
    }
}

class PeopleViewModelFactory(
    private val personRepository: PersonRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PeopleViewModel(personRepository) as T
}
