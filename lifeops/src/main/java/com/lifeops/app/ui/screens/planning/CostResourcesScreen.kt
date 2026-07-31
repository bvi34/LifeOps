@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.planning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.CostResource
import com.lifeops.app.data.model.ResourceResetCycle
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.screens.settings.SettingsViewModel

@Composable
fun CostResourcesScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
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
                        "Cost Resources",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = viewModel::showNewCostResourceDialog) {
                        Icon(Icons.Default.Add, contentDescription = "Add cost resource")
                    }
                }
                Text(
                    "Track usage of external resources (e.g. Claude Code, Apollo Credits) per task. Does not affect scoring.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(4.dp))
            }
            items(state.costResources, key = { it.id }) { resource ->
                CostResourceItem(
                    resource = resource,
                    onToggleActive = { viewModel.setCostResourceActive(resource.id, !resource.isActive) }
                )
            }
        }
    }

    if (state.showNewCostResourceDialog) {
        NewCostResourceDialog(
            onConfirm = { name, resetCycle, capacity ->
                viewModel.addCostResource(name, resetCycle, capacity)
            },
            onDismiss = viewModel::hideNewCostResourceDialog
        )
    }
}

@Composable
private fun CostResourceItem(resource: CostResource, onToggleActive: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    resource.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (resource.isActive) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                val cycleLabel = when (resource.resetCycle) {
                    ResourceResetCycle.WEEKLY -> "Resets weekly"
                    ResourceResetCycle.MONTHLY -> "Resets monthly"
                    ResourceResetCycle.NEVER -> "No reset"
                }
                val capacityLabel = resource.capacity?.let { " · cap: $it" } ?: ""
                Text(
                    cycleLabel + capacityLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Switch(
                checked = resource.isActive,
                onCheckedChange = { onToggleActive() }
            )
        }
    }
}

@Composable
private fun NewCostResourceDialog(
    onConfirm: (name: String, resetCycle: String, capacity: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedCycle by remember { mutableStateOf(ResourceResetCycle.MONTHLY) }
    var capacity by remember { mutableStateOf("") }
    var dropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Cost Resource") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = when (selectedCycle) {
                            ResourceResetCycle.WEEKLY -> "Weekly"
                            ResourceResetCycle.MONTHLY -> "Monthly"
                            ResourceResetCycle.NEVER -> "Never"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Reset cycle") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        ResourceResetCycle.entries.forEach { cycle ->
                            DropdownMenuItem(
                                text = {
                                    Text(when (cycle) {
                                        ResourceResetCycle.WEEKLY -> "Weekly"
                                        ResourceResetCycle.MONTHLY -> "Monthly"
                                        ResourceResetCycle.NEVER -> "Never"
                                    })
                                },
                                onClick = {
                                    selectedCycle = cycle
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = capacity,
                    onValueChange = { capacity = it.filter { c -> c.isDigit() } },
                    label = { Text("Capacity (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim(), selectedCycle.label, capacity.toIntOrNull())
                    }
                },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
