@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.CostResource
import com.lifeops.app.data.model.Priority
import com.lifeops.app.data.model.Project
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskCostEntry
import com.lifeops.app.data.model.TaskNote
import com.lifeops.app.data.model.TaskSource
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.ui.theme.priorityColor
import com.lifeops.app.util.DateUtil

@Composable
fun TaskDetailSheet(
    task: Task,
    notes: List<TaskNote>,
    totalTimeMinutes: Int,
    isTimerActive: Boolean,
    timerElapsedSeconds: () -> Int,
    isPomodoroActive: Boolean = false,
    costEntries: List<TaskCostEntry> = emptyList(),
    costResources: List<CostResource> = emptyList(),
    project: Project? = null,
    projects: List<Project> = emptyList(),
    onAssignProject: ((String?) -> Unit)? = null,
    onDismiss: () -> Unit,
    onAddNote: (String) -> Unit,
    onEdit: () -> Unit,
    onCarryForward: () -> Unit,
    onUnCarryForward: (() -> Unit)? = null,
    onPromoteToProject: (() -> Unit)? = null,
    ancestorNotes: List<TaskNote> = emptyList(),
    onStartTimer: () -> Unit,
    onStopTimer: () -> Unit,
    onStartPomodoro: () -> Unit = {},
    onLogTime: (Int, String?) -> Unit,
    onLogCost: (resourceId: String, amount: Int, note: String?) -> Unit = { _, _, _ -> },
    onDeleteCostEntry: (id: String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newNoteText by remember { mutableStateOf("") }
    var showLogManuallyDialog by remember { mutableStateOf(false) }
    var showLogCostDialog by remember { mutableStateOf(false) }

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
                    if (task.source != TaskSource.MANUAL) {
                        val (sourceLabel, sourceIcon) = when (task.source) {
                            TaskSource.SMS -> "via SMS" to Icons.Default.Sms
                            TaskSource.PLANNED -> "planned" to Icons.Default.EventNote
                            TaskSource.MANUAL -> "" to Icons.Default.Circle
                        }
                        Spacer(Modifier.height(4.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text(sourceLabel, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(sourceIcon, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            modifier = Modifier.height(24.dp)
                        )
                        Spacer(Modifier.height(4.dp))
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

            if (task.status == TaskStatus.CARRIED_FORWARD && onUnCarryForward != null) {
                TextButton(
                    onClick = onUnCarryForward,
                    modifier = Modifier.padding(top = 2.dp)
                ) { Text("Restore") }
            }

            if (task.carriedCount >= 2 && task.projectId == null && onPromoteToProject != null) {
                Spacer(Modifier.height(8.dp))
                SuggestionChip(
                    onClick = onPromoteToProject,
                    label = { Text("Looks like a project — promote?") },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            // Project section
            if (project != null || (onAssignProject != null && projects.isNotEmpty())) {
                Spacer(Modifier.height(4.dp))
                var showProjectPicker by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(4.dp))
                    if (project != null) {
                        Text(
                            project.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        if (onAssignProject != null) {
                            IconButton(
                                onClick = { onAssignProject(null) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove project",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            TextButton(
                                onClick = { showProjectPicker = true },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text("Change", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        TextButton(
                            onClick = { showProjectPicker = true },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Assign to project", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (showProjectPicker && onAssignProject != null) {
                    AlertDialog(
                        onDismissRequest = { showProjectPicker = false },
                        title = { Text("Assign to Project") },
                        text = {
                            Column {
                                if (project != null) {
                                    TextButton(
                                        onClick = { onAssignProject(null); showProjectPicker = false },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Remove project assignment")
                                    }
                                    HorizontalDivider()
                                }
                                projects.forEach { proj ->
                                    TextButton(
                                        onClick = { onAssignProject(proj.id); showProjectPicker = false },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(proj.title)
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showProjectPicker = false }) { Text("Cancel") }
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Time tracking section
            Text("Time Tracking", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            if (task.estimatedMinutes != null || totalTimeMinutes > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    task.estimatedMinutes?.let {
                        Text(
                            "Est: ${formatMinutes(it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    if (totalTimeMinutes > 0) {
                        Text(
                            "Logged: ${formatMinutes(totalTimeMinutes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (isTimerActive) {
                val elapsed = timerElapsedSeconds()
                val displaySeconds = if (isPomodoroActive) maxOf(0, 1500 - elapsed) else elapsed
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isPomodoroActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            formatElapsed(displaySeconds),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isPomodoroActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                        )
                        if (isPomodoroActive) {
                            Text(
                                "Pomodoro — ${formatElapsed(elapsed)} elapsed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
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
                        Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Timer")
                    }
                    OutlinedButton(
                        onClick = onStartPomodoro,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("25min")
                    }
                    OutlinedButton(
                        onClick = { showLogManuallyDialog = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("Manual") }
                }
            }

            if (costResources.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Resource Costs",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { showLogCostDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Log", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (costEntries.isEmpty()) {
                    Text(
                        "No costs logged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                } else {
                    val resourceMap = costResources.associateBy { it.id }
                    costEntries.forEach { entry ->
                        val resourceName = resourceMap[entry.resourceId]?.name ?: entry.resourceId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        resourceName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "×${entry.amount}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                entry.note?.let { note ->
                                    Text(
                                        note,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                    )
                                }
                                Text(
                                    entry.recordedAt.take(10),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                            IconButton(
                                onClick = { onDeleteCostEntry(entry.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete cost entry",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Notes section
            Text("Notes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            if (ancestorNotes.isNotEmpty()) {
                Text(
                    "↩ from previous weeks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
                ancestorNotes.forEach { note ->
                    Text(
                        "• ${note.content}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

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
                if (!note.isNullOrBlank()) onAddNote(note)
                showLogManuallyDialog = false
            },
            onDismiss = { showLogManuallyDialog = false }
        )
    }

    if (showLogCostDialog) {
        LogResourceCostDialog(
            resources = costResources,
            onConfirm = { resourceId, amount, note ->
                onLogCost(resourceId, amount, note)
                showLogCostDialog = false
            },
            onDismiss = { showLogCostDialog = false }
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
    val preview = minutesInt?.takeIf { it > 0 }?.let { m ->
        val h = m / 60
        val rem = m % 60
        when {
            h > 0 && rem > 0 -> "$h hr $rem min"
            h > 0 -> "$h hr"
            else -> "$rem min"
        }
    }

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
                if (preview != null) {
                    Text(
                        "= $preview",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                }
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

@Composable
private fun LogResourceCostDialog(
    resources: List<CostResource>,
    onConfirm: (resourceId: String, amount: Int, note: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedResource by remember { mutableStateOf(resources.firstOrNull()) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val amountInt = amount.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Resource Cost") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedResource?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Resource") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        resources.forEach { resource ->
                            DropdownMenuItem(
                                text = { Text(resource.name) },
                                onClick = {
                                    selectedResource = resource
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() } },
                    label = { Text("Amount used") },
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
                onClick = {
                    val res = selectedResource ?: return@Button
                    val amt = amountInt ?: return@Button
                    onConfirm(res.id, amt, note.trim().ifBlank { null })
                },
                enabled = selectedResource != null && amountInt != null && amountInt > 0
            ) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
