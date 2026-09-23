package com.lifeops.app.ui.screens.thisweek

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.ObjectiveStep
import com.lifeops.app.data.model.ObjectiveWithSteps
import com.lifeops.app.ui.components.objectiveDueLine
import com.lifeops.app.ui.components.objectiveStepDetail
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.Objectives
import com.lifeops.app.util.StepState

/*
 * An objective on the week board, laid out like the rest of an aspect: a header that reads like a
 * category ("Promotion"), the week's work on its steps as ordinary task rows beneath it (those are
 * drawn by the board with TaskRow), a quiet line for whatever step comes next, and the outcome —
 * a task-like row in the week it's due, a line with "Report success" before then.
 */

/** The objective's header under its aspect. Tapping it opens the objective. */
@Composable
internal fun ObjectiveHeader(
    item: ObjectiveWithSteps,
    aspectColor: Color,
    today: String,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onMarkUnsuccessful: () -> Unit
) {
    val objective = item.objective
    val done = item.steps.count { it.isDone }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    var menuOpen by remember { mutableStateOf(false) }
    var confirmUnsuccessful by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 24.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Flag, contentDescription = null, tint = aspectColor, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                objective.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                objectiveDueLine(item, today),
                style = MaterialTheme.typography.labelSmall,
                color = if (Objectives.isOverdue(item, today)) MaterialTheme.colorScheme.error else muted
            )
        }
        if (item.steps.isNotEmpty()) {
            Text("$done/${item.steps.size}", style = MaterialTheme.typography.labelMedium, color = muted)
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Objective actions", tint = muted)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menuOpen = false; onOpen() })
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                DropdownMenuItem(
                    text = { Text("Mark unsuccessful") },
                    onClick = { menuOpen = false; confirmUnsuccessful = true }
                )
            }
        }
    }

    if (confirmUnsuccessful) {
        AlertDialog(
            onDismissRequest = { confirmUnsuccessful = false },
            title = { Text("Mark unsuccessful?") },
            text = {
                Text(
                    "\"${objective.title}\" closes as not achieved and leaves your week. " +
                        "You can reopen it from Planning → Future."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmUnsuccessful = false; onMarkUnsuccessful() }) {
                    Text("Mark unsuccessful")
                }
            },
            dismissButton = { TextButton(onClick = { confirmUnsuccessful = false }) { Text("Cancel") } }
        )
    }
}

/**
 * A step with no task on this week — the next one waiting, or an open one not due yet. Quiet, so
 * the week's actual work stands out; an open one offers to start on it now, which puts it on the
 * week as a task.
 */
@Composable
internal fun ObjectiveStepHint(
    number: Int,
    step: ObjectiveStep,
    state: StepState,
    onStartNow: () -> Unit
) {
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val open = state == StepState.OPEN || state == StepState.OVERDUE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when (state) {
                StepState.LOCKED -> Icons.Default.Lock
                StepState.UPCOMING -> Icons.Default.Schedule
                else -> Icons.Default.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = faint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$number. ${step.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            objectiveStepDetail(number, step, state)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state == StepState.OVERDUE) MaterialTheme.colorScheme.error else faint
                )
            }
        }
        if (open) {
            TextButton(onClick = onStartNow, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Start now", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * How the objective closes. In the week it's due this is a row like any task's — its box reports
 * success, once every step is done. Before that week it's a line under the steps, with the same
 * "Report success" for an objective finished early.
 */
@Composable
internal fun ObjectiveOutcomeRow(
    item: ObjectiveWithSteps,
    aspectColor: Color,
    dueThisWeek: Boolean,
    onReportSuccess: () -> Unit
) {
    val objective = item.objective
    val ready = Objectives.canReportSuccess(item.steps)
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    var confirm by remember { mutableStateOf(false) }
    val due = DateUtil.formatDate(objective.dueDate)
    val detail = if (ready) "Objective result · due $due" else "Opens once every step is done · due $due"

    if (dueThisWeek) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(56.dp)
                        .background(aspectColor)
                )
                IconButton(onClick = { confirm = true }, enabled = ready, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.EmojiEvents,
                        contentDescription = "Report success",
                        tint = if (ready) aspectColor else muted.copy(alpha = 0.3f)
                    )
                }
                Column(Modifier.weight(1f).padding(end = 12.dp, top = 6.dp, bottom = 6.dp)) {
                    Text(objective.successCriteria, style = MaterialTheme.typography.bodyMedium)
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Complete when: ${objective.successCriteria}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(detail, style = MaterialTheme.typography.labelSmall, color = muted)
            }
            if (ready) {
                TextButton(onClick = { confirm = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Report success", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Report success?") },
            text = { Text("\"${objective.title}\" closes as achieved and leaves your week.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onReportSuccess() }) { Text("Report success") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}
