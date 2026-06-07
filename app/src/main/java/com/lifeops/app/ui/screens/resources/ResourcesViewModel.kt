package com.lifeops.app.ui.screens.resources

import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ResourcesUiState(
    val gameResources: List<GameResource> = emptyList(),
    val mappings: List<GameResourceMapping> = emptyList(),
    val aspects: List<Aspect> = emptyList(),
    val aspectEarnedThisWeek: Map<String, Int> = emptyMap(),
    val aspectEarnedLifetime: Map<String, Int> = emptyMap(),
    val editingMappingResourceId: String? = null
)

class ResourcesViewModel(
    private val gameResourceRepository: GameResourceRepository,
    private val aspectRepository: AspectRepository,
    private val taskRepository: TaskRepository,
    private val weekRepository: WeekRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResourcesUiState())
    val uiState: StateFlow<ResourcesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                gameResourceRepository.observeResources(),
                gameResourceRepository.observeMappings(),
                aspectRepository.observeAspects()
            ) { resources, mappings, aspects -> Triple(resources, mappings, aspects) }
                .collectLatest { (resources, mappings, aspects) ->
                    _uiState.update {
                        it.copy(
                            gameResources = resources,
                            mappings = mappings,
                            aspects = aspects
                        )
                    }
                }
        }
    }

    fun onAddOrUpdateMapping(gameResourceId: String, aspectId: String, weight: Float) {
        viewModelScope.launch {
            val mapping = GameResourceMapping(
                id = java.util.UUID.randomUUID().toString(),
                gameResourceId = gameResourceId,
                aspectId = aspectId,
                weight = weight
            )
            gameResourceRepository.upsertMapping(mapping)
        }
    }

    fun onDeleteMapping(mapping: GameResourceMapping) {
        viewModelScope.launch { gameResourceRepository.deleteMapping(mapping) }
    }

    fun onRenameResource(resource: GameResource, newName: String) {
        viewModelScope.launch {
            gameResourceRepository.upsertResource(resource.copy(name = newName))
        }
    }

    fun setEditingResource(id: String?) {
        _uiState.update { it.copy(editingMappingResourceId = id) }
    }
}

class ResourcesViewModelFactory(
    private val gameResourceRepository: GameResourceRepository,
    private val aspectRepository: AspectRepository,
    private val taskRepository: TaskRepository,
    private val weekRepository: WeekRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ResourcesViewModel(gameResourceRepository, aspectRepository, taskRepository, weekRepository) as T
}
