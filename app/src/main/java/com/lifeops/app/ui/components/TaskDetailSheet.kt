@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.Priority
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskNote
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.ui.theme.priorityColor
import com.lifeops.app.util.DateUtil

@Composable
fun TaskDetailSheet(
    task: Task,
    notes: List<TaskNote>,
    totalTimeMinutes: Int,
    isTimerActive: Boolean,
    timerElapsedSeconds: Int,
    onDismiss: () -> Unit,
    onAddNote: (String) -> Unit,
    onEdit: () -> Unit,
    onCarryForward: () -> Unit,
    onStartTimer: () -> Unit,
    onStopTimer: () -> Unit,
    onLogTime: (Int, String?) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newNoteText by remember { mutableStateOf("") }
    var showLogManuallyDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header: title + edit button
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusChip(task.status)
                        Text(
                            task.priority.label.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = priorityColor(task.priority.label)
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Edit task")
                }
            }

            task.dueDate?.let { date ->
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.hardDeadline) {
                        Icon(
                            Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        "${if (task.hardDeadline) "Hard deadline: " else "Due: "}${DateUtil.formatDate(date)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            if (task.status == TaskStatus.PENDING) {
                TextButton(
                    onClick = onCarryForward,
                    modifier = Modifier.padding(top = 2.dp)
                ) { Text("Carry Forward") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Time tracking section
            Text("Time Tracking", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            if (totalTimeMinutes > 0) {
                Text(
                    "Total logged: ${formatMinutes(totalTimeMinutes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(6.dp))
            }

            if (isTimerActive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatElapsed(timerElapsedSeconds),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onStopTimer,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Stop & Save") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onStartTimer,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.AccessTime,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Start Timer")
                    }
                    OutlinedButton(
                        onClick = { showLogManuallyDialog = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("Log Manually") }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Notes section
            Text("Notes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            if (notes.isEmpty()) {
                Text(
                    "No notes yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                notes.forEach { note ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(note.content, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            note.createdAt.take(10),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                    HorizontalDivider()
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = newNoteText,
                    onValueChange = { newNoteText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Add a note…") },
                    minLines = 1,
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (newNoteText.isNotBlank()) {
                            onAddNote(newNoteText.trim())
                            newNoteText = ""
                        }
                    },
                    enabled = newNoteText.isNotBlank()
                ) { Icon(Icons.Default.Add, "Add note") }
            }
        }
    }

    if (showLogManuallyDialog) {
        ManualLogTimeDialog(
            onConfirm = { minutes, note ->
                onLogTime(minutes, note)
                showLogManuallyDialog = false
            },
            onDismiss = { showLogManuallyDialog = false }
        )
    }
}

@Composable
private fun StatusChip(status: TaskStatus) {
    val (color, label) = when (status) {
        TaskStatus.PENDING -> MaterialTheme.colorScheme.primary to "Pending"
        TaskStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary to "Done"
        TaskStatus.SKIPPED -> MaterialTheme.colorScheme.outline to "Skipped"
        TaskStatus.INCOMPLETE -> MaterialTheme.colorScheme.error to "Incomplete"
        TaskStatus.EXPIRED -> MaterialTheme.colorScheme.error to "Expired"
        TaskStatus.CARRIED_FORWARD -> MaterialTheme.colorScheme.secondary to "Carried"
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ManualLogTimeDialog(onConfirm: (Int, String?) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val minutesInt = minutes.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Time Manually") },
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
