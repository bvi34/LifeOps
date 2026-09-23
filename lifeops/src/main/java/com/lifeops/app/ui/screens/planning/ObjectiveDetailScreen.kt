@file:OptIn(ExperimentalMaterial3Api::class)
package com.lifeops.app.ui.screens.planning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.ObjectiveStatus
import com.lifeops.app.data.model.ObjectiveStep
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.components.formatMinutes
import com.lifeops.app.ui.components.objectiveDueLine
import com.lifeops.app.ui.components.objectiveStepDetail
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.Objectives
import com.lifeops.app.util.StepState
import com.operations.backupkit.AppId
import com.repository.app.logic.DocumentKind
import com.repository.app.ui.attach.DocumentsPanel

/**
 * An objective's own page: its steps with the time and notes put into each, the documents that
 * back it up, and notes on the objective itself. A step's notes, photos and time go on its task —
 * the one it has on the week it's worked — so a step without one can be started here.
 */
@Composable
fun ObjectiveDetailScreen(
    viewModel: ObjectiveDetailViewModel,
    objectivesViewModel: ObjectivesViewModel,
    onOpenTask: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Back from a step's task (time logged, a note added) — re-read the roll-up.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val item = state.item
    var confirm by remember { mutableStateOf<ObjectiveStatus?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            AppHeader(
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (item != null) {
                        TextButton(onClick = { objectivesViewModel.startEdit(item) }) { Text("Edit") }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            item == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This objective no longer exists.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            else -> {
                val objective = item.objective
                val isActive = objective.status == ObjectiveStatus.ACTIVE
                val color = state.aspect?.let { parseColor(it.color) } ?: MaterialTheme.colorScheme.primary
                val states = Objectives.states(item.steps, state.today)
                val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Flag, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(objective.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    listOfNotNull(state.aspect?.name, objectiveDueLine(item, state.today)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isActive && Objectives.isOverdue(item, state.today)) MaterialTheme.colorScheme.error else muted
                                )
                                Text(
                                    "Complete when: ${objective.successCriteria}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    Stat("${item.steps.count { it.isDone }}/${item.steps.size}", "steps done")
                                    Stat(formatMinutes(state.totalMinutes), "time")
                                    Stat("${state.timeline.size}", if (state.timeline.size == 1) "note" else "notes")
                                }
                            }
                        }
                    }

                    if (item.steps.isNotEmpty()) {
                        item {
                            Column {
                                SectionTitle("Steps")
                                Text(
                                    "A step's notes, photos and time go on its task for the week — tap one to open it.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = muted
                                )
                            }
                        }
                        items(item.steps.size, key = { item.steps[it].id }) { index ->
                            val step = item.steps[index]
                            val work = state.workByStep[step.id]
                            val task = work?.taskToOpen(state.currentWeekId)
                            StepRow(
                                number = index + 1,
                                step = step,
                                state = states[index],
                                enabled = isActive,
                                work = work,
                                onToggle = { done -> objectivesViewModel.setStepDone(objective.id, step.id, done) },
                                onOpenTask = task?.let { t -> { onOpenTask(t.id) } },
                                onStartNow = if (task == null && isActive && !step.isDone &&
                                    (states[index] == StepState.OPEN || states[index] == StepState.OVERDUE)
                                ) {
                                    { objectivesViewModel.workOnThisWeek(step.id) { id -> onOpenTask(id) } }
                                } else null
                            )
                        }
                    }

                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isActive) {
                                Button(
                                    onClick = { confirm = ObjectiveStatus.SUCCEEDED },
                                    enabled = Objectives.canReportSuccess(item.steps)
                                ) { Text("Report success") }
                                OutlinedButton(onClick = { confirm = ObjectiveStatus.UNSUCCESSFUL }) {
                                    Text("Mark unsuccessful")
                                }
                            } else {
                                OutlinedButton(onClick = { objectivesViewModel.reopen(objective.id) }) { Text("Reopen") }
                            }
                        }
                    }

                    // The paperwork behind the objective — the offer letter, the certificate — on
                    // the household's shelf, the same as an operation's.
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SectionTitle("Documents")
                                DocumentsPanel(
                                    appKey = AppId.LIFEOPS.key,
                                    recordKey = objective.id,
                                    recordLabel = objective.title,
                                    kinds = listOf(
                                        DocumentKind.CONTRACT,
                                        DocumentKind.RECEIPT,
                                        DocumentKind.REPORT,
                                        DocumentKind.CORRESPONDENCE,
                                        DocumentKind.OTHER
                                    ),
                                    emptyLine = "The offer, the certificate, the sign-off — what shows it happened."
                                )
                            }
                        }
                    }

                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SectionTitle("Notes")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    placeholder = { Text("A note on this objective") },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    onClick = { viewModel.addNote(draft); draft = "" },
                                    enabled = draft.isNotBlank()
                                ) { Text("Add") }
                            }
                        }
                    }
                    items(state.timeline, key = { "note|${it.id}" }) { note ->
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                                Text(
                                    note.stepTitle ?: "Objective",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = color.copy(alpha = 0.8f)
                                )
                                Text(note.content, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    DateUtil.formatDate(DateUtil.localDateKey(note.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                            note.objectiveNoteId?.let { id ->
                                IconButton(onClick = { viewModel.deleteNote(id) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete note", modifier = Modifier.size(16.dp), tint = muted)
                                }
                            }
                        }
                    }
                }

                confirm?.let { outcome ->
                    val success = outcome == ObjectiveStatus.SUCCEEDED
                    AlertDialog(
                        onDismissRequest = { confirm = null },
                        title = { Text(if (success) "Report success?" else "Mark unsuccessful?") },
                        text = {
                            Text(
                                if (success) "\"${objective.title}\" closes as achieved and leaves your week."
                                else "\"${objective.title}\" closes as not achieved and leaves your week. " +
                                    "You can reopen it from here or Planning → Future."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirm = null
                                if (success) objectivesViewModel.reportSuccess(objective.id)
                                else objectivesViewModel.markUnsuccessful(objective.id)
                            }) { Text(if (success) "Report success" else "Mark unsuccessful") }
                        },
                        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
                    )
                }
            }
        }
    }

    ObjectiveEditorHost(objectivesViewModel)
}

@Composable
private fun StepRow(
    number: Int,
    step: ObjectiveStep,
    state: StepState,
    enabled: Boolean,
    work: StepWork?,
    onToggle: (Boolean) -> Unit,
    onOpenTask: (() -> Unit)?,
    onStartNow: (() -> Unit)?
) {
    val waiting = state == StepState.LOCKED || state == StepState.UPCOMING
    val dim = waiting || state == StepState.DONE
    ElevatedCard(
        onClick = { onOpenTask?.invoke() },
        enabled = onOpenTask != null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
            if (waiting) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        if (state == StepState.LOCKED) Icons.Default.Lock else Icons.Default.Schedule,
                        contentDescription = if (state == StepState.LOCKED) "Locked" else "Not open yet",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            } else {
                Checkbox(checked = step.isDone, onCheckedChange = onToggle, enabled = enabled)
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    "$number. ${step.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (step.isDone) TextDecoration.LineThrough else null,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dim) 0.55f else 1f)
                )
                objectiveStepDetail(number, step, state)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state == StepState.OVERDUE) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                work?.takeIf { it.minutes > 0 || it.noteCount > 0 }?.let { w ->
                    Text(
                        listOfNotNull(
                            w.minutes.takeIf { it > 0 }?.let { formatMinutes(it) },
                            w.noteCount.takeIf { it > 0 }?.let { "$it note${if (it == 1) "" else "s"}" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            when {
                onOpenTask != null -> Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Open the step's task",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                onStartNow != null -> TextButton(onClick = onStartNow) { Text("Start now") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun Stat(value: String, label: String, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}
