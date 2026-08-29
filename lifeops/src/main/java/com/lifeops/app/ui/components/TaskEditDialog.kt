@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Category
import com.lifeops.app.data.model.Counter
import com.lifeops.app.data.model.Priority
import com.lifeops.app.data.model.Operation
import com.lifeops.app.data.model.Task
import java.util.UUID

@Composable
fun TaskEditDialog(
    task: Task,
    aspects: List<Aspect>,
    allCategories: Map<String, Category>,
    operations: List<Operation> = emptyList(),
    counters: List<Counter> = emptyList(),
    onCreateOperation: (id: String, title: String, aspectId: String?) -> Unit = { _, _, _ -> },
    onSave: (
        title: String,
        priority: Priority,
        dueDate: String?,
        hardDeadline: Boolean,
        isRecurring: Boolean,
        estimatedMinutes: Int?,
        aspectId: String?,
        categoryId: String?,
        operationId: String?,
        counterId: String?,
        recurrenceIntervalWeeks: Int,
        recurrenceDayOfMonth: Int?
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var priority by remember(task.id) { mutableStateOf(task.priority) }
    var dueDate by remember(task.id) { mutableStateOf(task.dueDate) }
    var hardDeadline by remember(task.id) { mutableStateOf(task.hardDeadline) }
    var isRecurring by remember(task.id) { mutableStateOf(task.isRecurring) }
    var recurrenceIntervalWeeks by remember(task.id) { mutableStateOf(task.recurrenceIntervalWeeks) }
    var recurrenceDayOfMonth by remember(task.id) { mutableStateOf(task.recurrenceDayOfMonth) }
    var estimatedMinutes by remember(task.id) { mutableStateOf(task.estimatedMinutes?.toString() ?: "") }
    var selectedAspectId by remember(task.id) { mutableStateOf(task.aspectId) }
    var selectedCategoryId by remember(task.id) { mutableStateOf(task.categoryId) }
    var selectedOperationId by remember(task.id) { mutableStateOf(task.operationId) }
    var selectedCounterId by remember(task.id) { mutableStateOf(task.counterId) }
    var showNewOperationDialog by remember { mutableStateOf(false) }

    var aspectExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var operationExpanded by remember { mutableStateOf(false) }
    var counterExpanded by remember { mutableStateOf(false) }

    val categoriesForAspect = remember(selectedAspectId, allCategories) {
        if (selectedAspectId == null) emptyList()
        else allCategories.values.filter { it.aspectId == selectedAspectId && !it.isArchived }
    }

    val suggestedOperations = remember(selectedAspectId, operations) {
        if (selectedAspectId == null) operations
        else operations.filter { it.aspectId == selectedAspectId }
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Edit Task") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Aspect dropdown
                val selectedAspect = aspects.firstOrNull { it.id == selectedAspectId }
                ExposedDropdownMenuBox(expanded = aspectExpanded, onExpandedChange = { aspectExpanded = it }) {
                    OutlinedTextField(
                        value = selectedAspect?.name ?: "No aspect",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Aspect") },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(aspectExpanded) }
                    )
                    ExposedDropdownMenu(expanded = aspectExpanded, onDismissRequest = { aspectExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("No aspect") },
                            onClick = { selectedAspectId = null; selectedCategoryId = null; aspectExpanded = false }
                        )
                        aspects.filter { !it.isArchived }.forEach { aspect ->
                            DropdownMenuItem(
                                text = { Text(aspect.name) },
                                onClick = { selectedAspectId = aspect.id; selectedCategoryId = null; aspectExpanded = false }
                            )
                        }
                    }
                }

                // Category dropdown (only when aspect is selected)
                if (selectedAspectId != null) {
                    val selectedCategory = categoriesForAspect.firstOrNull { it.id == selectedCategoryId }
                    ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
                        OutlinedTextField(
                            value = selectedCategory?.name ?: "No category",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category") },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) }
                        )
                        ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
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

                // Operation dropdown
                val selectedOperation = operations.firstOrNull { it.id == selectedOperationId }
                ExposedDropdownMenuBox(expanded = operationExpanded, onExpandedChange = { operationExpanded = it }) {
                    OutlinedTextField(
                        value = selectedOperation?.title ?: "No operation",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Operation (optional)") },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(operationExpanded) }
                    )
                    ExposedDropdownMenu(expanded = operationExpanded, onDismissRequest = { operationExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("No operation") },
                            onClick = { selectedOperationId = null; operationExpanded = false }
                        )
                        suggestedOperations.forEach { operation ->
                            DropdownMenuItem(
                                text = { Text(operation.title) },
                                onClick = { selectedOperationId = operation.id; operationExpanded = false }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("New operation…") },
                            onClick = { showNewOperationDialog = true; operationExpanded = false }
                        )
                    }
                }

                // Counter dropdown — completing this task logs a tick on the chosen counter
                if (counters.isNotEmpty()) {
                    val selectedCounter = counters.firstOrNull { it.id == selectedCounterId }
                    ExposedDropdownMenuBox(expanded = counterExpanded, onExpandedChange = { counterExpanded = it }) {
                        OutlinedTextField(
                            value = selectedCounter?.name ?: "No counter",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Counter (optional)") },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(counterExpanded) }
                        )
                        ExposedDropdownMenu(expanded = counterExpanded, onDismissRequest = { counterExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("No counter") },
                                onClick = { selectedCounterId = null; counterExpanded = false }
                            )
                            counters.forEach { counter ->
                                DropdownMenuItem(
                                    text = { Text(counter.name) },
                                    onClick = { selectedCounterId = counter.id; counterExpanded = false }
                                )
                            }
                        }
                    }
                }

                Text("Priority", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priority.entries.forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p.label.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }

                DatePickerButton(
                    label = "due date",
                    selectedDateStr = dueDate,
                    onDateSelected = { dueDate = it },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = estimatedMinutes,
                    onValueChange = { estimatedMinutes = it.filter { c -> c.isDigit() } },
                    label = { Text("Estimated minutes (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hardDeadline, onCheckedChange = { hardDeadline = it })
                    Text("Hard deadline", style = MaterialTheme.typography.bodyMedium)
                }
                RecurrenceControls(
                    isRecurring = isRecurring,
                    intervalWeeks = recurrenceIntervalWeeks,
                    dayOfMonth = recurrenceDayOfMonth,
                    onChange = { recurring, weeks, day ->
                        isRecurring = recurring
                        recurrenceIntervalWeeks = weeks
                        recurrenceDayOfMonth = day
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        title.trim(),
                        priority,
                        dueDate,
                        hardDeadline,
                        isRecurring,
                        estimatedMinutes.toIntOrNull(),
                        selectedAspectId,
                        selectedCategoryId,
                        selectedOperationId,
                        selectedCounterId,
                        recurrenceIntervalWeeks,
                        recurrenceDayOfMonth
                    )
                },
                enabled = title.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showNewOperationDialog) {
        NewOperationQuickEditDialog(
            onConfirm = { operationTitle ->
                val newId = UUID.randomUUID().toString()
                onCreateOperation(newId, operationTitle, selectedAspectId)
                selectedOperationId = newId
                showNewOperationDialog = false
            },
            onDismiss = { showNewOperationDialog = false }
        )
    }
}

@Composable
private fun NewOperationQuickEditDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Operation") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
