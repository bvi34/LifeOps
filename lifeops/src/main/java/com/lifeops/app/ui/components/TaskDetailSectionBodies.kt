package com.lifeops.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.CostResource
import com.lifeops.app.data.model.RunbookWithSteps
import com.lifeops.app.data.model.Subtask
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskAttachment
import com.lifeops.app.data.model.TaskCostEntry
import com.lifeops.app.data.model.TaskNote
import com.lifeops.app.util.DateUtil

/**
 * The task detail body's five long sections, each with the state that belongs to it.
 *
 * They were lifted out of [TaskDetailContent] one at a time, and the test each had to pass was that
 * the hoisted state it used was used by nothing else — a dialog flag read by one section and one
 * dialog was never the body's state, it was the section's, sitting in the body because there was
 * nowhere else to put it. Now there is.
 *
 * `ColumnScope` receivers because that is what they are rendered into, which keeps the call sites in
 * the body reading as the same list of sections they always were.
 */

/**
 * Time against the task: the estimate, what has been logged, the running timer, and the way to
 * log a stretch by hand.
 *
 * The "log it by hand" dialog belongs to this section rather than to the body, because nothing else
 * opens it — which is the test for whether a piece of hoisted state was really the body's.
 */
@Composable
internal fun ColumnScope.TimeTrackingSection(
    task: Task,
    editable: Boolean,
    totalTimeMinutes: Int,
    isTimerActive: Boolean,
    timerElapsedSeconds: () -> Int,
    isPomodoroActive: Boolean,
    onStartTimer: () -> Unit,
    onStopTimer: () -> Unit,
    onStartPomodoro: () -> Unit,
    onLogTime: (Int, String?) -> Unit,
    onAddNote: (String) -> Unit
) {
    var showLogManuallyDialog by remember { mutableStateOf(false) }
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
    } else if (!editable) {
        Text(
            "No time logged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }

    if (editable) {
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
}

/**
 * The checklist: subtasks stamped from a runbook, and the picker that stamps them.
 */
@Composable
internal fun ColumnScope.ChecklistSection(
    editable: Boolean,
    subtasks: List<Subtask>,
    runbooks: List<RunbookWithSteps>,
    onToggleSubtask: (id: String, checked: Boolean) -> Unit,
    onDeleteSubtask: (id: String) -> Unit,
    onAttachRunbook: (runbookId: String) -> Unit
) {
    var showRunbookPicker by remember { mutableStateOf(false) }
    // Checklist — subtasks stamped from runbooks
    if (subtasks.isNotEmpty() || (editable && runbooks.isNotEmpty())) {
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
            if (editable && runbooks.isNotEmpty()) {
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
                        onCheckedChange = if (editable) {
                            { checked: Boolean -> onToggleSubtask(subtask.id, checked) }
                        } else null,
                        enabled = editable
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
                    if (editable) {
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
 * What the task cost: the resources spent on it, and the way to record another.
 */
@Composable
internal fun ColumnScope.CostSection(
    editable: Boolean,
    costEntries: List<TaskCostEntry>,
    costResources: List<CostResource>,
    onLogCost: (resourceId: String, amount: Int, note: String?) -> Unit,
    onDeleteCostEntry: (id: String) -> Unit
) {
    var showLogCostDialog by remember { mutableStateOf(false) }
    if (costEntries.isNotEmpty() || (editable && costResources.isNotEmpty())) {
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
            if (editable && costResources.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showLogCostDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Log", style = MaterialTheme.typography.labelMedium)
                }
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
                            DateUtil.localDateKey(entry.recordedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                    if (editable) {
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
                }
                HorizontalDivider()
            }
        }
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

/**
 * The notes kept against the task, the ones inherited from the tasks it was carried forward
 * from, and the box for writing another.
 */
@Composable
internal fun ColumnScope.NotesSection(
    editable: Boolean,
    notes: List<TaskNote>,
    ancestorNotes: List<TaskNote>,
    onAddNote: (String) -> Unit
) {
    var newNoteText by remember { mutableStateOf("") }
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
                            DateUtil.localDateKey(note.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (editable) {
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

/**
 * Photos and files kept with the task.
 *
 * Images are capped in height so a photo never dwarfs the notes above them, and scroll horizontally
 * when there are several.
 */
@Composable
internal fun ColumnScope.AttachmentsSection(
    editable: Boolean,
    attachments: List<TaskAttachment>,
    onAddAttachment: (Uri) -> Unit,
    onDeleteAttachment: (id: String) -> Unit
) {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onAddAttachment(it) }
    }
    // Attachments section — images kept deliberately compact (capped height) so a photo
    // never dwarfs the surrounding notes; they scroll horizontally when there are several.
    if (attachments.isNotEmpty() || editable) {
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
                        if (editable) {
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
        }
        if (editable) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add image")
            }
        }
    }
}
