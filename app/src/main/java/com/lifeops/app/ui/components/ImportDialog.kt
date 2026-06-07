package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.ImportPreview

@Composable
fun ImportDialog(
    json: String,
    onJsonChange: (String) -> Unit,
    preview: ImportPreview?,
    error: String?,
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
                    "Paste JSON with tasks array. Each task: title, priority, aspect, category, due_date, hard_deadline, notes",
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
                    Text("Tasks to add: ${p.newTasks.size}", style = MaterialTheme.typography.bodySmall)
                    p.newTasks.take(5).forEach { task ->
                        Text("• ${task.title} (${task.priority.label})", style = MaterialTheme.typography.bodySmall)
                    }
                    if (p.newTasks.size > 5) {
                        Text("  ...and ${p.newTasks.size - 5} more", style = MaterialTheme.typography.bodySmall)
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
