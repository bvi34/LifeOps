package com.lifeops.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.ui.theme.priorityColor
import com.lifeops.app.util.DateUtil

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    task: Task,
    aspectColor: String,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onCarryForward: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }

    val isCompleted = task.status == TaskStatus.COMPLETED
    val isSkipped = task.status == TaskStatus.SKIPPED
    val isExpired = task.status == TaskStatus.EXPIRED
    val isPending = task.status == TaskStatus.PENDING

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = { if (task.notes != null) expanded = !expanded },
                onLongClick = { if (isPending) showContextMenu = true }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpired)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(priorityColor(task.priority.label))
                    .align(Alignment.CenterVertically)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isCompleted,
                    onCheckedChange = { if (!isCompleted) onComplete() },
                    enabled = task.status == TaskStatus.PENDING,
                    colors = CheckboxDefaults.colors(
                        checkedColor = parseColor(aspectColor)
                    )
                )
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (isCompleted || isSkipped) TextDecoration.LineThrough else null,
                            color = if (isCompleted || isSkipped)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (task.hardDeadline) {
                            Icon(
                                Icons.Default.AccessTime,
                                contentDescription = "Hard deadline",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp).padding(start = 4.dp)
                            )
                        }
                    }
                    task.dueDate?.let { date ->
                        Text(
                            text = DateUtil.formatDate(date),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (DateUtil.isOverdue(date) && task.status == TaskStatus.PENDING)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    AnimatedVisibility(visible = expanded && task.notes != null) {
                        Text(
                            text = task.notes ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                PriorityBadge(task.priority.label)
            }
        }
    }

    if (showContextMenu) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = { showContextMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Skip") },
                onClick = { showContextMenu = false; onSkip() }
            )
            DropdownMenuItem(
                text = { Text("Carry Forward") },
                onClick = { showContextMenu = false; onCarryForward() }
            )
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = { showContextMenu = false; onEdit() }
            )
        }
    }
}

@Composable
private fun PriorityBadge(priority: String) {
    val color = priorityColor(priority)
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.2f),
        modifier = Modifier.padding(start = 4.dp)
    ) {
        Text(
            text = priority.take(1).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
