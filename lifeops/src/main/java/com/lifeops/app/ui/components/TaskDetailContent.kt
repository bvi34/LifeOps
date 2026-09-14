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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import com.lifeops.app.data.model.Operation
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

/**
 * The task detail body, rendered as a plain scrolling Column so it can host a full screen (the
 * `task_detail/{id}` route). When [editable] is false — a task from a past, closed week — every
 * mutating control collapses to a read-only rendering, so old tasks can be inspected but never
 * changed. The interactive callbacks are only invoked on the editable path.
 */
@Composable
fun TaskDetailContent(
    task: Task,
    editable: Boolean,
    weekLabel: String?,
    notes: List<TaskNote>,
    totalTimeMinutes: Int,
    isTimerActive: Boolean,
    timerElapsedSeconds: () -> Int,
    modifier: Modifier = Modifier,
    isPomodoroActive: Boolean = false,
    costEntries: List<TaskCostEntry> = emptyList(),
    costResources: List<CostResource> = emptyList(),
    operation: Operation? = null,
    operations: List<Operation> = emptyList(),
    onAssignOperation: ((String?) -> Unit)? = null,
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
    onOpenOperation: (operationId: String) -> Unit = {},
    onToggleSubtask: (id: String, checked: Boolean) -> Unit = { _, _ -> },
    onAttachRunbook: (runbookId: String) -> Unit = {},
    onDeleteSubtask: (id: String) -> Unit = {},
    onAddNote: (String) -> Unit = {},
    /** Toggle this task in/out of the week's commitment; null renders the state without offering to change it. */
    onToggleCommitment: (() -> Unit)? = null,
    attachments: List<TaskAttachment> = emptyList(),
    onAddAttachment: (Uri) -> Unit = {},
    onDeleteAttachment: (id: String) -> Unit = {},
    onEdit: () -> Unit = {},
    onCarryForward: (CarryForwardReason) -> Unit = {},
    onUnCarryForward: (() -> Unit)? = null,
    onUnsuccessful: (() -> Unit)? = null,
    onUnUnsuccessful: (() -> Unit)? = null,
    onPromoteToOperation: (() -> Unit)? = null,
    ancestorNotes: List<TaskNote> = emptyList(),
    onStartTimer: () -> Unit = {},
    onStopTimer: () -> Unit = {},
    onStartPomodoro: () -> Unit = {},
    onLogTime: (Int, String?) -> Unit = { _, _ -> },
    onLogCost: (resourceId: String, amount: Int, note: String?) -> Unit = { _, _, _ -> },
    onDeleteCostEntry: (id: String) -> Unit = {}
) {
    var newNoteText by remember { mutableStateOf("") }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onAddAttachment(it) }
    }
    var showLogManuallyDialog by remember { mutableStateOf(false) }
    var showLogCostDialog by remember { mutableStateOf(false) }
    var showRunbookPicker by remember { mutableStateOf(false) }
    var showCarryForwardReasonPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        if (!editable) {
            ReadOnlyBanner(weekLabel = weekLabel)
            Spacer(Modifier.height(12.dp))
        }

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
            if (editable) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Edit task")
                }
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

        if (editable && task.status == TaskStatus.PENDING) {
            TextButton(
                onClick = { showCarryForwardReasonPicker = true },
                modifier = Modifier.padding(top = 2.dp)
            ) { Text("Carry Forward") }
        }

        if (editable && task.status == TaskStatus.PENDING && onUnsuccessful != null) {
            TextButton(
                onClick = onUnsuccessful,
                modifier = Modifier.padding(top = 2.dp)
            ) { Text("Mark Unsuccessful (50% resources)") }
        }

        if (editable && task.status == TaskStatus.UNSUCCESSFUL && onUnUnsuccessful != null) {
            TextButton(
                onClick = onUnUnsuccessful,
                modifier = Modifier.padding(top = 2.dp)
            ) { Text("Restore") }
        }

        if (task.status == TaskStatus.CARRIED_FORWARD) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editable && onUnCarryForward != null) {
                    TextButton(
                        onClick = onUnCarryForward,
                        modifier = Modifier.padding(top = 2.dp)
                    ) { Text("Restore") }
                }
                task.carryForwardReason?.let { reason ->
                    Text(
                        (if (editable && onUnCarryForward != null) "· " else "Delayed: ") + reason.label,
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

        // The week's bar, stated in words rather than as a star — the row's marker is for marking
        // fast on Monday, this is for knowing what the marker meant when you come back to it.
        if (onToggleCommitment != null || task.isCommitment) {
            Spacer(Modifier.height(8.dp))
            FilterChip(
                selected = task.isCommitment,
                onClick = { onToggleCommitment?.invoke() },
                enabled = editable && onToggleCommitment != null,
                label = {
                    Text(
                        if (task.isCommitment) "The week rests on this"
                        else "Mark as one of the week's few"
                    )
                },
                leadingIcon = {
                    Icon(
                        if (task.isCommitment) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }

        if (editable && task.carriedCount >= 2 && task.operationId == null && onPromoteToOperation != null) {
            Spacer(Modifier.height(8.dp))
            SuggestionChip(
                onClick = onPromoteToOperation,
                label = { Text("Looks like a operation — promote?") },
                icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }

        // Operation section
        if (operation != null || (editable && onAssignOperation != null && operations.isNotEmpty())) {
            Spacer(Modifier.height(4.dp))
            var showOperationPicker by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
                Spacer(Modifier.width(4.dp))
                if (operation != null) {
                    Text(
                        operation.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenOperation(operation.id) }
                    )
                    if (editable && onAssignOperation != null) {
                        IconButton(
                            onClick = { onAssignOperation(null) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove operation",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        TextButton(
                            onClick = { showOperationPicker = true },
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text("Change", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (editable) {
                    TextButton(
                        onClick = { showOperationPicker = true },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Assign to operation", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (showOperationPicker && onAssignOperation != null) {
                AlertDialog(
                    onDismissRequest = { showOperationPicker = false },
                    title = { Text("Assign to Operation") },
                    text = {
                        Column {
                            if (operation != null) {
                                TextButton(
                                    onClick = { onAssignOperation(null); showOperationPicker = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Remove operation assignment")
                                }
                                HorizontalDivider()
                            }
                            operations.forEach { proj ->
                                TextButton(
                                    onClick = { onAssignOperation(proj.id); showOperationPicker = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(proj.title)
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showOperationPicker = false }) { Text("Cancel") }
                    }
                )
            }
        }

        // Weather section — only for tasks that carry a weather profile. Read-only display.
        if (weatherRequirement != null && !weatherRequirement.isEmpty) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            WeatherSection(fit = weatherFit, requirement = weatherRequirement)
        }

        // Counter section — the recurring metric this task ticks, with a one-tap log when editable.
        if (counter != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            CounterSection(
                counter = counter,
                weeklyTotal = counterWeeklyTotal ?: 0,
                editable = editable,
                onLog = onLogCounter,
                onOpen = { onOpenCounter(counter.id) }
            )
        }

        // People section — who's involved. Add/remove only when editable.
        if (allPeople.isNotEmpty() || involvedPeople.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            PeopleSection(
                involved = involvedPeople,
                all = allPeople,
                editable = editable,
                onAttach = onAttachPerson,
                onDetach = onDetachPerson,
                onOpen = onOpenPerson
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        TimeTrackingSection(
            task = task,
            editable = editable,
            totalTimeMinutes = totalTimeMinutes,
            isTimerActive = isTimerActive,
            timerElapsedSeconds = timerElapsedSeconds,
            isPomodoroActive = isPomodoroActive,
            onStartTimer = onStartTimer,
            onStopTimer = onStopTimer,
            onStartPomodoro = onStartPomodoro,
            onLogTime = onLogTime,
            onAddNote = onAddNote
        )

        ChecklistSection(
            editable = editable,
            subtasks = subtasks,
            runbooks = runbooks,
            onToggleSubtask = onToggleSubtask,
            onDeleteSubtask = onDeleteSubtask,
            onAttachRunbook = onAttachRunbook
        )

        CostSection(
            editable = editable,
            costEntries = costEntries,
            costResources = costResources,
            onLogCost = onLogCost,
            onDeleteCostEntry = onDeleteCostEntry
        )

        NotesSection(
            editable = editable,
            notes = notes,
            ancestorNotes = ancestorNotes,
            onAddNote = onAddNote
        )

        AttachmentsSection(
            editable = editable,
            attachments = attachments,
            onAddAttachment = onAddAttachment,
            onDeleteAttachment = onDeleteAttachment
        )
    }

}

