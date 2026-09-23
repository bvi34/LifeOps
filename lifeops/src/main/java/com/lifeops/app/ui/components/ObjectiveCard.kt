package com.lifeops.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
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
import com.lifeops.app.data.model.ObjectiveStatus
import com.lifeops.app.data.model.ObjectiveStep
import com.lifeops.app.data.model.ObjectiveWithSteps
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.Objectives
import com.lifeops.app.util.StepState

/**
 * An Objective as it sits on the week board, above its aspect. It shows what can be done now —
 * the open steps, or the next one waiting — and expands to the full step list. There is no way to
 * dismiss it here: it leaves the board only when success is reported (once every step is done)
 * or it is marked unsuccessful.
 */
@Composable
fun ObjectiveCard(
    item: ObjectiveWithSteps,
    aspectColor: Color,
    today: String,
    onToggleStep: (stepId: String, done: Boolean) -> Unit,
    onReportSuccess: () -> Unit,
    onMarkUnsuccessful: () -> Unit,
    onReopen: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false
) {
    val objective = item.objective
    val steps = item.steps
    val states = remember(steps, today) { Objectives.states(steps, today) }
    val doneCount = steps.count { it.isDone }
    val isActive = objective.status == ObjectiveStatus.ACTIVE
    var expanded by rememberSaveable(objective.id) { mutableStateOf(startExpanded) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ObjectiveStatus?>(null) }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(aspectColor)
            )
            Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Flag,
                        contentDescription = null,
                        tint = aspectColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { expanded = !expanded }
                    ) {
                        Text(
                            objective.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            dueLine(item, today),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive && Objectives.isOverdue(item, today)) MaterialTheme.colorScheme.error
                                    else muted
                        )
                    }
                    if (steps.isNotEmpty()) {
                        Text("$doneCount/${steps.size}", style = MaterialTheme.typography.labelMedium, color = muted)
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Objective actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                            if (isActive) {
                                DropdownMenuItem(
                                    text = { Text("Mark unsuccessful") },
                                    onClick = { menuOpen = false; confirm = ObjectiveStatus.UNSUCCESSFUL }
                                )
                            } else {
                                DropdownMenuItem(text = { Text("Reopen") }, onClick = { menuOpen = false; onReopen() })
                            }
                        }
                    }
                }
                if (steps.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { doneCount / steps.size.toFloat() },
                        color = aspectColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp)
                    )
                }

                val shown = if (expanded || !isActive) steps.indices.toList() else Objectives.focusIndices(steps, today)
                shown.forEach { index ->
                    StepLine(
                        number = index + 1,
                        step = steps[index],
                        state = states[index],
                        enabled = isActive,
                        onToggle = { done -> onToggleStep(steps[index].id, done) }
                    )
                }
                val hidden = steps.size - shown.size
                if (hidden > 0) {
                    Text(
                        "Show all ${steps.size} steps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { expanded = true }
                            .padding(vertical = 4.dp)
                    )
                } else if (expanded && isActive && steps.size > Objectives.focusIndices(steps, today).size) {
                    Text(
                        "Show less",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { expanded = false }
                            .padding(vertical = 4.dp)
                    )
                }

                if (isActive) {
                    val ready = Objectives.canReportSuccess(steps)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Complete when: ${objective.successCriteria}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (ready) "Due ${DateUtil.formatDate(objective.dueDate)}"
                                else "Opens once every step is done · due ${DateUtil.formatDate(objective.dueDate)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = muted
                            )
                        }
                        FilledTonalButton(
                            onClick = { confirm = ObjectiveStatus.SUCCEEDED },
                            enabled = ready,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("Report success") }
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
                        "You can reopen it from Planning → Future."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (success) onReportSuccess() else onMarkUnsuccessful()
                }) { Text(if (success) "Report success" else "Mark unsuccessful") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StepLine(
    number: Int,
    step: ObjectiveStep,
    state: StepState,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val canTick = enabled && state != StepState.LOCKED && state != StepState.UPCOMING
    val dim = state == StepState.LOCKED || state == StepState.UPCOMING || state == StepState.DONE
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state == StepState.LOCKED || state == StepState.UPCOMING) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (state == StepState.LOCKED) Icons.Default.Lock else Icons.Default.Schedule,
                    contentDescription = if (state == StepState.LOCKED) "Locked" else "Not open yet",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        } else {
            Checkbox(checked = step.isDone, onCheckedChange = onToggle, enabled = canTick)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "$number. ${step.title}",
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (step.isDone) TextDecoration.LineThrough else null,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dim) 0.55f else 1f)
            )
            stepDetail(number, step, state)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state == StepState.OVERDUE) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun stepDetail(number: Int, step: ObjectiveStep, state: StepState): String? {
    val due = step.dueDate?.let { "due ${DateUtil.formatDate(it)}" }
    return when (state) {
        StepState.DONE -> step.completedAt?.let { "done ${DateUtil.formatDate(DateUtil.localDateKey(it))}" }
        StepState.LOCKED -> listOfNotNull("opens when step ${number - 1} is done", due).joinToString(" · ")
        StepState.UPCOMING -> listOfNotNull("opens ${DateUtil.formatDate(step.opensOn)}", due).joinToString(" · ")
        StepState.OVERDUE -> "overdue · was due ${DateUtil.formatDate(step.dueDate)}"
        StepState.OPEN -> due
    }
}

private fun dueLine(item: ObjectiveWithSteps, today: String): String {
    val o = item.objective
    val due = "Due ${DateUtil.formatDate(o.dueDate)}"
    return when (o.status) {
        ObjectiveStatus.SUCCEEDED -> "Succeeded ${DateUtil.formatDate(o.closedAt?.let { DateUtil.localDateKey(it) })} · $due"
        ObjectiveStatus.UNSUCCESSFUL -> "Unsuccessful ${DateUtil.formatDate(o.closedAt?.let { DateUtil.localDateKey(it) })} · $due"
        ObjectiveStatus.ACTIVE -> {
            val days = Objectives.daysUntil(o.dueDate, today)
            when {
                days == null -> "Objective · $due"
                days < 0 -> "Objective · overdue by ${-days} day${if (days == -1L) "" else "s"}"
                days == 0L -> "Objective · due today"
                else -> "Objective · $due · $days day${if (days == 1L) "" else "s"} left"
            }
        }
    }
}
