package com.lifeops.app.ui.screens.resources

import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class ResourcesUiState(
    val gameResources: List<GameResource> = emptyList(),
    val mappings: List<GameResourceMapping> = emptyList(),
    val aspects: List<Aspect> = emptyList(),
    val aspectEarnedThisWeek: Map<String, Int> = emptyMap(),
    val aspectLifetimeEarned: Map<String, Int> = emptyMap(),
    val transactions: Map<String, List<ResourceTransaction>> = emptyMap(),
    val editingMappingResourceId: String? = null,
    val spendingResourceId: String? = null
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
                aspectRepository.observeAspects(),
                weekRepository.observeCurrentWeek()
            ) { resources, mappings, aspects, week ->
                object {
                    val resources = resources
                    val mappings = mappings
                    val aspects = aspects
                    val week = week
                }
            }.collectLatest { state ->
                val earnedThisWeek = state.week?.let { week ->
                    taskRepository.getEarnedThisWeekByAspect(week.id)
                } ?: emptyMap()
                _uiState.update {
                    it.copy(
                        gameResources = state.resources,
                        mappings = state.mappings,
                        aspects = state.aspects,
                        aspectEarnedThisWeek = earnedThisWeek
                    )
                }
            }
        }
        // Lifetime per aspect: aggregate aspectBreakdown across all closed week snapshots
        viewModelScope.launch {
            weekRepository.observeSnapshots().collectLatest { snapshots ->
                val lifetime = mutableMapOf<String, Int>()
                snapshots.forEach { snap ->
                    snap.aspectBreakdown.forEach { (k, v) -> lifetime[k] = (lifetime[k] ?: 0) + v }
                }
                _uiState.update { it.copy(aspectLifetimeEarned = lifetime) }
            }
        }
        // Observe all resource transactions
        viewModelScope.launch {
            gameResourceRepository.observeAllTransactions().collectLatest { all ->
                _uiState.update { it.copy(transactions = all.groupBy { tx -> tx.resourceId }) }
            }
        }
    }

    fun onAddOrUpdateMapping(gameResourceId: String, aspectId: String, weight: Float) {
        viewModelScope.launch {
            val mapping = GameResourceMapping(
                id = UUID.randomUUID().toString(),
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

    fun showSpendDialog(resourceId: String) = _uiState.update { it.copy(spendingResourceId = resourceId) }
    fun hideSpendDialog() = _uiState.update { it.copy(spendingResourceId = null) }

    fun onSpendResource(resourceId: String, amount: Int, note: String?) {
        viewModelScope.launch {
            gameResourceRepository.spendResource(resourceId, amount, note)
            _uiState.update { it.copy(spendingResourceId = null) }
        }
    }

    fun computeResourceEarnedThisWeek(gameResourceId: String): Int {
        val state = _uiState.value
        return state.mappings
            .filter { it.gameResourceId == gameResourceId }
            .sumOf { mapping ->
                val aspectEarned = state.aspectEarnedThisWeek[mapping.aspectId] ?: 0
                (aspectEarned * mapping.weight).toInt()
            }
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
