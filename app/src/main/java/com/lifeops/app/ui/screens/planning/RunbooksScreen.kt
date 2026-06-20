@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.planning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.RunbookWithSteps
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.screens.settings.SettingsViewModel

@Composable
fun RunbooksScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppHeader(navigationIcon = { BackNavIcon(onBack) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Runbooks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = viewModel::showNewRunbookDialog) {
                        Icon(Icons.Default.Add, contentDescription = "Add runbook")
                    }
                }
                Text(
                    "Reusable step lists stamped onto tasks as subtasks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(4.dp))
            }
            items(state.runbooks, key = { it.runbook.id }) { rb ->
                RunbookItem(
                    rb = rb,
                    onEdit = { viewModel.showEditRunbookDialog(rb) },
                    onDelete = { viewModel.deleteRunbook(rb.runbook.id) }
                )
            }
        }
    }

    if (state.showNewRunbookDialog) {
        NewRunbookDialog(
            onConfirm = { name, steps -> viewModel.addRunbook(name, steps) },
            onDismiss = viewModel::hideNewRunbookDialog
        )
    }

    state.editingRunbook?.let { rb ->
        EditRunbookDialog(
            rb = rb,
            onConfirm = { name, steps -> viewModel.saveRunbookEdit(rb, name, steps) },
            onDismiss = viewModel::hideEditRunbookDialog
        )
    }
}

@Composable
private fun RunbookItem(rb: RunbookWithSteps, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rb.runbook.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val n = rb.steps.size
                Text(
                    "$n step${if (n != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun RunbookStepEditor(steps: List<String>, onChange: (List<String>) -> Unit) {
    steps.forEachIndexed { i, step ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Text(
                "${i + 1}.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(24.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            OutlinedTextField(
                value = step,
                onValueChange = { v -> onChange(steps.toMutableList().also { it[i] = v }) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { onChange(steps.toMutableList().also { it.removeAt(i) }) },
                enabled = steps.size > 1,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Remove", modifier = Modifier.size(16.dp))
            }
        }
    }
    TextButton(onClick = { onChange(steps + "") }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Add Step")
    }
}

@Composable
private fun NewRunbookDialog(onConfirm: (String, List<String>) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf(listOf("")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Runbook") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Steps", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                RunbookStepEditor(steps = steps, onChange = { steps = it })
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val filtered = steps.filter { it.isNotBlank() }.map { it.trim() }
                    if (name.isNotBlank() && filtered.isNotEmpty()) onConfirm(name.trim(), filtered)
                },
                enabled = name.isNotBlank() && steps.any { it.isNotBlank() }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditRunbookDialog(rb: RunbookWithSteps, onConfirm: (String, List<String>) -> Unit, onDismiss: () -> Unit) {
    var name by remember(rb.runbook.id) { mutableStateOf(rb.runbook.name) }
    var steps by remember(rb.runbook.id) { mutableStateOf(rb.steps.map { it.label }.ifEmpty { listOf("") }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Runbook") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Steps", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                RunbookStepEditor(steps = steps, onChange = { steps = it })
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val filtered = steps.filter { it.isNotBlank() }.map { it.trim() }
                    if (name.isNotBlank() && filtered.isNotEmpty()) onConfirm(name.trim(), filtered)
                },
                enabled = name.isNotBlank() && steps.any { it.isNotBlank() }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
