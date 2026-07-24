@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.lifeops.app.ui.screens.thisweek

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.CostResource
import com.lifeops.app.data.model.Counter
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskCostEntry
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.data.model.TemplateWithTasks
import com.lifeops.app.data.model.WeatherAlert
import com.lifeops.app.data.model.WeekProgress
import com.lifeops.app.util.WeatherAdvisory
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.CreateTaskDialog
import com.lifeops.app.ui.components.ImportDialog
import com.lifeops.app.ui.components.TaskEditDialog
import com.lifeops.app.ui.components.TaskRow
import com.lifeops.app.ui.components.formatMinutes
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.ui.theme.priorityColor
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
    var showSearch by remember { mutableStateOf(false) }
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

    val isPlanningMode = state.sortOrder == SortOrder.PLANNING

    Scaffold(
        topBar = {
            AppHeader(
                // Nested inside the Week hub's Scaffold — don't re-apply the status-bar inset.
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    // Inline filter over THIS week's list only. The header's global search
                    // (magnifier) is the one that finds and opens tasks from past weeks, so this
                    // uses a filter icon to avoid reading as a second search action.
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch) viewModel.setSearchQuery("")
                    }) {
                        Icon(
                            if (showSearch) Icons.Default.FilterListOff else Icons.Default.FilterList,
                            contentDescription = if (showSearch) "Clear filter" else "Filter tasks"
                        )
                    }
                    IconButton(onClick = {
                        viewModel.setSortOrder(if (isPlanningMode) SortOrder.DEFAULT else SortOrder.PLANNING)
                    }) {
                        Icon(
                            Icons.Default.Reorder,
                            contentDescription = "Planning mode",
                            tint = if (isPlanningMode) MaterialTheme.colorScheme.primary
                                   else LocalContentColor.current
                        )
                    }
                    state.week?.let { week ->
                        if (!week.isClosed) {
                            IconButton(onClick = { showCloseConfirm = true }) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Close week")
                            }
                        }
                    }
                }
            )
        },
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
            if (showSearch) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        "No tasks match \"${state.searchQuery}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
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
            }
        } else if (isPlanningMode) {
            // Flat drag-to-reorder list for planning
            val flatTasks = remember(state.rawTasks) {
                state.rawTasks.sortedWith(compareBy<Task> { it.sortOrder }.thenBy { it.createdAt })
            }
            val lazyListState = rememberLazyListState()
            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                viewModel.onReorderTask(from.index, to.index, flatTasks)
            }
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(flatTasks, key = { it.id }) { task ->
                    ReorderableItem(reorderableState, key = task.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .shadow(elevation),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDragging)
                                    MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = "Drag to reorder",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier
                                        .size(20.dp)
                                        .draggableHandle()
                                )
                                Spacer(Modifier.width(12.dp))
                                val aspect = state.aspects[task.aspectId]
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .height(40.dp)
                                        .background(
                                            parseColor(aspect?.color ?: "#6200EE"),
                                            RoundedCornerShape(2.dp)
                                        )
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        task.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val categoryName = task.categoryId?.let { state.categories[it]?.name }
                                    val subtitleParts = listOfNotNull(
                                        aspect?.name,
                                        categoryName,
                                        task.priority.label.replaceFirstChar { it.uppercase() }
                                    )
                                    Text(
                                        subtitleParts.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                stickyHeader(key = "progress_search") {
                    Column {
                        WeekProgressHeader(progress = state.weekProgress)
                        WeekDashboard(
                            advisories = state.weatherAdvisories,
                            alerts = state.weatherAlerts,
                            counters = state.counters,
                            counterTotals = state.counterWeeklyTotals,
                            costEntries = state.taskCostEntries,
                            costResources = state.costResources,
                            onOpenCounter = onOpenCounter
                        )
                        AnimatedVisibility(visible = showSearch) {
                            FilterBar(
                                query = state.searchQuery,
                                onQueryChange = viewModel::setSearchQuery
                            )
                        }
                        SortBar(selected = state.sortOrder, onSelect = viewModel::setSortOrder)
                        // Overdue/Due-today filter chip
                        if (state.week?.isClosed == false && state.rawTasks.isNotEmpty()) {
                            val showOverdueOnly = state.showOverdueOnly
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = showOverdueOnly,
                                    onClick = viewModel::toggleOverdueFilter,
                                    label = { Text("Overdue / Due today") },
                                    leadingIcon = if (showOverdueOnly) {
                                        { Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                        }
                    }
                }

                if (state.groupedTasks.isEmpty() && state.searchQuery.isNotBlank()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No tasks match \"${state.searchQuery}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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

@Composable
private fun WeekProgressHeader(progress: WeekProgress) {
    if (progress.totalCount == 0) return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
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
                    modifier = Modifier.width(80.dp).height(4.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (progress.totalTimeMinutes > 0) {
                Text(
                    formatMinutes(progress.totalTimeMinutes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

/**
 * The week "at a glance" strip under the progress bar: a severe-weather banner when there's an
 * active alert/advisory, this week's counter totals, and a spend rollup — all pulled from data
 * the app already tracks elsewhere, so This Week reads as a composite of the whole week.
 */
@Composable
private fun WeekDashboard(
    advisories: List<WeatherAdvisory>,
    alerts: List<WeatherAlert>,
    counters: List<Counter>,
    counterTotals: Map<String, Int>,
    costEntries: Map<String, List<TaskCostEntry>>,
    costResources: List<CostResource>,
    onOpenCounter: (String) -> Unit
) {
    // Severe-weather banner: prefer an actionable advisory, else the top active alert.
    val advisory = advisories.maxByOrNull { it.severityRank }
    val topAlert = alerts.maxByOrNull { it.severity.rank }
    val bannerText = advisory?.let { adv ->
        adv.delayHint?.let { "${adv.headline} · $it" } ?: adv.headline
    } ?: topAlert?.event

    // Counters that actually logged something this week, most-active first.
    val activeCounters = counters
        .mapNotNull { c -> counterTotals[c.id]?.takeIf { it > 0 }?.let { c to it } }
        .sortedByDescending { it.second }

    // Spend rollup: total per cost resource used this week.
    val spendByResource = costEntries.values.flatten()
        .groupBy { it.resourceId }
        .mapValues { (_, entries) -> entries.sumOf { it.amount } }
    val resourceName = costResources.associate { it.id to it.name }

    if (bannerText == null && activeCounters.isEmpty() && spendByResource.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        if (bannerText != null) {
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
        if (activeCounters.isNotEmpty() || spendByResource.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                activeCounters.forEach { (counter, total) ->
                    AssistChip(
                        onClick = { onOpenCounter(counter.id) },
                        label = { Text("${counter.name} $total", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                spendByResource.forEach { (resourceId, amount) ->
                    val name = resourceName[resourceId] ?: "Spend"
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Text(
                            "$name: $amount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        placeholder = { Text("Filter this week…") },
        leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            }
        },
        singleLine = true
    )
}

@Composable
private fun SortBar(selected: SortOrder, onSelect: (SortOrder) -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SortOrder.entries.forEach { order ->
                FilterChip(
                    selected = selected == order,
                    onClick = { onSelect(order) },
                    label = { Text(order.label, style = MaterialTheme.typography.labelSmall) }
                )
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
