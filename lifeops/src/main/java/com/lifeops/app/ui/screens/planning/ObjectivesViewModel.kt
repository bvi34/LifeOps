package com.lifeops.app.ui.screens.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.ObjectiveStatus
import com.lifeops.app.data.model.ObjectiveWithSteps
import com.lifeops.app.data.repository.AspectRepository
import com.lifeops.app.data.repository.ObjectiveRepository
import com.lifeops.app.data.repository.ObjectiveStepDraft
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the objective editor is open on: [editing] is null for a new objective. */
data class ObjectiveEditorTarget(val editing: ObjectiveWithSteps?)

data class ObjectivesUiState(
    val objectives: List<ObjectiveWithSteps> = emptyList(),
    val aspects: Map<String, Aspect> = emptyMap(),
    val editor: ObjectiveEditorTarget? = null,
    val today: String = DateUtil.todayKey(),
    val isLoading: Boolean = true
) {
    val active: List<ObjectiveWithSteps>
        get() = objectives.filter { it.objective.status == ObjectiveStatus.ACTIVE }

    /** Closed objectives, most recently closed first. */
    val closed: List<ObjectiveWithSteps>
        get() = objectives.filter { it.objective.status != ObjectiveStatus.ACTIVE }
            .sortedByDescending { it.objective.closedAt ?: it.objective.updatedAt }
}

/**
 * Objectives, for both places they appear: the Future screen, where they're created, edited and
 * reviewed, and the This Week board, where each active one sits above its aspect every week.
 */
class ObjectivesViewModel(
    private val objectiveRepository: ObjectiveRepository,
    aspectRepository: AspectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ObjectivesUiState())
    val uiState: StateFlow<ObjectivesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(objectiveRepository.observeAll(), aspectRepository.observeAllAspects()) { o, a -> o to a }
                .collectLatest { (objectives, aspects) ->
                    _uiState.update {
                        it.copy(
                            objectives = objectives,
                            aspects = aspects.associateBy { a -> a.id },
                            // Re-read on every change so a board left open overnight rolls over.
                            today = DateUtil.todayKey(),
                            isLoading = false
                        )
                    }
                }
        }
    }

    fun startCreate() = _uiState.update { it.copy(editor = ObjectiveEditorTarget(null)) }
    fun startEdit(item: ObjectiveWithSteps) = _uiState.update { it.copy(editor = ObjectiveEditorTarget(item)) }
    fun dismissEditor() = _uiState.update { it.copy(editor = null) }

    fun save(
        title: String,
        aspectId: String?,
        dueDate: String,
        successCriteria: String,
        steps: List<ObjectiveStepDraft>
    ) {
        val editingId = _uiState.value.editor?.editing?.objective?.id
        viewModelScope.launch {
            objectiveRepository.save(editingId, title, aspectId, dueDate, successCriteria, steps)
            _uiState.update { it.copy(editor = null) }
        }
    }

    fun setStepDone(objectiveId: String, stepId: String, done: Boolean) {
        viewModelScope.launch { objectiveRepository.setStepDone(objectiveId, stepId, done) }
    }

    fun reportSuccess(objectiveId: String) = setStatus(objectiveId, ObjectiveStatus.SUCCEEDED)
    fun markUnsuccessful(objectiveId: String) = setStatus(objectiveId, ObjectiveStatus.UNSUCCESSFUL)
    fun reopen(objectiveId: String) = setStatus(objectiveId, ObjectiveStatus.ACTIVE)

    private fun setStatus(objectiveId: String, status: ObjectiveStatus) {
        viewModelScope.launch { objectiveRepository.setStatus(objectiveId, status) }
    }

    fun delete(objectiveId: String) {
        viewModelScope.launch {
            objectiveRepository.delete(objectiveId)
            _uiState.update { it.copy(editor = null) }
        }
    }
}

class ObjectivesViewModelFactory(
    private val objectiveRepository: ObjectiveRepository,
    private val aspectRepository: AspectRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ObjectivesViewModel(objectiveRepository, aspectRepository) as T
}
