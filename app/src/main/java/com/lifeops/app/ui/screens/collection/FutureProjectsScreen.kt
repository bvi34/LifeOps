@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.FutureProjectStatus
import com.lifeops.app.data.repository.FutureProjectListItem

@Composable
fun FutureProjectsScreen(viewModel: FutureProjectViewModel, onOpenProject: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showCreateDialog() }) {
                Icon(Icons.Default.Add, contentDescription = "New future project")
            }
        }
    ) { padding ->
        if (state.projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No future projects yet. Tap + to jot one down.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            val (archived, active) = state.projects.partition { it.project.status == FutureProjectStatus.ARCHIVED }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(active, key = { it.project.id }) { item ->
                    FutureProjectCard(
                        item,
                        onClick = { onOpenProject(item.project.id) },
                        onToggleStatus = { viewModel.setStatus(item.project.id, FutureProjectStatus.ARCHIVED) }
                    )
                }
                if (archived.isNotEmpty()) {
                    item(key = "archived-header") {
                        Text(
                            "Archived",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    items(archived, key = { it.project.id }) { item ->
                        FutureProjectCard(
                            item,
                            onClick = { onOpenProject(item.project.id) },
                            onToggleStatus = { viewModel.setStatus(item.project.id, FutureProjectStatus.ACTIVE) }
                        )
                    }
                }
            }
        }
    }

    if (state.showCreateDialog) {
        CreateFutureProjectDialog(
            onDismiss = { viewModel.hideCreateDialog() },
            onConfirm = { title -> viewModel.createProject(title) }
        )
    }
}

@Composable
private fun FutureProjectCard(
    item: FutureProjectListItem,
    onClick: () -> Unit,
    onToggleStatus: () -> Unit
) {
    val isArchived = item.project.status == FutureProjectStatus.ARCHIVED
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.project.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isArchived) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.onSurface
                )
                item.latestNote?.let { latest ->
                    Text(
                        latest,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 2
                    )
                }
            }
            TextButton(onClick = onToggleStatus) {
                Text(if (isArchived) "Restore" else "Archive")
            }
        }
    }
}

@Composable
private fun CreateFutureProjectDialog(onDismiss: () -> Unit, onConfirm: (title: String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New future project") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title.trim()) }, enabled = title.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
