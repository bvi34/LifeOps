@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.lifeops.app.ui.screens.thisweek

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.WeekProgress
import com.lifeops.app.ui.components.CreateTaskDialog
import com.lifeops.app.ui.components.ImportDialog
import com.lifeops.app.ui.components.TaskDetailSheet
import com.lifeops.app.ui.components.TaskEditDialog
import com.lifeops.app.ui.components.TaskRow
import com.lifeops.app.ui.components.formatMinutes
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.ui.theme.priorityColor

@Composable
fun ThisWeekScreen(viewModel: ThisWeekViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCloseConfirm by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }

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
            TopAppBar(
                title = { Text("This Week") },
                actions = {
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch) viewModel.setSearchQuery("")
                    }) {
                        Icon(
                            if (showSearch) Icons.Default.SearchOff else Icons.Default.Search,
                            contentDescription = "Search"
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
        } else if (state.groupedTasks.isEmpty() && !showSearch) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No tasks this week", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to create or import tasks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                stickyHeader(key = "progress_search") {
                    Column {
                        WeekProgressHeader(progress = state.weekProgress)
                        AnimatedVisibility(visible = showSearch) {
                            SearchBar(
                                query = state.searchQuery,
                                onQueryChange = viewModel::setSearchQuery
                            )
                        }
                        SortBar(selected = state.sortOrder, onSelect = viewModel::setSortOrder)
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
                    item(key = "aspect_${group.aspect?.id ?: "none"}") {
                        AspectHeader(
                            name = group.aspect?.name ?: "Uncategorized",
                            color = group.aspectColor,
                            isArchived = group.aspect?.isArchived == true
                        )
                    }
                    group.categories.forEach { catGroup ->
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
                            val isTimerActive = state.activeTimer?.taskId == task.id
                            val timerElapsed = if (isTimerActive) state.activeTimer?.elapsedSeconds ?: 0 else 0
                            TaskRow(
                                task = task,
                                aspectColor = group.aspectColor,
                                basketColor = basketColor,
                                notes = state.taskNotes[task.id] ?: emptyList(),
                                totalTimeMinutes = state.taskTimeMinutes[task.id] ?: 0,
                                isTimerActive = isTimerActive,
                                timerElapsedSeconds = timerElapsed,
                                isPlanningMode = isPlanningMode,
                                onComplete = { viewModel.onCompleteTask(task) },
                                onUnComplete = { viewModel.onUnCompleteTask(task.id) },
                                onUnSkip = { viewModel.onUnSkipTask(task.id) },
                                onSkip = { viewModel.onSkipTask(task.id) },
                                onCarryForward = { viewModel.onCarryForward(task) },
                                onEdit = { viewModel.startEditTask(task) },
                                onStartTimer = { viewModel.startTimer(task.id) },
                                onStopTimer = { viewModel.stopTimer(saveEntry = true) },
                                onOpenDetail = { viewModel.openDetail(task.id) },
                                onMoveUp = { viewModel.movePlanningTask(task.id, -1) },
                                onMoveDown = { viewModel.movePlanningTask(task.id, 1) }
                            )
                        }
                    }
                }
            }
        }
    }

    state.detailTaskId?.let { taskId ->
        val allTasks = state.groupedTasks.flatMap { g -> g.categories.flatMap { it.tasks } }
        val detailTask = allTasks.firstOrNull { it.id == taskId }
        if (detailTask != null) {
            val isTimerActive = state.activeTimer?.taskId == taskId
            val timerElapsed = if (isTimerActive) state.activeTimer?.elapsedSeconds ?: 0 else 0
            val isPomodoroActive = isTimerActive && state.activeTimer?.isPomodoro == true
            TaskDetailSheet(
                task = detailTask,
                notes = state.taskNotes[taskId] ?: emptyList(),
                totalTimeMinutes = state.taskTimeMinutes[taskId] ?: 0,
                isTimerActive = isTimerActive,
                timerElapsedSeconds = timerElapsed,
                isPomodoroActive = isPomodoroActive,
                onDismiss = viewModel::closeDetail,
                onAddNote = { content -> viewModel.onAddNote(taskId, content) },
                onEdit = { viewModel.startEditTask(detailTask); viewModel.closeDetail() },
                onCarryForward = { viewModel.closeDetail(); viewModel.onCarryForward(detailTask) },
                onStartTimer = { viewModel.startTimer(taskId) },
                onStopTimer = { viewModel.stopTimer(saveEntry = true) },
                onStartPomodoro = { viewModel.startTimer(taskId, isPomodoro = true) },
                onLogTime = { minutes, note -> viewModel.onLogTime(taskId, minutes, note) }
            )
        }
    }

    if (state.importDialogOpen) {
        ImportDialog(
            json = state.importJson,
            onJsonChange = viewModel::onImportJsonChange,
            preview = state.importPreview,
            error = state.importError,
            onPreview = viewModel::previewImport,
            onCommit = viewModel::commitImport,
            onDismiss = viewModel::closeImportDialog
        )
    }

    if (state.showCreateTaskDialog) {
        CreateTaskDialog(
            aspects = state.aspects.values.toList(),
            allCategories = state.categories,
            onConfirm = { title, note, aspectId, categoryId, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes ->
                viewModel.createTask(title, note, aspectId, categoryId, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes)
            },
            onDismiss = viewModel::hideCreateTaskDialog
        )
    }

    state.editingTask?.let { task ->
        TaskEditDialog(
            task = task,
            aspects = state.aspects.values.filter { !it.isArchived }.toList(),
            allCategories = state.categories,
            onSave = { title, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, aspectId, categoryId ->
                viewModel.saveTaskEdit(title, priority, dueDate, hardDeadline, isRecurring, estimatedMinutes, aspectId, categoryId)
            },
            onDismiss = viewModel::cancelEditTask
        )
    }

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Close This Week?") },
            text = { Text("Pending tasks without hard deadlines become incomplete. Hard deadline tasks become expired.") },
            confirmButton = {
                Button(onClick = {
                    showCloseConfirm = false
                    viewModel.onCloseWeek()
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

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        placeholder = { Text("Search tasks…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
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
private fun AspectHeader(name: String, color: String, isArchived: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
    }
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
