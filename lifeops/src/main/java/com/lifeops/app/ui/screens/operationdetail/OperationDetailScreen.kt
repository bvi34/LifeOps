@file:OptIn(ExperimentalMaterial3Api::class)
package com.lifeops.app.ui.screens.operationdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.operations.backupkit.AppId
import com.repository.app.logic.DocumentKind
import com.repository.app.ui.attach.DocumentsPanel
import com.lifeops.app.data.model.OperationStatus
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.util.DateUtil
import com.lifeops.app.ui.components.formatMinutes
import com.lifeops.app.ui.theme.CompletedGreen

@Composable
fun OperationDetailScreen(
    viewModel: OperationDetailViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val operation = state.operation

    Scaffold(
        topBar = {
            AppHeader(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (operation != null) {
                        if (operation.status == OperationStatus.ACTIVE) {
                            TextButton(onClick = { viewModel.setOperationStatus(OperationStatus.COMPLETED) }) {
                                Text("Complete")
                            }
                        } else {
                            TextButton(onClick = { viewModel.setOperationStatus(OperationStatus.ACTIVE) }) {
                                Text("Reactivate")
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header stats
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val completed = state.tasks.count { it.status == TaskStatus.COMPLETED }
                        val total = state.tasks.size
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatChip("$completed/$total tasks", "completed")
                            StatChip(formatMinutes(state.totalTimeMinutes), "total time")
                            StatChip("${state.totalPoints} pts", "earned")
                        }
                    }
                }
            }
            // Brainstorm notes carried over from the future operation this one was promoted from
            if (state.brainstormNotes.isNotEmpty()) {
                item {
                    Text(
                        "Brainstorm",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(state.brainstormNotes) { note ->
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(note.content, style = MaterialTheme.typography.bodySmall)
                        Text(
                            DateUtil.localDateKey(note.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
            // The paperwork about this operation, on the household's shelf.
            //
            // Not task attachments, which are a different thing and stay where they are: a photo
            // pinned to "call the roofer back" is working material for one week, whereas the quote,
            // the permit and the warranty outlive the operation entirely and are what somebody goes
            // looking for two years later. Those belong on the shelf, findable without remembering
            // which app took them in.
            if (operation != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Documents",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            DocumentsPanel(
                                appKey = AppId.LIFEOPS.key,
                                recordKey = operation.id,
                                recordLabel = operation.title,
                                kinds = listOf(
                                    DocumentKind.CONTRACT,
                                    DocumentKind.RECEIPT,
                                    DocumentKind.REPORT,
                                    DocumentKind.WARRANTY,
                                    DocumentKind.CORRESPONDENCE,
                                    DocumentKind.OTHER
                                ),
                                emptyLine = "The quote, the permit, the warranty — what outlives the " +
                                    "operation and gets looked for years later."
                            )
                        }
                    }
                }
            }
            // Tasks by week
            state.tasksByWeek.forEach { (week, tasks) ->
                item {
                    Text(
                        "Week of ${week.startDate.take(10)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(tasks) { task ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (task.status == TaskStatus.COMPLETED) {
                            Icon(Icons.Default.CheckCircle, null, tint = CompletedGreen, modifier = Modifier.size(16.dp))
                        } else {
                            Spacer(Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(task.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (task.carriedCount > 0) {
                            Text(
                                "↩${task.carriedCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
            // Notes timeline
            if (state.notes.isNotEmpty()) {
                item {
                    Text(
                        "Notes Timeline",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(state.notes) { (taskTitle, note) ->
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            taskTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        Text(note.content, style = MaterialTheme.typography.bodySmall)
                        Text(
                            DateUtil.localDateKey(note.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}
