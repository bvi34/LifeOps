package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.ImportPreview
import com.lifeops.app.data.model.TaskStatus

@Composable
fun ImportDialog(
    json: String,
    onJsonChange: (String) -> Unit,
    preview: ImportPreview?,
    error: String?,
    includeUnknownAsNotes: Boolean,
    onIncludeUnknownChange: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onCommit: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Tasks") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Paste AI-generated JSON. Each task: title, priority (low/medium/high/critical), aspect, category, due_date (YYYY-MM-DD), hard_deadline, notes, status (pending/completed/skipped), time_logged_minutes, estimated_minutes, is_recurring, recurrence_interval_weeks (1=weekly, 2=bi-weekly…), recurrence_day_of_month (1–31 for monthly)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = json,
                    onValueChange = onJsonChange,
                    label = { Text("JSON") },
                    placeholder = { Text("""{"tasks":[{"title":"...","priority":"medium"}]}""") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                preview?.let { p ->
                    Spacer(Modifier.height(12.dp))
                    Text("Preview", style = MaterialTheme.typography.titleSmall)
                    if (p.newAspects.isNotEmpty()) {
                        Text("New aspects: ${p.newAspects.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (p.newCategories.isNotEmpty()) {
                        Text("New categories: ${p.newCategories.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
                    }
                    val pendingCount = p.newTasks.count { it.status == TaskStatus.PENDING }
                    val completedCount = p.newTasks.count { it.status == TaskStatus.COMPLETED }
                    val skippedCount = p.newTasks.count { it.status == TaskStatus.SKIPPED }
                    val statusSummary = buildList {
                        if (pendingCount > 0) add("$pendingCount pending")
                        if (completedCount > 0) add("$completedCount done")
                        if (skippedCount > 0) add("$skippedCount skipped")
                    }.joinToString(", ")
                    Text("Tasks to add: ${p.newTasks.size} ($statusSummary)", style = MaterialTheme.typography.bodySmall)
                    p.newTasks.take(5).forEach { task ->
                        val statusTag = when (task.status) {
                            TaskStatus.COMPLETED -> " ✓"
                            TaskStatus.SKIPPED -> " –"
                            else -> ""
                        }
                        Text("• ${task.title} (${task.priority.label})$statusTag", style = MaterialTheme.typography.bodySmall)
                    }
                    if (p.newTasks.size > 5) {
                        Text("  ...and ${p.newTasks.size - 5} more", style = MaterialTheme.typography.bodySmall)
                    }

                    if (p.unknownFieldsByTask.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Unknown fields found",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                        p.unknownFieldsByTask.forEach { (taskTitle, fields) ->
                            Text(
                                "• $taskTitle",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            )
                            fields.forEach { fieldLine ->
                                Text(
                                    "    $fieldLine",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = includeUnknownAsNotes,
                                onCheckedChange = onIncludeUnknownChange
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Append extra fields as notes (field: value)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (preview != null) {
                Button(onClick = onCommit) { Text("Import ${preview.newTasks.size} Tasks") }
            } else {
                Button(onClick = onPreview, enabled = json.isNotBlank()) { Text("Preview") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
