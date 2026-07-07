package com.lifeops.app.ui.screens.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.repository.AspectRepository
import com.lifeops.app.data.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class FutureTasksUiState(
    val queuedTasks: List<Task> = emptyList(),
    val aspects: Map<String, Aspect> = emptyMap(),
    val isLoading: Boolean = true
)

class FutureTasksViewModel(
    private val taskRepository: TaskRepository,
    aspectRepository: AspectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FutureTasksUiState())
    val uiState: StateFlow<FutureTasksUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                taskRepository.observeQueuedTasks(),
                aspectRepository.observeAllAspects()
            ) { tasks, aspects -> tasks to aspects }
                .collectLatest { (tasks, aspects) ->
                    _uiState.value = FutureTasksUiState(
                        queuedTasks = tasks,
                        aspects = aspects.associateBy { it.id },
                        isLoading = false
                    )
                }
        }
    }

    /** Pull the task into the current week now instead of waiting for its due-date week. */
    fun onMoveToThisWeek(taskId: String) {
        viewModelScope.launch { taskRepository.activateQueuedTaskNow(taskId) }
    }

    fun onDelete(taskId: String) {
        viewModelScope.launch { taskRepository.deleteTask(taskId) }
    }
}

class FutureTasksViewModelFactory(
    private val taskRepository: TaskRepository,
    private val aspectRepository: AspectRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        FutureTasksViewModel(taskRepository, aspectRepository) as T
}
