@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.Priority
import com.lifeops.app.data.model.Task

@Composable
fun TaskEditDialog(
    task: Task,
    onSave: (title: String, priority: Priority, dueDate: String?, hardDeadline: Boolean, isRecurring: Boolean, estimatedMinutes: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var priority by remember(task.id) { mutableStateOf(task.priority) }
    var dueDate by remember(task.id) { mutableStateOf(task.dueDate ?: "") }
    var hardDeadline by remember(task.id) { mutableStateOf(task.hardDeadline) }
    var isRecurring by remember(task.id) { mutableStateOf(task.isRecurring) }
    var estimatedMinutes by remember(task.id) { mutableStateOf(task.estimatedMinutes?.toString() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Priority", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priority.entries.forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p.label.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
                OutlinedTextField(
                    value = dueDate,
                    onValueChange = { dueDate = it },
                    label = { Text("Due date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = estimatedMinutes,
                    onValueChange = { estimatedMinutes = it.filter { c -> c.isDigit() } },
                    label = { Text("Estimated minutes (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hardDeadline, onCheckedChange = { hardDeadline = it })
                    Text("Hard deadline", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isRecurring, onCheckedChange = { isRecurring = it })
                    Text("Repeat weekly", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        title.trim(),
                        priority,
                        dueDate.trim().ifBlank { null },
                        hardDeadline,
                        isRecurring,
                        estimatedMinutes.toIntOrNull()
                    )
                },
                enabled = title.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
