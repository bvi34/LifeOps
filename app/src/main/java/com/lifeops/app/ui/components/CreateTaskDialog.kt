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
import com.lifeops.app.data.model.Project
import com.lifeops.app.data.model.RunbookWithSteps
import java.util.UUID

@Composable
fun CreateTaskDialog(
    aspects: List<Aspect>,
    allCategories: Map<String, Category>,
    projects: List<Project> = emptyList(),
    runbooks: List<RunbookWithSteps> = emptyList(),
    counters: List<Counter> = emptyList(),
    currentWeekEndDate: String? = null,
    onCreateProject: (id: String, title: String, aspectId: String?) -> Unit = { _, _, _ -> },
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
        projectId: String?,
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
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var priority by remember { mutableStateOf(Priority.MEDIUM) }
    var dueDate by remember { mutableStateOf("") }
    var hardDeadline by remember { mutableStateOf(false) }
    var isRecurring by remember { mutableStateOf(false) }
    var recurrenceIntervalWeeks by remember { mutableStateOf(1) }
    var recurrenceDayOfMonth by remember { mutableStateOf<Int?>(null) }
    var estimatedMinutes by remember { mutableStateOf("") }
    var selectedRunbookId by remember { mutableStateOf<String?>(null) }
    var selectedCounterId by remember { mutableStateOf<String?>(null) }
    var showNewProjectDialog by remember { mutableStateOf(false) }

    var aspectExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var projectExpanded by remember { mutableStateOf(false) }
    var runbookExpanded by remember { mutableStateOf(false) }
    var counterExpanded by remember { mutableStateOf(false) }

    val categoriesForAspect = remember(selectedAspectId, allCategories) {
        if (selectedAspectId == null) emptyList()
        else allCategories.values.filter { it.aspectId == selectedAspectId && !it.isArchived }
    }

    val suggestedProjects = remember(selectedAspectId, projects) {
        if (selectedAspectId == null) projects
        else projects.filter { it.aspectId == selectedAspectId }
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

                // Project dropdown
                val selectedProject = projects.firstOrNull { it.id == selectedProjectId }
                ExposedDropdownMenuBox(expanded = projectExpanded, onExpandedChange = { projectExpanded = it }) {
                    OutlinedTextField(
                        value = selectedProject?.title ?: "No project",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Project (optional)") },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(projectExpanded) }
                    )
                    ExposedDropdownMenu(expanded = projectExpanded, onDismissRequest = { projectExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("No project") },
                            onClick = { selectedProjectId = null; projectExpanded = false }
                        )
                        suggestedProjects.forEach { project ->
                            DropdownMenuItem(
                                text = { Text(project.title) },
                                onClick = { selectedProjectId = project.id; projectExpanded = false }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("New project…") },
                            onClick = { showNewProjectDialog = true; projectExpanded = false }
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

                DatePickerButton(
                    label = "due date",
                    selectedDateStr = dueDate.ifBlank { null },
                    onDateSelected = { dueDate = it ?: "" },
                    modifier = Modifier.fillMaxWidth()
                )
                // ISO dates compare lexicographically, so a plain string compare is safe here.
                if (currentWeekEndDate != null && dueDate.isNotBlank() && dueDate > currentWeekEndDate) {
                    Text(
                        "Due after this week — it will wait in Future Tasks (Planning tab) until its week arrives.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

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
                        selectedProjectId,
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

    if (showNewProjectDialog) {
        NewProjectQuickDialog(
            onConfirm = { projectTitle ->
                val newId = UUID.randomUUID().toString()
                onCreateProject(newId, projectTitle, selectedAspectId)
                selectedProjectId = newId
                showNewProjectDialog = false
            },
            onDismiss = { showNewProjectDialog = false }
        )
    }
}

@Composable
private fun NewProjectQuickDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
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
