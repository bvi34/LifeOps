package com.lifeops.app.ui.screens.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.FutureOperation
import com.lifeops.app.data.model.FutureOperationNote
import com.lifeops.app.data.model.FutureOperationStatus
import com.lifeops.app.data.repository.FutureOperationRepository
import com.lifeops.app.data.repository.OperationRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FutureOperationDetailUiState(
    val operation: FutureOperation? = null,
    val notes: List<FutureOperationNote> = emptyList()
)

class FutureOperationDetailViewModel(
    private val operationId: String,
    private val futureOperationRepository: FutureOperationRepository,
    private val operationRepository: OperationRepository
) : ViewModel() {
    private val futureOperationService =
        com.lifeops.app.connection.service.FutureOperationService(futureOperationRepository)

    private val _uiState = MutableStateFlow(FutureOperationDetailUiState())
    val uiState: StateFlow<FutureOperationDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            futureOperationRepository.observeById(operationId).collectLatest { operation ->
                _uiState.update { it.copy(operation = operation) }
            }
        }
        viewModelScope.launch {
            futureOperationRepository.observeNotes(operationId).collectLatest { notes ->
                _uiState.update { it.copy(notes = notes) }
            }
        }
    }

    fun saveTitle(title: String) {
        val operation = _uiState.value.operation ?: return
        viewModelScope.launch { futureOperationService.saveTitle(operation, title) }
    }

    fun addNote(content: String) {
        viewModelScope.launch { futureOperationService.addNote(operationId, content) }
    }

    fun setStatus(status: FutureOperationStatus) {
        viewModelScope.launch { futureOperationService.setStatus(operationId, status) }
    }

    /** Turn this idea into a real (current) operation, then archive it here. The notes stay
     *  on the archived future operation; the link lets the promoted operation's detail screen
     *  surface them as its brainstorm history. The operation-creation half is a cross-domain op
     *  (it stamps a sourceFutureOperationId link) and stays on OperationRepository. */
    fun promote() {
        val operation = _uiState.value.operation ?: return
        viewModelScope.launch {
            operationRepository.createOperation(
                UUID.randomUUID().toString(), operation.title,
                aspectId = null, sourceFutureOperationId = operationId
            )
            futureOperationService.setStatus(operationId, FutureOperationStatus.ARCHIVED)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            futureOperationService.delete(operationId)
            onDeleted()
        }
    }
}

class FutureOperationDetailViewModelFactory(
    private val operationId: String,
    private val futureOperationRepository: FutureOperationRepository,
    private val operationRepository: OperationRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        FutureOperationDetailViewModel(operationId, futureOperationRepository, operationRepository) as T
}
