@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.planning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Category
import com.lifeops.app.data.model.Operation
import com.lifeops.app.data.model.OperationStatus
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.screens.settings.SettingsViewModel

@Composable
fun OperationsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
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
                        "Operations",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = viewModel::showNewOperationDialog) {
                        Icon(Icons.Default.Add, contentDescription = "Add operation")
                    }
                }
                Text(
                    "Group related tasks into operations within an aspect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(4.dp))
            }
            items(state.operations, key = { it.id }) { operation ->
                OperationItem(
                    operation = operation,
                    aspectName = state.aspects.firstOrNull { it.id == operation.aspectId }?.name,
                    onToggleStatus = {
                        val newStatus = if (operation.status == OperationStatus.ACTIVE)
                            OperationStatus.COMPLETED else OperationStatus.ACTIVE
                        viewModel.setOperationStatus(operation.id, newStatus)
                    },
                    onEdit = { viewModel.showEditOperationDialog(operation) }
                )
            }
        }
    }

    if (state.showNewOperationDialog) {
        NewOperationDialog(
            aspects = state.aspects.filter { !it.isArchived },
            onConfirm = { title, aspectId -> viewModel.addOperation(title, aspectId) },
            onDismiss = viewModel::hideNewOperationDialog
        )
    }

    state.editingOperation?.let { operation ->
        EditOperationDialog(
            operation = operation,
            aspects = state.aspects,
            categories = state.categories,
            onConfirm = { title, aspectId, categoryId, description ->
                viewModel.saveOperationEdit(operation, title, aspectId, categoryId, description)
            },
            onDismiss = viewModel::hideEditOperationDialog
        )
    }
}

@Composable
private fun OperationItem(
    operation: Operation,
    aspectName: String?,
    onToggleStatus: () -> Unit,
    onEdit: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    operation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (operation.status == OperationStatus.ACTIVE) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                val statusLabel = if (operation.status == OperationStatus.ACTIVE) "Active" else "Completed"
                val subLabel = listOfNotNull(aspectName, statusLabel).joinToString(" · ")
                if (subLabel.isNotEmpty()) {
                    Text(
                        subLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit operation", modifier = Modifier.size(18.dp))
            }
            TextButton(onClick = onToggleStatus) {
                Text(if (operation.status == OperationStatus.ACTIVE) "Complete" else "Reopen")
            }
        }
    }
}

@Composable
private fun NewOperationDialog(
    aspects: List<Aspect>,
    onConfirm: (title: String, aspectId: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedAspectId by remember { mutableStateOf<String?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val selectedAspect = aspects.firstOrNull { it.id == selectedAspectId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Operation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (aspects.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedAspect?.name ?: "No aspect",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Aspect (optional)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("No aspect") },
                                onClick = { selectedAspectId = null; dropdownExpanded = false }
                            )
                            aspects.forEach { aspect ->
                                DropdownMenuItem(
                                    text = { Text(aspect.name) },
                                    onClick = { selectedAspectId = aspect.id; dropdownExpanded = false }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedAspectId) },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditOperationDialog(
    operation: Operation,
    aspects: List<Aspect>,
    categories: Map<String, List<Category>>,
    onConfirm: (title: String, aspectId: String?, categoryId: String?, description: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(operation.id) { mutableStateOf(operation.title) }
    var description by remember(operation.id) { mutableStateOf(operation.description ?: "") }
    var selectedAspectId by remember(operation.id) { mutableStateOf(operation.aspectId) }
    var selectedCategoryId by remember(operation.id) { mutableStateOf(operation.categoryId) }
    var aspectExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }

    val selectedAspect = aspects.firstOrNull { it.id == selectedAspectId }
    val categoriesForAspect = categories[selectedAspectId] ?: emptyList()
    val selectedCategory = categoriesForAspect.firstOrNull { it.id == selectedCategoryId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Operation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(
                    expanded = aspectExpanded,
                    onExpandedChange = { aspectExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedAspect?.name ?: "No aspect",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Aspect (optional)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = aspectExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = aspectExpanded,
                        onDismissRequest = { aspectExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("No aspect") },
                            onClick = { selectedAspectId = null; selectedCategoryId = null; aspectExpanded = false }
                        )
                        aspects.forEach { aspect ->
                            DropdownMenuItem(
                                text = { Text(aspect.name) },
                                onClick = { selectedAspectId = aspect.id; selectedCategoryId = null; aspectExpanded = false }
                            )
                        }
                    }
                }
                if (selectedAspectId != null && categoriesForAspect.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = categoryExpanded,
                        onExpandedChange = { categoryExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedCategory?.name ?: "No category",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category (optional)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = categoryExpanded,
                            onDismissRequest = { categoryExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("No category") },
                                onClick = { selectedCategoryId = null; categoryExpanded = false }
                            )
                            categoriesForAspect.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name) },
                                    onClick = { selectedCategoryId = cat.id; categoryExpanded = false }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(title.trim(), selectedAspectId, selectedCategoryId, description.trim().ifBlank { null })
                    }
                },
                enabled = title.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
