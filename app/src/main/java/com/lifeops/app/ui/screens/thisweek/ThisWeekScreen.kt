@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.lifeops.app.ui.screens.thisweek

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.data.model.TemplateWithTasks
import com.lifeops.app.data.model.WeatherAlert
import com.lifeops.app.data.model.WeekProgress
import com.lifeops.app.util.TodayEvents
import com.lifeops.app.util.WeatherAdvisory
import com.lifeops.app.ui.components.CreateTaskDialog
import com.lifeops.app.ui.components.ImportDialog
import com.lifeops.app.ui.components.TaskEditDialog
import com.lifeops.app.ui.components.TaskRow
import com.lifeops.app.ui.components.formatMinutes
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.ui.theme.priorityColor
import java.time.LocalDate
import kotlinx.coroutines.delay

@Composable
fun ThisWeekScreen(
    viewModel: ThisWeekViewModel,
    onOpenProject: (String) -> Unit = {},
    onOpenPerson: (String) -> Unit = {},
    onOpenCounter: (String) -> Unit = {},
    onOpenTask: (String) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // `activeTimer` changes only on start/stop. `timerElapsedState` ticks each second but is
    // intentionally NOT read at this scope — it's read via a deferred lambda inside the active
    // row/sheet so the per-second tick doesn'sq`t recompose the whole screen.
    val activeTimer by viewModel.activeTimer.collectAsStateWithLifecycle()
    val timerElapsedState = viewModel.timerElapsedSeconds.collectAsStateWithLifecycle()
    var showCloseConfirm by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }
    // Per-aspect expand/collapse. The default follows whether the aspect still has open
    // work (expanded when it does); an explicit user tap is remembered here and overrides it.
    val aspectExpanded = remember { mutableStateMapOf<String, Boolean>() }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.undoEvents.collect { event ->
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (event.action) {
                    UndoEventAction.UNSKIP -> viewModel.onUnSkipTask(event.taskId)
                    UndoEventAction.UN_CARRY_FORWARD -> viewModel.onUnCarryForward(event.taskId)
                    UndoEventAction.UN_UNSUCCESSFUL -> viewModel.onUnUnsuccessTask(event.taskId)
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.stopTimer(saveEntry = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedVisibility(visible = fabExpanded) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ExtendedFloatingActionButton(
                            text = { Text("New Task") },
                            icon = { Icon(Icons.Default.Create, contentDescription = null) },
                            onClick = { fabExpanded = false; viewModel.showCreateTaskDialog() },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        ExtendedFloatingActionButton(
                            text = { Text("Template") },
                            icon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = { fabExpanded = false; viewModel.showTemplatePicker() },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        ExtendedFloatingActionButton(
                            text = { Text("Import JSON") },
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = { fabExpanded = false; viewModel.openImportDialog() },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                FloatingActionButton(onClick = { fabExpanded = !fabExpanded }) {
                    Icon(
                        if (fabExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (fabExpanded) "Close menu" else "Add"
                    )
                }
            }
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.groupedTasks.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.CalendarToday,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                    )
                    Text(
                        "No tasks this week",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        "Tap + to add tasks or import from JSON",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                // Pinned header, in the order the app leads with: weather alerts, then the
                // "happening today" event banner, then the weekly task + time summary. The forecast
                // strip and tab/search row sit above this list in the Week hub shell.
                stickyHeader(key = "week_header") {
                    Surface {
                        Column {
                            WeatherAlertsBanner(
                                advisories = state.weatherAdvisories,
                                alerts = state.weatherAlerts
                            )
                            EventBanner(
                                tasks = state.rawTasks,
                                busyBlocks = state.busyBlocks,
                                onOpenTask = onOpenTask
                            )
                            WeekProgressHeader(
                                progress = state.weekProgress,
                                canCloseWeek = state.week?.isClosed == false,
                                onCloseWeek = { showCloseConfirm = true }
                            )
                        }
                    }
                }

                state.groupedTasks.forEach { group ->
                    val aspectKey = group.aspect?.id ?: "none"
                    val hasActive = group.categories.any { c -> c.tasks.any { it.status == TaskStatus.PENDING } }
                    val expanded = aspectExpanded[aspectKey] ?: hasActive
                    item(key = "aspect_$aspectKey") {
                        AspectHeader(
                            name = group.aspect?.name ?: "Uncategorized",
                            color = group.aspectColor,
                            isArchived = group.aspect?.isArchived == true,
                            isExpanded = expanded,
                            isComplete = !hasActive,
                            onToggle = { aspectExpanded[aspectKey] = !expanded }
                        )
                    }
                    if (expanded) group.categories.forEach { catGroup ->
                        val basketColor = catGroup.dominantPriority
                            ?.let { priorityColor(it.label) }
                            ?: Color.Transparent

                        if (catGroup.category != null || group.categories.size > 1) {
                            item(key = "cat_${catGroup.categoryId ?: catGroup.category?.id ?: "none"}") {
                                CategoryHeader(
                                    name = catGroup.category?.name ?: "Uncategorized",
                                    priorityTint = basketColor
                                )
                            }
                        }
                        items(catGroup.tasks, key = { it.id }) { task ->
                            val isTimerActive = activeTimer?.taskId == task.id
                            TaskRow(
                                task = task,
                                aspectColor = group.aspectColor,
                                basketColor = basketColor,
                                notes = state.taskNotes[task.id] ?: emptyList(),
                                totalTimeMinutes = state.taskTimeMinutes[task.id] ?: 0,
                                isTimerActive = isTimerActive,
                                timerElapsedSeconds = { timerElapsedState.value },
                                isPlanningMode = false,
                                projectName = task.projectId?.let { pid -> state.projects.firstOrNull { it.id == pid }?.title },
                                weatherFit = state.taskWeatherFit[task.id],
                                counterName = task.counterId?.let { cid -> state.counters.firstOrNull { it.id == cid }?.name },
                                peopleNames = (state.taskPeople[task.id] ?: emptyList())
                                    .mapNotNull { pid -> state.people.firstOrNull { it.id == pid }?.name },
                                subtaskProgress = state.subtaskCounts[task.id],
                                onComplete = { viewModel.onCompleteTask(task) },
                                onUnComplete = { viewModel.onUnCompleteTask(task.id) },
                                onUnSkip = { viewModel.onUnSkipTask(task.id) },
                                onSkip = { viewModel.onSkipTask(task.id) },
                                onCarryForward = { },
                                onEdit = { viewModel.startEditTask(task) },
                                onStartTimer = { viewModel.startTimer(task.id) },
                                onStopTimer = { viewModel.stopTimer(saveEntry = true) },
                                onOpenDetail = { onOpenTask(task.id) },
                                onMoveUp = { viewModel.movePlanningTask(task.id, -1) },
                                onMoveDown = { viewModel.movePlanningTask(task.id, 1) },
                                onQuickLogTime = { minutes -> viewModel.onLogTime(task.id, minutes, null) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.importDialogOpen) {
        ImportDialog(
            json = state.importJson,
            onJsonChange = viewModel::onImportJsonChange,
            preview = state.importPreview,
            error = state.importError,
            includeUnknownAsNotes = state.includeUnknownAsNotes,
            onIncludeUnknownChange = viewModel::setIncludeUnknownAsNotes,
            onPreview = viewModel::previewImport,
            onCommit = viewModel::commitImport,
            onDismiss = viewModel::closeImportDialog
        )
    }

    if (state.showTemplatePickerDialog) {
        TemplatePickerDialog(
            templates = state.availableTemplates,
            onPick = { templateId -> viewModel.applyTemplate(templateId) },
            onDismiss = viewModel::hideTemplatePicker
        )
    }

    if (state.showCreateTaskDialog) {
        CreateTaskDialog(
            aspects = state.aspects.values.toList(),
            allCategories = state.categories,
            projects = state.projects,
            runbooks = state.runbooks,
            counters = state.counters,
            currentWeekEndDate = state.week?.endDate,
            onCreateProject = viewModel::onCreateProject,
            onConfirm = { title, note, aspectId, categoryId, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, projectId, runbookId, counterId, recurrenceIntervalWeeks, recurrenceDayOfMonth ->
                viewModel.createTask(title, note, aspectId, categoryId, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, projectId, runbookId, counterId, recurrenceIntervalWeeks, recurrenceDayOfMonth)
            },
            onDismiss = viewModel::hideCreateTaskDialog
        )
    }

    state.editingTask?.let { task ->
        TaskEditDialog(
            task = task,
            aspects = state.aspects.values.filter { !it.isArchived }.toList(),
            allCategories = state.categories,
            projects = state.projects,
            counters = state.counters,
            onCreateProject = viewModel::onCreateProject,
            onSave = { title, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, aspectId, categoryId, projectId, counterId, recurrenceIntervalWeeks, recurrenceDayOfMonth ->
                viewModel.saveTaskEdit(title, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, aspectId, categoryId, projectId, counterId, recurrenceIntervalWeeks, recurrenceDayOfMonth)
            },
            onDismiss = viewModel::cancelEditTask
        )
    }

    if (showCloseConfirm) {
        val completedCount = state.rawTasks.count { it.status == TaskStatus.COMPLETED }
        val totalRelevant = state.rawTasks.count { it.status != TaskStatus.CARRIED_FORWARD && it.status != TaskStatus.QUEUED }
        val carriedCount = state.rawTasks.count { it.status == TaskStatus.CARRIED_FORWARD }
        val pendingCount = state.rawTasks.count { it.status == TaskStatus.PENDING }
        val hdHits = state.rawTasks.count { it.hardDeadline && it.status == TaskStatus.COMPLETED }
        val hdExpired = state.rawTasks.count { it.hardDeadline && it.status != TaskStatus.COMPLETED && it.status != TaskStatus.PENDING }
        val totalMinutes = state.taskTimeMinutes.values.sum()

        var selfRating by remember { mutableStateOf<Int?>(null) }
        var selfRatingNote by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Close This Week?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "$completedCount / $totalRelevant tasks completed",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (totalMinutes > 0) {
                        Text(
                            "Time logged: ${formatMinutes(totalMinutes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                    if (carriedCount > 0) {
                        Text(
                            "$carriedCount task${if (carriedCount != 1) "s" else ""} will carry to next week",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (pendingCount > 0) {
                        Text(
                            "$pendingCount pending → will become incomplete",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    if (hdHits + hdExpired > 0) {
                        Text(
                            "Hard deadlines: $hdHits hit, $hdExpired expired",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hdExpired > 0) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        "Explicitly carried tasks will appear in next week's list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        "How do you think this week went?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        (0..10).forEach { n ->
                            val isSelected = selfRating == n
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .size(26.dp)
                                    .clickable { selfRating = if (selfRating == n) null else n }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        n.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                    if (selfRating != null) {
                        OutlinedTextField(
                            value = selfRatingNote,
                            onValueChange = { selfRatingNote = it },
                            label = { Text("Why? (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            maxLines = 3
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCloseConfirm = false
                    viewModel.onCloseWeek(selfRating, selfRatingNote.takeIf { it.isNotBlank() })
                }) { Text("Close Week") }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

/** Weekly task + time summary: completed/total, a progress bar, total logged time, and the
 *  close-week action (the only place the week is closed now that the old top app bar is gone). */
@Composable
private fun WeekProgressHeader(
    progress: WeekProgress,
    canCloseWeek: Boolean = false,
    onCloseWeek: () -> Unit = {}
) {
    if (progress.totalCount == 0) return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${progress.completedCount}/${progress.totalCount} done",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            if (progress.totalCount > 0) {
                LinearProgressIndicator(
                    progress = { progress.completedCount.toFloat() / progress.totalCount },
                    modifier = Modifier.weight(1f).height(4.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (progress.totalTimeMinutes > 0) {
                Text(
                    formatMinutes(progress.totalTimeMinutes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (canCloseWeek) {
                IconButton(onClick = onCloseWeek, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Close week",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Weather alerts banner: the highest-severity active alert/advisory for the tracked location,
 * shown as a persistent error-coloured strip so a storm or warning is visible above the task list.
 * Prefers an actionable advisory (with its delay hint) over the raw alert headline.
 */
@Composable
private fun WeatherAlertsBanner(
    advisories: List<WeatherAdvisory>,
    alerts: List<WeatherAlert>
) {
    val advisory = advisories.maxByOrNull { it.severityRank }
    val topAlert = alerts.maxByOrNull { it.severity.rank }
    val bannerText = advisory?.let { adv ->
        adv.delayHint?.let { "${adv.headline} · $it" } ?: adv.headline
    } ?: topAlert?.event ?: return

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                bannerText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * "Happening today" banner: a single always-visible line that rotates through today's calendar
 * events (busy blocks) and the tasks due today, so whatever needs attention today is glanceable
 * without scrolling or opening anything. Hidden entirely when nothing is on for today. Tapping a
 * task item opens it; a small dot pager hints at how many items are in the rotation.
 */
@Composable
private fun EventBanner(
    tasks: List<Task>,
    busyBlocks: List<BusyBlock>,
    onOpenTask: (String) -> Unit
) {
    val items = remember(tasks, busyBlocks) {
        TodayEvents.forDate(tasks, busyBlocks, LocalDate.now())
    }
    if (items.isEmpty()) return

    var index by remember(items.size) { mutableStateOf(0) }
    // Auto-advance the rotation once there's more than one thing on today.
    LaunchedEffect(items.size) {
        if (items.size > 1) {
            while (true) {
                delay(4000)
                index = (index + 1) % items.size
            }
        }
    }
    val current = items[index.coerceIn(0, items.lastIndex)]
    val isTask = current.kind == TodayEvents.Kind.TASK_DUE

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = current.taskId != null) {
                current.taskId?.let(onOpenTask)
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (isTask) Icons.Default.Assignment else Icons.Default.Event,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            AnimatedContent(
                targetState = current,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "today-event",
                modifier = Modifier.weight(1f)
            ) { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    item.detail?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            maxLines = 1
                        )
                    }
                }
            }
            if (items.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    items.forEachIndexed { i, _ ->
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                        .copy(alpha = if (i == index) 0.9f else 0.3f),
                                    RoundedCornerShape(50)
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AspectHeader(
    name: String,
    color: String,
    isArchived: Boolean = false,
    isExpanded: Boolean = true,
    isComplete: Boolean = false,
    onToggle: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = parseColor(color).copy(alpha = if (isArchived) 0.4f else 1f)
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (isArchived) "$name (archived)" else name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = parseColor(color).copy(alpha = if (isArchived) 0.5f else 1f)
        )
        if (isComplete) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "All tasks resolved",
                tint = parseColor(color).copy(alpha = if (isArchived) 0.5f else 1f),
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun TemplatePickerDialog(
    templates: List<TemplateWithTasks>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply Template") },
        text = {
            if (templates.isEmpty()) {
                Text(
                    "No templates yet. Create one in Settings → Templates.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    templates.forEach { t ->
                        OutlinedCard(
                            onClick = { onPick(t.template.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(t.template.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                val count = t.tasks.size
                                Text(
                                    "$count task${if (count != 1) "s" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                if (t.tasks.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        t.tasks.take(3).joinToString(", ") { it.title } +
                                            if (t.tasks.size > 3) "…" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
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
private fun CategoryHeader(name: String, priorityTint: Color = Color.Transparent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (priorityTint != Color.Transparent) {
            Surface(
                modifier = Modifier.size(6.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = priorityTint
            ) {}
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}
