package com.lifeops.app.ui.screens.milestones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Milestone
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.repository.AspectRepository
import com.lifeops.app.data.repository.MilestoneRepository
import com.lifeops.app.data.repository.PersonRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MilestonesUiState(
    val milestones: List<Milestone> = emptyList(),
    val aspects: List<Aspect> = emptyList(),
    val people: List<Person> = emptyList()
)

class MilestonesViewModel(
    private val milestoneRepository: MilestoneRepository,
    aspectRepository: AspectRepository,
    personRepository: PersonRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MilestonesUiState())
    val uiState: StateFlow<MilestonesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                milestoneRepository.observeAll(),
                aspectRepository.observeAspects(),
                personRepository.observeAll()
            ) { milestones, aspects, people ->
                MilestonesUiState(
                    milestones = milestones,
                    aspects = aspects,
                    // Archived people can still hold historic milestones, but only active ones
                    // are offered when recording a new milestone.
                    people = people.filter { !it.isArchived }
                )
            }.collectLatest { state -> _uiState.update { state } }
        }
    }

    fun create(
        title: String,
        description: String?,
        points: Int,
        aspectId: String?,
        personId: String?,
        achievedAt: String?
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            milestoneRepository.create(title, description, points, aspectId, personId, achievedAt)
        }
    }

    fun delete(milestone: Milestone) {
        viewModelScope.launch { milestoneRepository.delete(milestone) }
    }
}

class MilestonesViewModelFactory(
    private val milestoneRepository: MilestoneRepository,
    private val aspectRepository: AspectRepository,
    private val personRepository: PersonRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MilestonesViewModel(milestoneRepository, aspectRepository, personRepository) as T
}
