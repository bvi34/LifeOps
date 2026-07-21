@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.CarryForwardReason
import com.lifeops.app.data.model.CostResource
import com.lifeops.app.data.model.Counter
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.Priority
import com.lifeops.app.data.model.Project
import com.lifeops.app.data.model.RunbookWithSteps
import com.lifeops.app.data.model.Subtask
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskCostEntry
import com.lifeops.app.data.model.TaskAttachment
import com.lifeops.app.data.model.TaskNote
import com.lifeops.app.data.model.TaskSource
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.data.model.TaskWeatherRequirement
import com.lifeops.app.ui.theme.priorityColor
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.TaskWeatherFit

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
    subtasks: List<Subtask> = emptyList(),
    runbooks: List<RunbookWithSteps> = emptyList(),
    weatherFit: TaskWeatherFit? = null,
    weatherRequirement: TaskWeatherRequirement? = null,
    involvedPeople: List<Person> = emptyList(),
    allPeople: List<Person> = emptyList(),
    onAttachPerson: (personId: String) -> Unit = {},
    onDetachPerson: (personId: String) -> Unit = {},
    onOpenPerson: (personId: String) -> Unit = {},
    counter: Counter? = null,
    counterWeeklyTotal: Int? = null,
    onLogCounter: () -> Unit = {},
    onOpenCounter: (counterId: String) -> Unit = {},
    onOpenProject: (projectId: String) -> Unit = {},
    onToggleSubtask: (id: String, checked: Boolean) -> Unit = { _, _ -> },
    onAttachRunbook: (runbookId: String) -> Unit = {},
    onDeleteSubtask: (id: String) -> Unit = {},
    onDismiss: () -> Unit,
    onAddNote: (String) -> Unit,
    attachments: List<TaskAttachment> = emptyList(),
    onAddAttachment: (Uri) -> Unit = {},
    onDeleteAttachment: (id: String) -> Unit = {},
    onEdit: () -> Unit,
    onCarryForward: (CarryForwardReason) -> Unit,
    onUnCarryForward: (() -> Unit)? = null,
    onUnsuccessful: (() -> Unit)? = null,
    onUnUnsuccessful: (() -> Unit)? = null,
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
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onAddAttachment(it) }
    }
    var showLogManuallyDialog by remember { mutableStateOf(false) }
    var showLogCostDialog by remember { mutableStateOf(false) }
    var showRunbookPicker by remember { mutableStateOf(false) }
    var showCarryForwardReasonPicker by remember { mutableStateOf(false) }

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
                    onClick = { showCarryForwardReasonPicker = true },
                    modifier = Modifier.padding(top = 2.dp)
                ) { Text("Carry Forward") }
            }

            if (task.status == TaskStatus.PENDING && onUnsuccessful != null) {
                TextButton(
                    onClick = onUnsuccessful,
                    modifier = Modifier.padding(top = 2.dp)
                ) { Text("Mark Unsuccessful (50% resources)") }
            }

            if (task.status == TaskStatus.UNSUCCESSFUL && onUnUnsuccessful != null) {
                TextButton(
                    onClick = onUnUnsuccessful,
                    modifier = Modifier.padding(top = 2.dp)
                ) { Text("Restore") }
            }

            if (task.status == TaskStatus.CARRIED_FORWARD && onUnCarryForward != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onUnCarryForward,
                        modifier = Modifier.padding(top = 2.dp)
                    ) { Text("Restore") }
                    task.carryForwardReason?.let { reason ->
                        Text(
                            "· ${reason.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
            }

            if (showCarryForwardReasonPicker) {
                AlertDialog(
                    onDismissRequest = { showCarryForwardReasonPicker = false },
                    title = { Text("Why is this being delayed?") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CarryForwardReason.entries.forEach { reason ->
                                OutlinedButton(
                                    onClick = {
                                        showCarryForwardReasonPicker = false
                                        onCarryForward(reason)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(reason.label) }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showCarryForwardReasonPicker = false }) { Text("Cancel") }
                    }
                )
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
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpenProject(project.id) }
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

            // Weather section — only for tasks that carry a weather profile. Mirrors the row badge
            // but fuller: the requirement summary plus today-vs-best-day read.
            if (weatherRequirement != null && !weatherRequirement.isEmpty) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                WeatherSection(fit = weatherFit, requirement = weatherRequirement)
            }

            // Counter section — the recurring metric this task ticks, with a one-tap log.
            if (counter != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                CounterSection(
                    counter = counter,
                    weeklyTotal = counterWeeklyTotal ?: 0,
                    onLog = onLogCounter,
                    onOpen = { onOpenCounter(counter.id) }
                )
            }

            // People section — who's involved, add/remove, and jump to a person. Hidden entirely
            // for users who track no people at all.
            if (allPeople.isNotEmpty() || involvedPeople.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                PeopleSection(
                    involved = involvedPeople,
                    all = allPeople,
                    onAttach = onAttachPerson,
                    onDetach = onDetachPerson,
                    onOpen = onOpenPerson
                )
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

            // Checklist — subtasks stamped from runbooks
            if (subtasks.isNotEmpty() || runbooks.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Checklist,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Checklist",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (subtasks.isNotEmpty()) {
                        val done = subtasks.count { it.isChecked }
                        Text(
                            "$done/${subtasks.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    if (runbooks.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(
                            onClick = { showRunbookPicker = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Runbook", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                if (subtasks.isEmpty()) {
                    Text(
                        "No checklist items. Add a runbook to stamp its steps here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                } else {
                    subtasks.forEach { subtask ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = subtask.isChecked,
                                onCheckedChange = { onToggleSubtask(subtask.id, it) }
                            )
                            Text(
                                subtask.label,
                                style = MaterialTheme.typography.bodyMedium,
                                textDecoration = if (subtask.isChecked) TextDecoration.LineThrough else null,
                                color = if (subtask.isChecked)
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onDeleteSubtask(subtask.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove step",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
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
                SelectionContainer {
                    Column {
                        ancestorNotes.forEach { note ->
                            Text(
                                "• ${note.content}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
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
                SelectionContainer {
                    Column {
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

            // Attachments section — images kept deliberately compact (capped height) so a photo
            // never dwarfs the surrounding notes; they scroll horizontally when there are several.
            Spacer(Modifier.height(16.dp))
            Text("Attachments", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (attachments.isEmpty()) {
                Text(
                    "No images yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachments.forEach { att ->
                        val bitmap = rememberAttachmentBitmap(att.imageData)
                        Box {
                            if (bitmap != null) {
                                // Fixed compact height; width follows the image's aspect ratio but is
                                // clamped so neither tall-portrait nor wide-panorama shots blow out the row.
                                val aspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = att.caption ?: "Attachment",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .height(110.dp)
                                        .width((110f * aspect).dp.coerceIn(70.dp, 180.dp))
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(110.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("!", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
                            ) {
                                IconButton(
                                    onClick = { onDeleteAttachment(att.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove attachment",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add image")
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

    if (showRunbookPicker) {
        AttachRunbookDialog(
            runbooks = runbooks,
            onPick = { runbookId ->
                onAttachRunbook(runbookId)
                showRunbookPicker = false
            },
            onDismiss = { showRunbookPicker = false }
        )
    }
}

/**
 * Decode a base64 JPEG attachment into an ImageBitmap, cached per image string so it is decoded
 * once while the sheet is open. Attachments are downscaled on import, so decoding here is cheap;
 * a corrupt/unsupported payload yields null (rendered as a small placeholder).
 */
@Composable
private fun rememberAttachmentBitmap(data: String): ImageBitmap? =
    remember(data) {
        runCatching {
            val bytes = Base64.decode(data, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

@Composable
private fun WeatherSection(fit: TaskWeatherFit?, requirement: TaskWeatherRequirement) {
    Text("Weather", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(
        weatherRequirementSummary(requirement),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    if (fit != null) {
        Spacer(Modifier.height(6.dp))
        val goodToday = fit.suitableToday || fit.bestIsToday
        val headline = when {
            goodToday -> "☀ Good today · ${fit.todayMatchPercent}% match"
            fit.bestWindowLabel != null -> "🌧 Not today · better on ${fit.bestWindowLabel}"
            else -> "🌧 No good weather window this week"
        }
        Text(
            headline,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (goodToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.tertiary
        )
        fit.notTodayReason?.takeIf { !goodToday }?.let { reason ->
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    } else {
        Spacer(Modifier.height(6.dp))
        Text(
            "No forecast available yet — add a location on the Weather screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun CounterSection(counter: Counter, weeklyTotal: Int, onLog: () -> Unit, onOpen: () -> Unit) {
    Text("Counter", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(counter.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "$weeklyTotal this week",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        FilledTonalButton(onClick = onLog, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Log")
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "Open counter",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun PeopleSection(
    involved: List<Person>,
    all: List<Person>,
    onAttach: (String) -> Unit,
    onDetach: (String) -> Unit,
    onOpen: (String) -> Unit
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val available = all.filter { p -> involved.none { it.id == p.id } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("People", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = { pickerOpen = true }, enabled = available.isNotEmpty()) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                available.forEach { person ->
                    DropdownMenuItem(
                        text = { Text(person.name) },
                        onClick = { onAttach(person.id); pickerOpen = false }
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    if (involved.isEmpty()) {
        Text(
            "No one assigned.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            involved.forEach { person ->
                InputChip(
                    selected = false,
                    onClick = { onOpen(person.id) },
                    label = { Text(person.name) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove ${person.name}",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onDetach(person.id) }
                        )
                    }
                )
            }
        }
    }
}

/** Compact one-line summary of a task's weather constraints, e.g. "Outdoor · ≤85° · no rain". */
private fun weatherRequirementSummary(req: TaskWeatherRequirement): String = buildList {
    if (req.outdoorPreferred) add("Outdoor")
    req.maxTempF?.let { add("≤${it}°") }
    req.minTempF?.let { add("≥${it}°") }
    if (req.avoidRain) add("no rain")
    req.maxWindMph?.let { add("wind ≤${it}mph") }
    req.durationMinutes?.let { add("${it}m") }
}.joinToString(" · ").ifEmpty { "Weather-sensitive" }

@Composable
private fun AttachRunbookDialog(
    runbooks: List<RunbookWithSteps>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Runbook") },
        text = {
            if (runbooks.isEmpty()) {
                Text(
                    "No runbooks yet. Create one in Settings → Runbooks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    runbooks.forEach { rb ->
                        val n = rb.steps.size
                        OutlinedCard(
                            onClick = { onPick(rb.runbook.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(rb.runbook.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    "$n step${if (n != 1) "s" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
        TaskStatus.UNSUCCESSFUL -> MaterialTheme.colorScheme.outline to "Unsuccessful"
        TaskStatus.QUEUED -> MaterialTheme.colorScheme.secondary to "Queued"
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
