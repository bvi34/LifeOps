package com.lifeops.app.ui.screens.thisweek

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.ui.components.ImportDialog
import com.lifeops.app.ui.components.TaskRow
import com.lifeops.app.ui.theme.parseColor

@Composable
fun ThisWeekScreen(viewModel: ThisWeekViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCloseConfirm by remember { mutableStateOf(false) }

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
            FloatingActionButton(onClick = viewModel::openImportDialog) {
                Icon(Icons.Default.Add, contentDescription = "Import tasks")
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
                    Text("Tap + to import tasks", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 80.dp)
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
                                onComplete = { viewModel.onCompleteTask(task) },
                                onSkip = { viewModel.onSkipTask(task.id) },
                                onCarryForward = { viewModel.onCarryForward(task) },
                                onEdit = {}
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
