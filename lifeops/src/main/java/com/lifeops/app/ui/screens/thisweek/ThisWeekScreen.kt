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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.lifeops.app.util.ReviewMetric
import com.lifeops.app.util.WeekCapacity
import com.lifeops.app.util.TodayEvents
import com.lifeops.app.util.Trend
import com.lifeops.app.util.WeatherAdvisory
import com.lifeops.app.util.WeekReview
import com.lifeops.app.ui.components.CreateTaskDialog
import com.lifeops.app.ui.components.ImportDialog
import com.lifeops.app.ui.components.TaskEditDialog
import com.lifeops.app.ui.components.TaskRow
import com.lifeops.app.ui.components.formatMinutes
import com.lifeops.app.ui.screens.planning.ObjectiveEditorHost
import com.lifeops.app.ui.screens.planning.ObjectivesViewModel
import com.lifeops.app.util.Objectives
import com.lifeops.app.ui.screens.wellness.ChoiceRow
import com.lifeops.app.ui.screens.wellness.RatingRow
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.ui.theme.priorityColor
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun ThisWeekScreen(
    viewModel: ThisWeekViewModel,
    objectivesViewModel: ObjectivesViewModel,
    onOpenOperation: (String) -> Unit = {},
    onOpenPerson: (String) -> Unit = {},
    onOpenCounter: (String) -> Unit = {},
    onOpenTask: (String) -> Unit = {},
    onOpenObjective: (String) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val objectivesState by objectivesViewModel.uiState.collectAsStateWithLifecycle()
    // Every active objective sits under its aspect, every week, until it's closed — like one of the
    // aspect's categories, with the week's work on its steps as task rows beneath it. An aspect
    // with an objective but no tasks this week still gets its header, so there's somewhere to sit.
    val objectivesByAspect = objectivesState.active.groupBy { it.objective.aspectId }
    val boardGroups = WeekTaskGrouping.withObjectiveAspects(
        state.groupedTasks, objectivesByAspect.keys, state.aspects.ifEmpty { objectivesState.aspects }
    )
    val weekEnd = state.week?.endDate ?: objectivesState.today
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

    // One week task row, as every list on this board draws it — under a category, or under an
    // objective as the week's work on one of its steps.
    val weekTaskRow: @Composable (Task, String, Color) -> Unit = { task, aspectColor, basketColor ->
        val isTimerActive = activeTimer?.taskId == task.id
        // The bar can only be set on the tasks it counts. A carried or queued
        // task belongs to another week, so ticking it would change nothing —
        // one already carrying the flag still shows it, read-only.
        val canSetBar = task.status == TaskStatus.PENDING ||
            task.status == TaskStatus.COMPLETED
        TaskRow(
            task = task,
            aspectColor = aspectColor,
            basketColor = basketColor,
            notes = state.taskNotes[task.id] ?: emptyList(),
            totalTimeMinutes = state.taskTimeMinutes[task.id] ?: 0,
            isTimerActive = isTimerActive,
            timerElapsedSeconds = { timerElapsedState.value },
            isPlanningMode = false,
            operationName = task.operationId?.let { pid -> state.operations.firstOrNull { it.id == pid }?.title },
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
            onQuickLogTime = { minutes -> viewModel.onLogTime(task.id, minutes, null) },
            onToggleCommitment =
                if (canSetBar) ({ viewModel.onToggleCommitment(task) }) else null
        )
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
        } else if (state.groupedTasks.isEmpty() && objectivesState.active.isEmpty()) {
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
                                capacity = state.capacity,
                                canCloseWeek = state.week?.isClosed == false,
                                onCloseWeek = { showCloseConfirm = true }
                            )
                        }
                    }
                }

                boardGroups.forEach { fullGroup ->
                    val aspectKey = WeekTaskGrouping.aspectKey(fullGroup)
                    val groupObjectives = objectivesByAspect[fullGroup.aspectId].orEmpty()
                    val (stepTasks, group) = WeekTaskGrouping.splitStepTasks(
                        fullGroup,
                        groupObjectives.flatMap { o -> o.steps.map { it.id } }.toSet()
                    )
                    val hasActive = fullGroup.categories.any { c -> c.tasks.any { it.status == TaskStatus.PENDING } }
                    val expanded = aspectExpanded[aspectKey] ?: (hasActive || groupObjectives.isNotEmpty())
                    item(key = aspectKey) {
                        AspectHeader(
                            name = group.aspect?.name ?: "Uncategorized",
                            color = group.aspectColor,
                            isArchived = group.aspect?.isArchived == true,
                            isExpanded = expanded,
                            // An open objective is unfinished business, tasks or not.
                            isComplete = !hasActive && groupObjectives.isEmpty(),
                            onToggle = { aspectExpanded[aspectKey] = !expanded }
                        )
                    }
                    if (expanded) groupObjectives.forEach { entry ->
                        val objective = entry.objective
                        val objectiveColor = parseColor(fullGroup.aspectColor)
                        val today = objectivesState.today
                        item(key = "objective|${objective.id}") {
                            ObjectiveHeader(
                                item = entry,
                                aspectColor = objectiveColor,
                                today = today,
                                onOpen = { onOpenObjective(objective.id) },
                                onEdit = { objectivesViewModel.startEdit(entry) },
                                onMarkUnsuccessful = { objectivesViewModel.markUnsuccessful(objective.id) }
                            )
                        }
                        // The week's work on this objective's steps, as task rows, in step order.
                        val position = entry.steps.withIndex().associate { (i, step) -> step.id to i }
                        val mine = stepTasks
                            .filter { it.objectiveStepId in position }
                            .sortedBy { position[it.objectiveStepId] }
                        items(mine, key = { it.id }) { task -> weekTaskRow(task, fullGroup.aspectColor, Color.Transparent) }
                        // What comes next, for steps with no task on this week.
                        val onWeek = mine.mapNotNull { it.objectiveStepId }.toSet()
                        val states = Objectives.states(entry.steps, today)
                        Objectives.focusIndices(entry.steps, today)
                            .filter { entry.steps[it].id !in onWeek }
                            .forEach { index ->
                                val step = entry.steps[index]
                                item(key = "objective-step|${step.id}") {
                                    ObjectiveStepHint(
                                        number = index + 1,
                                        step = step,
                                        state = states[index],
                                        onStartNow = { objectivesViewModel.workOnThisWeek(step.id) }
                                    )
                                }
                            }
                        item(key = "objective-outcome|${objective.id}") {
                            ObjectiveOutcomeRow(
                                item = entry,
                                aspectColor = objectiveColor,
                                dueThisWeek = objective.dueDate <= weekEnd,
                                onReportSuccess = { objectivesViewModel.reportSuccess(objective.id) }
                            )
                        }
                    }
                    if (expanded) group.categories.forEach { catGroup ->
                        val basketColor = catGroup.dominantPriority
                            ?.let { priorityColor(it.label) }
                            ?: Color.Transparent

                        if (catGroup.category != null || group.categories.size > 1) {
                            item(key = WeekTaskGrouping.categoryKey(group, catGroup)) {
                                CategoryHeader(
                                    name = catGroup.category?.name ?: "Uncategorized",
                                    priorityTint = basketColor
                                )
                            }
                        }
                        items(catGroup.tasks, key = { it.id }) { task ->
                            weekTaskRow(task, group.aspectColor, basketColor)
                        }
                    }
                }
            }
        }
    }

    ObjectiveEditorHost(objectivesViewModel)

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
            operations = state.operations,
            runbooks = state.runbooks,
            counters = state.counters,
            currentWeekStartDate = state.week?.startDate,
            currentWeekEndDate = state.week?.endDate,
            currentWeekClosed = state.week?.isClosed == true,
            onCreateOperation = viewModel::onCreateOperation,
            onConfirm = { title, note, aspectId, categoryId, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, operationId, runbookId, counterId, recurrenceIntervalWeeks, recurrenceDayOfMonth ->
                viewModel.createTask(title, note, aspectId, categoryId, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, operationId, runbookId, counterId, recurrenceIntervalWeeks, recurrenceDayOfMonth)
            },
            onDismiss = viewModel::hideCreateTaskDialog
        )
    }

    state.editingTask?.let { task ->
        TaskEditDialog(
            task = task,
            aspects = state.aspects.values.filter { !it.isArchived }.toList(),
            allCategories = state.categories,
            operations = state.operations,
            counters = state.counters,
            onCreateOperation = viewModel::onCreateOperation,
            onSave = { title, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, aspectId, categoryId, operationId, counterId, recurrenceIntervalWeeks, recurrenceDayOfMonth ->
                viewModel.saveTaskEdit(title, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, aspectId, categoryId, operationId, counterId, recurrenceIntervalWeeks, recurrenceDayOfMonth)
            },
            onDismiss = viewModel::cancelEditTask
        )
    }

    if (showCloseConfirm) {
        // The headline figures now live in the Week-in-Review section; these two feed the
        // consequence lines that explain what closing does to still-open tasks.
        val carriedCount = state.rawTasks.count { it.status == TaskStatus.CARRIED_FORWARD }
        val pendingCount = state.rawTasks.count { it.status == TaskStatus.PENDING }

        var selfRating by remember { mutableStateOf<Int?>(null) }
        var selfRatingNote by remember { mutableStateOf("") }
        var mentalReset by remember { mutableStateOf<Boolean?>(null) }
        var exhaustion by remember { mutableStateOf<Int?>(null) }
        // The retrospective is assembled off the UI thread from live tasks + trailing history.
        val review by produceState<WeekReview?>(initialValue = null) { value = viewModel.buildWeekReview() }

        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Week in Review") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    val r = review
                    if (r == null) {
                        Text(
                            "Reviewing your week…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    } else {
                        WeekReviewSection(r)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // What closing actually does to the open tasks.
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
                    // The honest mirror: your rating held against what the board actually shows.
                    selfRatingMirror(selfRating, review?.completionRate)?.let { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
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

                    Spacer(Modifier.height(2.dp))

                    // The two rest questions. A week's completion rate says nothing about whether
                    // you actually got your head back or how spent you are at the end of it, and
                    // those are what decide whether the next week starts from a standing position.
                    // Both optional — tapping the same answer again clears it.
                    ChoiceRow(
                        label = "Mental reset achieved?",
                        options = listOf("Yes", "No"),
                        selectedIndex = mentalReset?.let { if (it) 0 else 1 }
                    ) { index ->
                        val picked = index == 0
                        mentalReset = if (mentalReset == picked) null else picked
                    }
                    // Same 1–10 strip the wellness check-ins use, so "an 8 week" means the same
                    // thing here as it does in a daytime reading.
                    RatingRow(
                        label = "Overall exhaustion (1 fresh → 10 wiped out)",
                        value = exhaustion
                    ) { exhaustion = if (exhaustion == it) null else it }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCloseConfirm = false
                    viewModel.onCloseWeek(
                        selfRating,
                        selfRatingNote.takeIf { it.isNotBlank() },
                        mentalReset,
                        exhaustion
                    )
                }) { Text("Close Week") }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

/** A dry one-liner when your self-rating and the completion board disagree; null when they roughly agree. */
private fun selfRatingMirror(selfRating: Int?, completionRate: Float?): String? {
    if (selfRating == null || completionRate == null) return null
    val pct = (completionRate * 100).roundToInt()
    return when {
        selfRating >= 8 && completionRate < 0.5f -> "You rated this a $selfRating. The board says $pct% done."
        selfRating <= 3 && completionRate >= 0.75f -> "You rated this a $selfRating, but $pct% shipped. Credit yourself."
        else -> null
    }
}
