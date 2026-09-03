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
import com.lifeops.app.data.model.RunbookWithSteps
import java.util.UUID
import com.operations.suite.ui.pickers.SuiteDateButton
import com.lifeops.app.util.DueDates

@Composable
fun CreateTaskDialog(
    aspects: List<Aspect>,
    allCategories: Map<String, Category>,
    operations: List<Operation> = emptyList(),
    runbooks: List<RunbookWithSteps> = emptyList(),
    counters: List<Counter> = emptyList(),
    currentWeekStartDate: String? = null,
    currentWeekEndDate: String? = null,
    currentWeekClosed: Boolean = false,
    onCreateOperation: (id: String, title: String, aspectId: String?) -> Unit = { _, _, _ -> },
    onConfirm: (
        title: String,
        note: String?,
        aspectId: String?,
        categoryId: String?,
        priority: Priority,
        dueDate: String?,
        hardDeadline: Boolean,
        isRecurring: Boolean,
        estimatedMinutes: Int?,
        operationId: String?,
        runbookId: String?,
        counterId: String?,
        recurrenceIntervalWeeks: Int,
        recurrenceDayOfMonth: Int?
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var selectedAspectId by remember { mutableStateOf<String?>(null) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var selectedOperationId by remember { mutableStateOf<String?>(null) }
    var priority by remember { mutableStateOf(Priority.MEDIUM) }
    var dueDate by remember { mutableStateOf("") }
    var hardDeadline by remember { mutableStateOf(false) }
    var isRecurring by remember { mutableStateOf(false) }
    var recurrenceIntervalWeeks by remember { mutableStateOf(1) }
    var recurrenceDayOfMonth by remember { mutableStateOf<Int?>(null) }
    var estimatedMinutes by remember { mutableStateOf("") }
    var selectedRunbookId by remember { mutableStateOf<String?>(null) }
    var selectedCounterId by remember { mutableStateOf<String?>(null) }
    var showNewOperationDialog by remember { mutableStateOf(false) }

    var aspectExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var operationExpanded by remember { mutableStateOf(false) }
    var runbookExpanded by remember { mutableStateOf(false) }
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
        title = { Text("New Task") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
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
                            onClick = {
                                selectedAspectId = null
                                selectedCategoryId = null
                                aspectExpanded = false
                            }
                        )
                        aspects.filter { !it.isArchived }.forEach { aspect ->
                            DropdownMenuItem(
                                text = { Text(aspect.name) },
                                onClick = {
                                    selectedAspectId = aspect.id
                                    selectedCategoryId = null
                                    aspectExpanded = false
                                }
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

                // Runbook (checklist) dropdown — stamps the runbook's steps as subtasks
                if (runbooks.isNotEmpty()) {
                    val selectedRunbook = runbooks.firstOrNull { it.runbook.id == selectedRunbookId }
                    ExposedDropdownMenuBox(expanded = runbookExpanded, onExpandedChange = { runbookExpanded = it }) {
                        OutlinedTextField(
                            value = selectedRunbook?.runbook?.name ?: "No runbook",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Runbook (optional)") },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(runbookExpanded) }
                        )
                        ExposedDropdownMenu(expanded = runbookExpanded, onDismissRequest = { runbookExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("No runbook") },
                                onClick = { selectedRunbookId = null; runbookExpanded = false }
                            )
                            runbooks.forEach { rb ->
                                val n = rb.steps.size
                                DropdownMenuItem(
                                    text = { Text("${rb.runbook.name} · $n step${if (n != 1) "s" else ""}") },
                                    onClick = { selectedRunbookId = rb.runbook.id; runbookExpanded = false }
                                )
                            }
                        }
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Priority.entries.forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p.label.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }

                SuiteDateButton(
                    label = "due date",
                    isoDate = dueDate.ifBlank { null },
                    onIsoDateChange = { dueDate = it ?: "" },
                    modifier = Modifier.fillMaxWidth(),
                    // What LifeOps makes of the day — said in the calendar as it is tapped, and kept
                    // under the button afterwards. See `util/DueDates`.
                    check = { day ->
                        DueDates.check(day, currentWeekStartDate, currentWeekEndDate, currentWeekClosed)
                    }
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

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        title.trim(),
                        note.trim().ifBlank { null },
                        selectedAspectId,
                        selectedCategoryId,
                        priority,
                        dueDate.trim().ifBlank { null },
                        hardDeadline,
                        isRecurring,
                        estimatedMinutes.toIntOrNull(),
                        selectedOperationId,
                        selectedRunbookId,
                        selectedCounterId,
                        recurrenceIntervalWeeks,
                        recurrenceDayOfMonth
                    )
                },
                enabled = title.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showNewOperationDialog) {
        NewOperationQuickDialog(
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
private fun NewOperationQuickDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
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
