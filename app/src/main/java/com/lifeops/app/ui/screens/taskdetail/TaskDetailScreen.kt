@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.taskdetail

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskSource
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.data.model.TaskWeatherRequirement
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.components.formatMinutes
import com.lifeops.app.ui.theme.priorityColor
import com.lifeops.app.util.DateUtil

/**
 * Standalone, read-only view of a single task, reachable as its own route (`task_detail/{id}`).
 * Opening one from search — or any other entry point — pushes it onto the back stack, so dismissing
 * (Back) returns to whatever screen you came from. Unlike the This Week edit sheet, this loads any
 * task by id, including ones from past, closed weeks. It never mutates the task: the live editing
 * surface stays the This Week sheet.
 */
@Composable
fun TaskDetailScreen(
    viewModel: TaskDetailViewModel,
    onOpenProject: (projectId: String) -> Unit = {},
    onOpenCounter: (counterId: String) -> Unit = {},
    onOpenPerson: (personId: String) -> Unit = {},
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppHeader(navigationIcon = { BackNavIcon(onBack) }) }
    ) { padding ->
        val task = state.task
        when {
            state.loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.notFound || task == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "This task no longer exists.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            else -> TaskDetailBody(
                task = task,
                state = state,
                modifier = Modifier.padding(padding),
                onOpenProject = onOpenProject,
                onOpenCounter = onOpenCounter,
                onOpenPerson = onOpenPerson
            )
        }
    }
}

@Composable
private fun TaskDetailBody(
    task: Task,
    state: TaskDetailUiState,
    modifier: Modifier = Modifier,
    onOpenProject: (String) -> Unit,
    onOpenCounter: (String) -> Unit,
    onOpenPerson: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        // Read-only banner — always present on this screen, and it names the week a task belongs
        // to so a hit from search reads in context.
        ReadOnlyBanner(isCurrentWeek = state.isCurrentWeek, weekLabel = state.weekLabel)

        Spacer(Modifier.height(12.dp))
        Text(task.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
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
            if (task.source == TaskSource.PLANNED) {
                Text(
                    "planned",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }

        task.dueDate?.let { date ->
            Spacer(Modifier.height(6.dp))
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

        task.carryForwardReason?.let { reason ->
            Spacer(Modifier.height(4.dp))
            Text(
                "Delayed: ${reason.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }

        // Project
        state.project?.let { project ->
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onOpenProject(project.id) }
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
                Spacer(Modifier.width(4.dp))
                Text(project.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        // Weather requirement (summary only — read-only)
        state.weatherRequirement?.takeIf { !it.isEmpty }?.let { req ->
            SectionDivider()
            Text("Weather", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                weatherRequirementSummary(req),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        // Counter
        state.counter?.let { counter ->
            SectionDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenCounter(counter.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Counter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text(counter.name, style = MaterialTheme.typography.bodyLarge)
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Open counter",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }

        // People
        if (state.involvedPeople.isNotEmpty()) {
            SectionDivider()
            Text("People", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            PeopleChips(state.involvedPeople, onOpenPerson)
        }

        // Time tracking (totals only — read-only)
        SectionDivider()
        Text("Time Tracking", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (task.estimatedMinutes == null && state.totalTimeMinutes == 0) {
            Text(
                "No time logged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                task.estimatedMinutes?.let {
                    Text(
                        "Est: ${formatMinutes(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (state.totalTimeMinutes > 0) {
                    Text(
                        "Logged: ${formatMinutes(state.totalTimeMinutes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        // Checklist (read-only)
        if (state.subtasks.isNotEmpty()) {
            SectionDivider()
            val done = state.subtasks.count { it.isChecked }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Checklist", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "$done/${state.subtasks.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.height(4.dp))
            state.subtasks.forEach { subtask ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = subtask.isChecked, onCheckedChange = null, enabled = false)
                    Text(
                        subtask.label,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (subtask.isChecked) TextDecoration.LineThrough else null,
                        color = if (subtask.isChecked)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Resource costs (read-only)
        if (state.costEntries.isNotEmpty()) {
            SectionDivider()
            Text("Resource Costs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val resourceMap = state.costResources.associateBy { it.id }
            state.costEntries.forEach { entry ->
                val resourceName = resourceMap[entry.resourceId]?.name ?: entry.resourceId
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(resourceName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text("×${entry.amount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    entry.note?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    }
                    Text(
                        entry.recordedAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                HorizontalDivider()
            }
        }

        // Notes (read-only)
        SectionDivider()
        Text("Notes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (state.notes.isEmpty()) {
            Text(
                "No notes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        } else {
            SelectionContainer {
                Column {
                    state.notes.forEach { note ->
                        Column(Modifier.padding(vertical = 6.dp)) {
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

        // Attachments (view only)
        if (state.attachments.isNotEmpty()) {
            SectionDivider()
            Text("Attachments", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.attachments.forEach { att ->
                    val bitmap = rememberAttachmentBitmap(att.imageData)
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
                        ) { Text("!", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyBanner(isCurrentWeek: Boolean, weekLabel: String?) {
    val text = when {
        isCurrentWeek -> "Read-only view · edit this task from This Week"
        weekLabel != null -> "Read-only · $weekLabel"
        else -> "Read-only"
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PeopleChips(people: List<Person>, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        people.forEach { person ->
            AssistChip(onClick = { onOpen(person.id) }, label = { Text(person.name) })
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
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
    Surface(shape = MaterialTheme.shapes.extraSmall, color = color.copy(alpha = 0.15f)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun rememberAttachmentBitmap(data: String): ImageBitmap? =
    remember(data) {
        runCatching {
            val bytes = Base64.decode(data, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
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
