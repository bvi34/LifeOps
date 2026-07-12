package com.lifeops.app.ui.screens.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.FutureProject
import com.lifeops.app.data.model.FutureProjectNote
import com.lifeops.app.data.repository.FutureProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FutureProjectDetailUiState(
    val project: FutureProject? = null,
    val notes: List<FutureProjectNote> = emptyList()
)

class FutureProjectDetailViewModel(
    private val projectId: String,
    private val futureProjectRepository: FutureProjectRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(FutureProjectDetailUiState())
    val uiState: StateFlow<FutureProjectDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            futureProjectRepository.observeById(projectId).collectLatest { project ->
                _uiState.update { it.copy(project = project) }
            }
        }
        viewModelScope.launch {
            futureProjectRepository.observeNotes(projectId).collectLatest { notes ->
                _uiState.update { it.copy(notes = notes) }
            }
        }
    }

    fun saveTitle(title: String) {
        val project = _uiState.value.project ?: return
        viewModelScope.launch { futureProjectRepository.saveTitle(project, title) }
    }

    fun addNote(content: String) {
        viewModelScope.launch { futureProjectRepository.addNote(projectId, content) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            futureProjectRepository.delete(projectId)
            onDeleted()
        }
    }
}

class FutureProjectDetailViewModelFactory(
    private val projectId: String,
    private val futureProjectRepository: FutureProjectRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        FutureProjectDetailViewModel(projectId, futureProjectRepository) as T
}
