package com.lifeops.app.ui.screens.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.repository.FutureProjectListItem
import com.lifeops.app.data.repository.FutureProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FutureProjectListUiState(
    val projects: List<FutureProjectListItem> = emptyList(),
    val showCreateDialog: Boolean = false
)

class FutureProjectViewModel(private val futureProjectRepository: FutureProjectRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(FutureProjectListUiState())
    val uiState: StateFlow<FutureProjectListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            futureProjectRepository.observeAll().collectLatest { projects -> _uiState.update { it.copy(projects = projects) } }
        }
    }

    fun showCreateDialog() = _uiState.update { it.copy(showCreateDialog = true) }
    fun hideCreateDialog() = _uiState.update { it.copy(showCreateDialog = false) }

    fun createProject(title: String) {
        viewModelScope.launch {
            futureProjectRepository.create(title)
            _uiState.update { it.copy(showCreateDialog = false) }
        }
    }
}

class FutureProjectViewModelFactory(private val futureProjectRepository: FutureProjectRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = FutureProjectViewModel(futureProjectRepository) as T
}
