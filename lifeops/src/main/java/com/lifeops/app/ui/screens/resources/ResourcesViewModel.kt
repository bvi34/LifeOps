package com.lifeops.app.ui.screens.resources

import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import com.lifeops.app.util.ImportParser
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

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
    private val weekRepository: WeekRepository,
    private val timeEntryRepository: TimeEntryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResourcesUiState())
    val uiState: StateFlow<ResourcesUiState> = _uiState.asStateFlow()

    init {
        // Core resource/mapping/aspect data combined with per-week task+time observations
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
            }.collectLatest { outer ->
                val week = outer.week
                if (week != null) {
                    // Observe tasks and time entries together so the preview updates
                    // whenever a task is completed or time is logged — not just at week close.
                    combine(
                        taskRepository.observeTasksForWeek(week.id),
                        timeEntryRepository.observeByWeek(week.id)
                    ) { tasks, timeEntries ->
                        val timeByTask = timeEntries
                            .groupBy { it.taskId }
                            .mapValues { (_, e) -> e.sumOf { it.durationMinutes } }
                        val earned = mutableMapOf<String, Int>()
                        tasks.filter { it.status == TaskStatus.COMPLETED }.forEach { task ->
                            val multiplier = accuracyMultiplier(task, timeByTask[task.id])
                            val pts = (task.resourceValue * multiplier).roundToInt()
                            task.aspectId?.let { earned[it] = (earned[it] ?: 0) + pts }
                        }
                        earned as Map<String, Int>
                    }.collectLatest { earnedThisWeek ->
                        _uiState.update {
                            it.copy(
                                gameResources = outer.resources,
                                mappings = outer.mappings,
                                aspects = outer.aspects,
                                aspectEarnedThisWeek = earnedThisWeek
                            )
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            gameResources = outer.resources,
                            mappings = outer.mappings,
                            aspects = outer.aspects,
                            aspectEarnedThisWeek = emptyMap()
                        )
                    }
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
    private val weekRepository: WeekRepository,
    private val timeEntryRepository: TimeEntryRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ResourcesViewModel(gameResourceRepository, aspectRepository, taskRepository, weekRepository, timeEntryRepository) as T
}

private fun accuracyMultiplier(task: Task, actualMinutes: Int?): Double {
    val estimated = task.estimatedMinutes ?: return 1.0
    val actual = actualMinutes?.takeIf { it > 0 } ?: return 1.0
    return when {
        abs(actual - estimated) <= 15 -> 2.0
        actual < estimated -> 0.9
        else -> 0.75
    }
}
