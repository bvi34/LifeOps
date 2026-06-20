@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.planning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Category
import com.lifeops.app.data.model.TemplateTask
import com.lifeops.app.data.model.TemplateWithTasks
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.data.model.RunbookWithSteps
import com.lifeops.app.ui.screens.settings.SettingsViewModel
import java.util.UUID

@Composable
fun TemplatesScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
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
                        "Templates",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = viewModel::showNewTemplateDialog) {
                        Icon(Icons.Default.Add, contentDescription = "Add template")
                    }
                }
                Text(
                    "Reusable task sets applied to a week via the + menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(4.dp))
            }
            items(state.templates, key = { it.template.id }) { t ->
                TemplateItem(
                    templateWithTasks = t,
                    onEdit = { viewModel.showEditTemplateDialog(t) },
                    onDelete = { viewModel.deleteTemplate(t.template.id) }
                )
            }
        }
    }

    if (state.showNewTemplateDialog) {
        NewTemplateDialog(
            runbooks = state.runbooks,
            aspects = state.aspects,
            categories = state.categories,
            onConfirm = { name, tasks -> viewModel.addTemplate(name, tasks) },
            onDismiss = viewModel::hideNewTemplateDialog
        )
    }

    state.editingTemplate?.let { t ->
        EditTemplateDialog(
            templateWithTasks = t,
            runbooks = state.runbooks,
            aspects = state.aspects,
            categories = state.categories,
            onConfirm = { tasks -> viewModel.saveTemplateEdit(t, tasks) },
            onDismiss = viewModel::hideEditTemplateDialog
        )
    }
}

private data class DraftTemplateTask(
    val title: String = "",
    val aspectName: String = "",
    val categoryName: String = "",
    val priority: String = "medium",
    val estimatedMinutes: String = "",
    val runbookId: String? = null
)

private fun DraftTemplateTask.toTemplateTask(order: Int) = TemplateTask(
    id = UUID.randomUUID().toString(),
    templateId = "",
    title = title.trim(),
    aspectName = aspectName.trim().ifBlank { null },
    categoryName = categoryName.trim().ifBlank { null },
    priority = priority,
    estimatedMinutes = estimatedMinutes.toIntOrNull(),
    runbookId = runbookId,
    taskOrder = order
)

private fun TemplateTask.toDraft() = DraftTemplateTask(
    title = title,
    aspectName = aspectName ?: "",
    categoryName = categoryName ?: "",
    priority = priority,
    estimatedMinutes = estimatedMinutes?.toString() ?: "",
    runbookId = runbookId
)

@Composable
private fun TemplateItem(templateWithTasks: TemplateWithTasks, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(templateWithTasks.template.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val n = templateWithTasks.tasks.size
                Text(
                    "$n task${if (n != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                if (templateWithTasks.tasks.isNotEmpty()) {
                    Text(
                        templateWithTasks.tasks.take(3).joinToString(", ") { it.title } +
                            if (templateWithTasks.tasks.size > 3) "…" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
private fun TemplateTaskEditor(
    index: Int,
    task: DraftTemplateTask,
    runbooks: List<RunbookWithSteps>,
    aspects: List<Aspect>,
    categories: Map<String, List<Category>>,
    onUpdate: (DraftTemplateTask) -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var priorityExpanded by remember { mutableStateOf(false) }
    var aspectExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var runbookExpanded by remember { mutableStateOf(false) }
    val priorities = listOf("low", "medium", "high", "critical")
    val selectedRunbook = runbooks.firstOrNull { it.runbook.id == task.runbookId }
    val activeAspects = aspects.filter { !it.isArchived }
    // Templates store aspect/category by NAME; resolve the selected aspect to surface its categories.
    val selectedAspect = activeAspects.firstOrNull { it.name.equals(task.aspectName, ignoreCase = true) }
    val categoriesForAspect = selectedAspect?.let { categories[it.id] } ?: emptyList()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = task.title,
                    onValueChange = { onUpdate(task.copy(title = it)) },
                    label = { Text("Task ${index + 1}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "More options",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                }
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                ExposedDropdownMenuBox(expanded = priorityExpanded, onExpandedChange = { priorityExpanded = it }) {
                    OutlinedTextField(
                        value = task.priority.replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Priority") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = priorityExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = priorityExpanded, onDismissRequest = { priorityExpanded = false }) {
                        priorities.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.replaceFirstChar { it.uppercase() }) },
                                onClick = { onUpdate(task.copy(priority = p)); priorityExpanded = false }
                            )
                        }
                    }
                }
                // Aspect — select from existing aspects (stored by name)
                ExposedDropdownMenuBox(expanded = aspectExpanded, onExpandedChange = { aspectExpanded = it }) {
                    OutlinedTextField(
                        value = task.aspectName.ifBlank { "No aspect" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Aspect (optional)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = aspectExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = aspectExpanded, onDismissRequest = { aspectExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("No aspect") },
                            onClick = { onUpdate(task.copy(aspectName = "", categoryName = "")); aspectExpanded = false }
                        )
                        activeAspects.forEach { aspect ->
                            DropdownMenuItem(
                                text = { Text(aspect.name) },
                                onClick = { onUpdate(task.copy(aspectName = aspect.name, categoryName = "")); aspectExpanded = false }
                            )
                        }
                    }
                }
                // Category — cascades from the selected aspect (stored by name)
                if (selectedAspect != null && categoriesForAspect.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
                        OutlinedTextField(
                            value = task.categoryName.ifBlank { "No category" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category (optional)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("No category") },
                                onClick = { onUpdate(task.copy(categoryName = "")); categoryExpanded = false }
                            )
                            categoriesForAspect.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name) },
                                    onClick = { onUpdate(task.copy(categoryName = cat.name)); categoryExpanded = false }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = task.estimatedMinutes,
                    onValueChange = { onUpdate(task.copy(estimatedMinutes = it.filter { c -> c.isDigit() })) },
                    label = { Text("Est. minutes (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (runbooks.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = runbookExpanded, onExpandedChange = { runbookExpanded = it }) {
                        OutlinedTextField(
                            value = selectedRunbook?.runbook?.name ?: "No runbook",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Runbook (optional)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = runbookExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = runbookExpanded, onDismissRequest = { runbookExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("No runbook") },
                                onClick = { onUpdate(task.copy(runbookId = null)); runbookExpanded = false }
                            )
                            runbooks.forEach { rb ->
                                DropdownMenuItem(
                                    text = { Text(rb.runbook.name) },
                                    onClick = { onUpdate(task.copy(runbookId = rb.runbook.id)); runbookExpanded = false }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewTemplateDialog(
    runbooks: List<RunbookWithSteps>,
    aspects: List<Aspect>,
    categories: Map<String, List<Category>>,
    onConfirm: (String, List<TemplateTask>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf(listOf(DraftTemplateTask())) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Template") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Template name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Tasks", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                tasks.forEachIndexed { i, task ->
                    TemplateTaskEditor(
                        index = i,
                        task = task,
                        runbooks = runbooks,
                        aspects = aspects,
                        categories = categories,
                        onUpdate = { updated -> tasks = tasks.toMutableList().also { it[i] = updated } },
                        onRemove = { tasks = tasks.toMutableList().also { it.removeAt(i) } }
                    )
                }
                TextButton(onClick = { tasks = tasks + DraftTemplateTask() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Task")
                }
            }
        },
        confirmButton = {
            val validTasks = tasks.filter { it.title.isNotBlank() }
            Button(
                onClick = {
                    if (name.isNotBlank() && validTasks.isNotEmpty()) {
                        onConfirm(name.trim(), validTasks.mapIndexed { i, d -> d.toTemplateTask(i) })
                    }
                },
                enabled = name.isNotBlank() && validTasks.isNotEmpty()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditTemplateDialog(
    templateWithTasks: TemplateWithTasks,
    runbooks: List<RunbookWithSteps>,
    aspects: List<Aspect>,
    categories: Map<String, List<Category>>,
    onConfirm: (List<TemplateTask>) -> Unit,
    onDismiss: () -> Unit
) {
    var tasks by remember(templateWithTasks.template.id) {
        mutableStateOf(templateWithTasks.tasks.map { it.toDraft() }.ifEmpty { listOf(DraftTemplateTask()) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Template: ${templateWithTasks.template.name}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())
            ) {
                Text("Tasks", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                tasks.forEachIndexed { i, task ->
                    TemplateTaskEditor(
                        index = i,
                        task = task,
                        runbooks = runbooks,
                        aspects = aspects,
                        categories = categories,
                        onUpdate = { updated -> tasks = tasks.toMutableList().also { it[i] = updated } },
                        onRemove = { tasks = tasks.toMutableList().also { it.removeAt(i) } }
                    )
                }
                TextButton(onClick = { tasks = tasks + DraftTemplateTask() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Task")
                }
            }
        },
        confirmButton = {
            val validTasks = tasks.filter { it.title.isNotBlank() }
            Button(
                onClick = {
                    if (validTasks.isNotEmpty()) {
                        onConfirm(validTasks.mapIndexed { i, d -> d.toTemplateTask(i) })
                    }
                },
                enabled = validTasks.isNotEmpty()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
