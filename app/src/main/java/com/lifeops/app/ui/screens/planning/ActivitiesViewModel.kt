package com.lifeops.app.ui.screens.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.ActivityTemplate
import com.lifeops.app.data.repository.ActivityTemplateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ActivitiesUiState(
    val templates: List<ActivityTemplate> = emptyList()
)

class ActivitiesViewModel(
    private val repository: ActivityTemplateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActivitiesUiState())
    val uiState: StateFlow<ActivitiesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { templates ->
                _uiState.value = ActivitiesUiState(templates)
            }
        }
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
            repository.create(name, outdoorPreferred, durationMinutes, maxTempF, minTempF, avoidRain, maxWindMph)
        }
    }

    /** Save an edit to an existing (built-in or custom) template. */
    fun save(template: ActivityTemplate) {
        if (template.name.isBlank()) return
        viewModelScope.launch { repository.update(template) }
    }

    fun delete(template: ActivityTemplate) {
        viewModelScope.launch { repository.delete(template) }
    }
}

class ActivitiesViewModelFactory(
    private val repository: ActivityTemplateRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ActivitiesViewModel(repository) as T
}
