package com.lifeops.app.ui.screens.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.FutureOperationStatus
import com.lifeops.app.data.repository.FutureOperationListItem
import com.lifeops.app.data.repository.FutureOperationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FutureOperationListUiState(
    val operations: List<FutureOperationListItem> = emptyList(),
    val showCreateDialog: Boolean = false
)

class FutureOperationViewModel(private val futureOperationRepository: FutureOperationRepository) : ViewModel() {
    private val futureOperationService =
        com.lifeops.app.connection.service.FutureOperationService(futureOperationRepository)

    private val _uiState = MutableStateFlow(FutureOperationListUiState())
    val uiState: StateFlow<FutureOperationListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            futureOperationRepository.observeAll().collectLatest { operations -> _uiState.update { it.copy(operations = operations) } }
        }
    }

    fun showCreateDialog() = _uiState.update { it.copy(showCreateDialog = true) }
    fun hideCreateDialog() = _uiState.update { it.copy(showCreateDialog = false) }

    fun createOperation(title: String) {
        viewModelScope.launch {
            futureOperationService.create(title)
            _uiState.update { it.copy(showCreateDialog = false) }
        }
    }

    fun setStatus(operationId: String, status: FutureOperationStatus) {
        viewModelScope.launch { futureOperationService.setStatus(operationId, status) }
    }
}

class FutureOperationViewModelFactory(private val futureOperationRepository: FutureOperationRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = FutureOperationViewModel(futureOperationRepository) as T
}
