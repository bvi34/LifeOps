@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.thisweek

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.ui.components.CreateTaskDialog
import com.lifeops.app.ui.components.ImportDialog
import com.lifeops.app.ui.components.TaskEditDialog
import com.lifeops.app.ui.components.TaskRow
import com.lifeops.app.ui.theme.parseColor

@Composable
fun ThisWeekScreen(viewModel: ThisWeekViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCloseConfirm by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("This Week") },
                actions = {
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
        } else if (state.groupedTasks.isEmpty()) {
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
                state.groupedTasks.forEach { group ->
                    item(key = "aspect_${group.aspect?.id ?: "none"}") {
                        AspectHeader(
                            name = group.aspect?.name ?: "Uncategorized",
                            color = group.aspectColor
                        )
                    }
                    group.categories.forEach { catGroup ->
                        if (catGroup.category != null || group.categories.size > 1) {
                            item(key = "cat_${catGroup.category?.id ?: "none"}") {
                                CategoryHeader(name = catGroup.category?.name ?: "General")
                            }
                        }
                        items(catGroup.tasks, key = { it.id }) { task ->
                            TaskRow(
                                task = task,
                                aspectColor = group.aspectColor,
                                notes = state.taskNotes[task.id] ?: emptyList(),
                                totalTimeMinutes = state.taskTimeMinutes[task.id] ?: 0,
                                onComplete = { viewModel.onCompleteTask(task) },
                                onSkip = { viewModel.onSkipTask(task.id) },
                                onCarryForward = { viewModel.onCarryForward(task) },
                                onEdit = { viewModel.startEditTask(task) },
                                onAddNote = { content -> viewModel.onAddNote(task.id, content) },
                                onLogTime = { minutes, note -> viewModel.onLogTime(task.id, minutes, note) }
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
            onPreview = viewModel::previewImport,
            onCommit = viewModel::commitImport,
            onDismiss = viewModel::closeImportDialog
        )
    }

    if (state.showCreateTaskDialog) {
        CreateTaskDialog(
            aspects = state.aspects.values.toList(),
            allCategories = state.categories,
            onConfirm = { title, note, aspectId, categoryId, priority, dueDate, hardDeadline ->
                viewModel.createTask(title, note, aspectId, categoryId, priority, dueDate, hardDeadline)
            },
            onDismiss = viewModel::hideCreateTaskDialog
        )
    }

    state.editingTask?.let { task ->
        TaskEditDialog(
            task = task,
            onSave = { title, priority, dueDate, hardDeadline ->
                viewModel.saveTaskEdit(title, priority, dueDate, hardDeadline)
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
private fun AspectHeader(name: String, color: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = parseColor(color)
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = parseColor(color)
        )
    }
}

@Composable
private fun CategoryHeader(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
    )
}
