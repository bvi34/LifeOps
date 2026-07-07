@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoveUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Task
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.util.DateUtil
import java.time.LocalDate

/**
 * Planning → Future Tasks: the queue of tasks whose due date falls beyond the current
 * week. Each waits here (hidden from This Week) until a week containing its due date
 * opens, or the user pulls it in early.
 */
@Composable
fun FutureTasksScreen(viewModel: FutureTasksViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppHeader(navigationIcon = { BackNavIcon(onBack) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
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
