@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoveUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.ObjectiveWithSteps
import com.lifeops.app.data.model.Task
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.components.ObjectiveCard
import com.lifeops.app.ui.components.ObjectiveEditorDialog
import com.lifeops.app.util.DateUtil
import java.time.LocalDate

/** The two kinds of forward-looking work the Future screen holds. */
enum class FutureTab(val label: String) { TASKS("Tasks"), OBJECTIVES("Objectives") }

/**
 * Planning → Future: what's ahead.
 *
 * Tasks is the queue of tasks whose due date falls beyond the current week. Each waits here
 * (hidden from This Week) until a week containing its due date opens, or the user pulls it in early.
 *
 * Objectives are goals with a due date, reached through ordered steps. They're created and
 * reviewed here, and every active one is shown above its aspect on This Week, every week, until
 * success is reported or it's marked unsuccessful.
 */
@Composable
fun FutureTasksScreen(
    viewModel: FutureTasksViewModel,
    objectivesViewModel: ObjectivesViewModel,
    onBack: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(FutureTab.TASKS) }

    Scaffold(
        topBar = { AppHeader(navigationIcon = { BackNavIcon(onBack) }) },
        floatingActionButton = {
            if (tab == FutureTab.OBJECTIVES) {
                FloatingActionButton(onClick = { objectivesViewModel.startCreate() }) {
                    Icon(Icons.Default.Add, contentDescription = "New objective")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                FutureTab.entries.forEachIndexed { index, t ->
                    SegmentedButton(
                        selected = tab == t,
                        onClick = { tab = t },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = FutureTab.entries.size)
                    ) { Text(t.label) }
                }
            }
            when (tab) {
                FutureTab.TASKS -> QueuedTasksList(viewModel)
                FutureTab.OBJECTIVES -> ObjectivesList(objectivesViewModel)
            }
        }
    }

    ObjectiveEditorHost(objectivesViewModel)
}

@Composable
private fun QueuedTasksList(viewModel: FutureTasksViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Future Tasks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Queued until their due date's week arrives, then they join This Week automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(4.dp))
        }
        if (!state.isLoading && state.queuedTasks.isEmpty()) {
            item {
                Text(
                    "Nothing queued. Tasks created with a due date beyond this week land here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }
        items(state.queuedTasks, key = { it.id }) { task ->
            QueuedTaskItem(
                task = task,
                aspect = task.aspectId?.let { state.aspects[it] },
                onMoveToThisWeek = { viewModel.onMoveToThisWeek(task.id) },
                onDelete = { viewModel.onDelete(task.id) }
            )
        }
    }
}

@Composable
private fun ObjectivesList(viewModel: ObjectivesViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Objectives", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Goals with a due date, reached step by step. Each active one sits above its aspect on " +
                    "This Week, every week, until you report success or mark it unsuccessful.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(4.dp))
        }
        if (!state.isLoading && state.objectives.isEmpty()) {
            item {
                Text(
                    "No objectives yet. Tap + to set one — e.g. \"Obtain ITIL 4 Foundation cert\", " +
                        "with steps to enroll, train and sit the exam.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }
        items(state.active, key = { it.objective.id }) { item ->
            ObjectiveListCard(item, state, viewModel)
        }
        val closed = state.closed
        if (closed.isNotEmpty()) {
            item(key = "closed-header") {
                Text(
                    "Closed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(closed, key = { it.objective.id }) { item ->
                ObjectiveListCard(item, state, viewModel)
            }
        }
    }
}

@Composable
private fun ObjectiveListCard(item: ObjectiveWithSteps, state: ObjectivesUiState, viewModel: ObjectivesViewModel) {
    val aspect = item.objective.aspectId?.let { state.aspects[it] }
    Column {
        Text(
            aspect?.name ?: "No aspect",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
        ObjectiveCard(
            item = item,
            aspectColor = aspect?.let { parseAspectColor(it.color) } ?: MaterialTheme.colorScheme.primary,
            today = state.today,
            onToggleStep = { stepId, done -> viewModel.setStepDone(item.objective.id, stepId, done) },
            onReportSuccess = { viewModel.reportSuccess(item.objective.id) },
            onMarkUnsuccessful = { viewModel.markUnsuccessful(item.objective.id) },
            onReopen = { viewModel.reopen(item.objective.id) },
            onEdit = { viewModel.startEdit(item) },
            startExpanded = true
        )
    }
}

/** The objective editor, shown while [ObjectivesViewModel] has one open. Hosted by every screen
 *  that shows objectives, so an objective can be edited from wherever it's seen. */
@Composable
fun ObjectiveEditorHost(viewModel: ObjectivesViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor = state.editor ?: return
    ObjectiveEditorDialog(
        editing = editor.editing,
        aspects = state.aspects.values.sortedBy { it.name },
        onSave = viewModel::save,
        onDelete = editor.editing?.let { e -> { viewModel.delete(e.objective.id) } },
        onDismiss = viewModel::dismissEditor
    )
}

@Composable
private fun QueuedTaskItem(
    task: Task,
    aspect: Aspect?,
    onMoveToThisWeek: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    aspect?.let {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(parseAspectColor(it.color), CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(task.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Text(
                    buildString {
                        append("Due ${DateUtil.formatDate(task.dueDate)}")
                        surfacingWeekLabel(task.dueDate)?.let { append(" · surfaces $it") }
                        append(" · ${task.priority.label}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            IconButton(onClick = onMoveToThisWeek, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoveUp, contentDescription = "Move to this week", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** "week of Mar 3" for the Monday of the week containing [dueDate], or null if unparseable. */
private fun surfacingWeekLabel(dueDate: String?): String? {
    dueDate ?: return null
    return try {
        val monday = DateUtil.weekStartFor(LocalDate.parse(dueDate))
        "week of ${DateUtil.formatDate(monday.toString())}"
    } catch (_: Exception) {
        null
    }
}

private fun parseAspectColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color.Gray
}
