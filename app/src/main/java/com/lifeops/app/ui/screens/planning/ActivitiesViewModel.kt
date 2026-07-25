package com.lifeops.app.ui.screens.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.ActivityTemplate
import com.lifeops.app.data.repository.ActivityTemplateRepository
import com.lifeops.app.util.PreferenceLearning
import com.lifeops.app.util.PreferenceSuggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ActivitiesUiState(
    val templates: List<ActivityTemplate> = emptyList(),
    val suggestions: List<PreferenceSuggestion> = emptyList()
)

class ActivitiesViewModel(
    private val repository: ActivityTemplateRepository
) : ViewModel() {

    private val activityService = com.lifeops.app.connection.service.ActivityService(repository)

    private val _uiState = MutableStateFlow(ActivitiesUiState())
    val uiState: StateFlow<ActivitiesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeAll(),
                repository.observeOverrides()
            ) { templates, overrides ->
                ActivitiesUiState(
                    templates = templates,
                    suggestions = PreferenceLearning.suggest(overrides, templates)
                )
            }.collect { _uiState.value = it }
        }
    }

    fun applySuggestion(suggestion: PreferenceSuggestion) {
        viewModelScope.launch { activityService.applySuggestion(suggestion) }
    }

    fun dismissSuggestion(suggestion: PreferenceSuggestion) {
        viewModelScope.launch { activityService.dismissSuggestion(suggestion.activityId, suggestion.field) }
    }

    fun create(
        name: String,
        outdoorPreferred: Boolean,
        durationMinutes: Int?,
        maxTempF: Int?,
        minTempF: Int?,
        avoidRain: Boolean,
        maxWindMph: Int?
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            activityService.create(name, outdoorPreferred, durationMinutes, maxTempF, minTempF, avoidRain, maxWindMph)
        }
    }

    /** Save an edit to an existing (built-in or custom) template. */
    fun save(template: ActivityTemplate) {
        if (template.name.isBlank()) return
        viewModelScope.launch { activityService.update(template) }
    }

    fun delete(template: ActivityTemplate) {
        viewModelScope.launch { activityService.delete(template.id) }
    }
}

class ActivitiesViewModelFactory(
    private val repository: ActivityTemplateRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ActivitiesViewModel(repository) as T
}
