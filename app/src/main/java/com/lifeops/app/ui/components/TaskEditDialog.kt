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
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Category
import com.lifeops.app.data.model.Priority
import com.lifeops.app.data.model.Task

@Composable
fun TaskEditDialog(
    task: Task,
    aspects: List<Aspect>,
    allCategories: Map<String, Category>,
    onSave: (
        title: String,
        priority: Priority,
        dueDate: String?,
        hardDeadline: Boolean,
        isRecurring: Boolean,
        estimatedMinutes: Int?,
        aspectId: String?,
        categoryId: String?
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var priority by remember(task.id) { mutableStateOf(task.priority) }
    var dueDate by remember(task.id) { mutableStateOf(task.dueDate) }
    var hardDeadline by remember(task.id) { mutableStateOf(task.hardDeadline) }
    var isRecurring by remember(task.id) { mutableStateOf(task.isRecurring) }
    var estimatedMinutes by remember(task.id) { mutableStateOf(task.estimatedMinutes?.toString() ?: "") }
    var selectedAspectId by remember(task.id) { mutableStateOf(task.aspectId) }
    var selectedCategoryId by remember(task.id) { mutableStateOf(task.categoryId) }

    var aspectExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }

    val categoriesForAspect = remember(selectedAspectId, allCategories) {
        if (selectedAspectId == null) emptyList()
        else allCategories.values.filter { it.aspectId == selectedAspectId && !it.isArchived }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Task") },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isRecurring, onCheckedChange = { isRecurring = it })
                    Text("Repeat weekly", style = MaterialTheme.typography.bodyMedium)
                }
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
                        selectedCategoryId
                    )
                },
                enabled = title.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
