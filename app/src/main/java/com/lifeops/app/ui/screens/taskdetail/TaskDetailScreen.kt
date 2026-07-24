@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.taskdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.ProjectStatus
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.components.TaskDetailContent
import com.lifeops.app.ui.components.TaskEditDialog

/**
 * Standalone task detail route (`task_detail/{id}`) — the single task detail surface, opened from
 * search or the This Week list. It pushes onto the back stack, so dismissing (Back) returns to the
 * caller. Current-week tasks are fully editable; tasks from a closed week render read-only.
 */
@Composable
fun TaskDetailScreen(
    viewModel: TaskDetailViewModel,
    onOpenProject: (projectId: String) -> Unit = {},
    onOpenCounter: (counterId: String) -> Unit = {},
    onOpenPerson: (personId: String) -> Unit = {},
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeTimer by viewModel.activeTimer.collectAsStateWithLifecycle()
    val timerElapsed = viewModel.timerElapsedSeconds.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppHeader(navigationIcon = { BackNavIcon(onBack) }) }
    ) { padding ->
        val task = state.task
        when {
            state.loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.notFound || task == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "This task no longer exists.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            else -> {
                val isTimerActive = activeTimer?.taskId == task.id
                val isPomodoroActive = isTimerActive && activeTimer?.isPomodoro == true
                val project = task.projectId?.let { pid -> state.projects.firstOrNull { it.id == pid } }
                val counter = task.counterId?.let { cid -> state.counters.firstOrNull { it.id == cid } }
                TaskDetailContent(
                    task = task,
                    editable = state.editable,
                    weekLabel = state.weekLabel,
                    modifier = Modifier.padding(padding),
                    notes = state.notes,
                    ancestorNotes = state.ancestorNotes,
                    totalTimeMinutes = state.totalTimeMinutes,
                    isTimerActive = isTimerActive,
                    timerElapsedSeconds = { timerElapsed.value },
                    isPomodoroActive = isPomodoroActive,
                    costEntries = state.costEntries,
                    costResources = state.costResources,
                    project = project,
                    projects = state.projects.filter { it.status == ProjectStatus.ACTIVE },
                    onAssignProject = { projectId -> viewModel.onAssignProject(projectId) },
                    subtasks = state.subtasks,
                    runbooks = state.runbooks,
                    weatherRequirement = state.weatherRequirement,
                    involvedPeople = state.involvedPeople,
                    allPeople = state.allPeople,
                    onAttachPerson = viewModel::attachPerson,
                    onDetachPerson = viewModel::detachPerson,
                    onOpenPerson = onOpenPerson,
                    counter = counter,
                    counterWeeklyTotal = counter?.let { state.counterWeeklyTotals[it.id] } ?: 0,
                    onLogCounter = { counter?.let { viewModel.logCounter(it.id) } },
                    onOpenCounter = onOpenCounter,
                    onOpenProject = onOpenProject,
                    onToggleSubtask = viewModel::onToggleSubtask,
                    onAttachRunbook = viewModel::onAttachRunbook,
                    onDeleteSubtask = viewModel::onDeleteSubtask,
                    onAddNote = viewModel::onAddNote,
                    attachments = state.attachments,
                    onAddAttachment = viewModel::onAddAttachment,
                    onDeleteAttachment = viewModel::onDeleteAttachment,
                    onEdit = viewModel::startEdit,
                    onCarryForward = viewModel::onCarryForward,
                    onUnCarryForward = { viewModel.onUnCarryForward() },
                    onUnsuccessful = { viewModel.onUnsuccess() },
                    onUnUnsuccessful = { viewModel.onUnUnsuccess() },
                    onPromoteToProject = { viewModel.onPromoteToProject() },
                    onStartTimer = { viewModel.startTimer() },
                    onStopTimer = { viewModel.stopTimer() },
                    onStartPomodoro = { viewModel.startTimer(isPomodoro = true) },
                    onLogTime = { minutes, note -> viewModel.onLogTime(minutes, note) },
                    onLogCost = { resourceId, amount, note -> viewModel.onLogCost(resourceId, amount, note) },
                    onDeleteCostEntry = viewModel::onDeleteCostEntry
                )
            }
        }
    }

    state.editingTask?.let { editing ->
        TaskEditDialog(
            task = editing,
            aspects = state.aspects,
            allCategories = state.categories,
            projects = state.projects,
            counters = state.counters,
            onCreateProject = viewModel::onCreateProject,
            onSave = { title, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, aspectId, categoryId, projectId, counterId, recurrenceIntervalWeeks, recurrenceDayOfMonth ->
                viewModel.saveEdit(title, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, aspectId, categoryId, projectId, counterId, recurrenceIntervalWeeks, recurrenceDayOfMonth)
            },
            onDismiss = viewModel::cancelEdit
        )
    }
}
