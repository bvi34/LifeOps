package com.lifeops.app.ui.screens.settings

import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class SettingsUiState(
    val aspects: List<Aspect> = emptyList(),
    val categories: Map<String, List<Category>> = emptyMap(),
    val gameResources: List<GameResource> = emptyList(),
    val expandedAspectId: String? = null,
    val editingAspect: Aspect? = null,
    val editingCategory: Category? = null,
    val showNewAspectDialog: Boolean = false,
    val showNewCategoryDialog: Boolean = false,
    val newAspectForCategoryId: String? = null,
    val defaultReminderHour: Int = 9,
    val showReminderTimePicker: Boolean = false
)

class SettingsViewModel(
    private val aspectRepository: AspectRepository,
    private val gameResourceRepository: GameResourceRepository,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                aspectRepository.observeAllAspects(),
                aspectRepository.observeCategories(),
                gameResourceRepository.observeResources()
            ) { aspects, categories, resources -> Triple(aspects, categories, resources) }
                .collectLatest { (aspects, categories, resources) ->
                    val catsByAspect = categories.groupBy { it.aspectId }
                    _uiState.update {
                        it.copy(
                            aspects = aspects,
                            categories = catsByAspect,
                            gameResources = resources,
                            defaultReminderHour = preferencesRepository.defaultReminderHour
                        )
                    }
                }
        }
    }

    fun addAspect(name: String, color: String, icon: String) {
        viewModelScope.launch {
            val aspect = Aspect(UUID.randomUUID().toString(), name, color, icon)
            aspectRepository.upsertAspect(aspect)
        }
    }

    fun updateAspect(aspect: Aspect) {
        viewModelScope.launch { aspectRepository.upsertAspect(aspect) }
    }

    fun archiveAspect(id: String, archive: Boolean) {
        viewModelScope.launch { aspectRepository.setAspectArchived(id, archive) }
    }

    fun addCategory(aspectId: String, name: String) {
        viewModelScope.launch {
            val category = Category(UUID.randomUUID().toString(), aspectId, name)
            aspectRepository.upsertCategory(category)
        }
    }

    fun archiveCategory(id: String, archive: Boolean) {
        viewModelScope.launch { aspectRepository.setCategoryArchived(id, archive) }
    }

    fun renameGameResource(resource: GameResource, newName: String) {
        viewModelScope.launch {
            gameResourceRepository.upsertResource(resource.copy(name = newName))
        }
    }

    fun toggleAspectExpanded(id: String) {
        _uiState.update {
            it.copy(expandedAspectId = if (it.expandedAspectId == id) null else id)
        }
    }

    fun setDefaultReminderHour(hour: Int) {
        preferencesRepository.defaultReminderHour = hour
        _uiState.update { it.copy(defaultReminderHour = hour) }
    }

    fun showReminderTimePicker() = _uiState.update { it.copy(showReminderTimePicker = true) }
    fun hideReminderTimePicker() = _uiState.update { it.copy(showReminderTimePicker = false) }

    fun showNewAspectDialog() = _uiState.update { it.copy(showNewAspectDialog = true) }
    fun hideNewAspectDialog() = _uiState.update { it.copy(showNewAspectDialog = false) }
    fun showNewCategoryDialog(aspectId: String) = _uiState.update {
        it.copy(showNewCategoryDialog = true, newAspectForCategoryId = aspectId)
    }
    fun hideNewCategoryDialog() = _uiState.update { it.copy(showNewCategoryDialog = false, newAspectForCategoryId = null) }
}

class SettingsViewModelFactory(
    private val aspectRepository: AspectRepository,
    private val gameResourceRepository: GameResourceRepository,
    private val preferencesRepository: PreferencesRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(aspectRepository, gameResourceRepository, preferencesRepository) as T
}
