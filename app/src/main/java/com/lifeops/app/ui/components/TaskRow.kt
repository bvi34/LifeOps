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
import com.lifeops.app.data.model.TaskNote
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.ui.theme.priorityColor
import com.lifeops.app.util.DateUtil

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    task: Task,
    aspectColor: String,
    notes: List<TaskNote>,
    totalTimeMinutes: Int,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onCarryForward: () -> Unit,
    onEdit: () -> Unit,
    onAddNote: (String) -> Unit,
    onLogTime: (Int, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var showAddNoteDialog by remember { mutableStateOf(false) }
    var showLogTimeDialog by remember { mutableStateOf(false) }

    val isCompleted = task.status == TaskStatus.COMPLETED
    val isSkipped = task.status == TaskStatus.SKIPPED
    val isExpired = task.status == TaskStatus.EXPIRED
    val isPending = task.status == TaskStatus.PENDING
    val hasExpandContent = notes.isNotEmpty() || totalTimeMinutes > 0

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = { if (hasExpandContent) expanded = !expanded },
                onLongClick = { showContextMenu = true }
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isCompleted,
                        onCheckedChange = { if (!isCompleted) onComplete() },
                        enabled = isPending,
                        colors = CheckboxDefaults.colors(checkedColor = parseColor(aspectColor))
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
                                color = if (DateUtil.isOverdue(date) && isPending)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        // Inline summary: note count and logged time shown when collapsed
                        if (!expanded && hasExpandContent) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                if (notes.isNotEmpty()) {
                                    Text(
                                        "${notes.size} note${if (notes.size > 1) "s" else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    )
                                }
                                if (totalTimeMinutes > 0) {
                                    Text(
                                        formatMinutes(totalTimeMinutes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                    PriorityBadge(task.priority.label)
                }

                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(start = 44.dp, top = 4.dp, bottom = 4.dp)) {
                        if (totalTimeMinutes > 0) {
                            Text(
                                "Time logged: ${formatMinutes(totalTimeMinutes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        notes.forEach { note ->
                            Text(
                                "• ${note.content}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showContextMenu) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = { showContextMenu = false }
        ) {
            if (isPending) {
                DropdownMenuItem(
                    text = { Text("Skip") },
                    onClick = { showContextMenu = false; onSkip() }
                )
                DropdownMenuItem(
                    text = { Text("Carry Forward") },
                    onClick = { showContextMenu = false; onCarryForward() }
                )
            }
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = { showContextMenu = false; onEdit() }
            )
            DropdownMenuItem(
                text = { Text("Add Note") },
                onClick = { showContextMenu = false; showAddNoteDialog = true }
            )
            DropdownMenuItem(
                text = { Text("Log Time") },
                onClick = { showContextMenu = false; showLogTimeDialog = true }
            )
        }
    }

    if (showAddNoteDialog) {
        AddNoteDialog(
            onConfirm = { content -> onAddNote(content); showAddNoteDialog = false },
            onDismiss = { showAddNoteDialog = false }
        )
    }

    if (showLogTimeDialog) {
        LogTimeDialog(
            onConfirm = { minutes, note -> onLogTime(minutes, note); showLogTimeDialog = false },
            onDismiss = { showLogTimeDialog = false }
        )
    }
}

@Composable
private fun AddNoteDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Note") },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("Note") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { if (content.isNotBlank()) onConfirm(content.trim()) }, enabled = content.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LogTimeDialog(onConfirm: (Int, String?) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val minutesInt = minutes.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter { c -> c.isDigit() } },
                    label = { Text("Minutes spent") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { minutesInt?.let { onConfirm(it, note.trim().ifBlank { null }) } },
                enabled = minutesInt != null && minutesInt > 0
            ) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatMinutes(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
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
