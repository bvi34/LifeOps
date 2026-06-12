@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.CostResource
import com.lifeops.app.data.model.GameResource
import com.lifeops.app.data.model.Project
import com.lifeops.app.data.model.ProjectStatus
import com.lifeops.app.data.model.ResourceResetCycle
import com.lifeops.app.data.model.ThemePreset
import com.lifeops.app.ui.theme.parseColor

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.loadBackupFromUri(context, it) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
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
                    Text("Aspects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = viewModel::showNewAspectDialog) {
                        Icon(Icons.Default.Add, contentDescription = "Add aspect")
                    }
                }
            }
            items(state.aspects, key = { it.id }) { aspect ->
                AspectItem(
                    aspect = aspect,
                    categories = state.categories[aspect.id] ?: emptyList(),
                    isExpanded = state.expandedAspectId == aspect.id,
                    onToggle = { viewModel.toggleAspectExpanded(aspect.id) },
                    onArchive = { viewModel.archiveAspect(aspect.id, !aspect.isArchived) },
                    onAddCategory = { viewModel.showNewCategoryDialog(aspect.id) },
                    onArchiveCategory = { catId, archive -> viewModel.archiveCategory(catId, archive) }
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                NotificationPreferenceRow(
                    hour = state.defaultReminderHour,
                    onClick = viewModel::showReminderTimePicker
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                ThemeSection(
                    selectedPreset = state.themePreset,
                    isDarkMode = state.isDarkMode,
                    onPresetSelect = viewModel::setThemePreset,
                    onDarkModeToggle = viewModel::setDarkMode
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                DataActionsSection(
                    onBackup = { viewModel.backup(context) },
                    onRestore = viewModel::showRestoreDialog,
                    onExportCsv = { viewModel.exportCsv(context) }
                )
                state.restoreWarning?.let { warning ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Game Resource Slots", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
            }
            items(state.gameResources, key = { it.id }) { resource ->
                GameResourceItem(resource = resource, onRename = { viewModel.renameGameResource(resource, it) })
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
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
                Spacer(Modifier.height(4.dp))
                Text(
                    "Track usage of external resources (e.g. Claude Code, Apollo Credits) per task. Does not affect scoring.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(8.dp))
            }
            items(state.costResources, key = { it.id }) { resource ->
                CostResourceItem(
                    resource = resource,
                    onToggleActive = { viewModel.setCostResourceActive(resource.id, !resource.isActive) }
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Projects",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = viewModel::showNewProjectDialog) {
                        Icon(Icons.Default.Add, contentDescription = "Add project")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Group related tasks into projects within an aspect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(8.dp))
            }
            items(state.projects, key = { it.id }) { project ->
                ProjectItem(
                    project = project,
                    aspectName = state.aspects.firstOrNull { it.id == project.aspectId }?.name,
                    onToggleStatus = {
                        val newStatus = if (project.status == ProjectStatus.ACTIVE)
                            ProjectStatus.COMPLETED else ProjectStatus.ACTIVE
                        viewModel.setProjectStatus(project.id, newStatus)
                    }
                )
            }
        }
    }

    if (state.showRestoreDialog) {
        RestoreDialog(
            json = state.restoreJson,
            error = state.restoreError,
            onJsonChange = viewModel::onRestoreJsonChange,
            onPickFile = { filePickerLauncher.launch("application/json") },
            onConfirm = viewModel::restore,
            onDismiss = viewModel::hideRestoreDialog
        )
    }

    if (state.showReminderTimePicker) {
        ReminderTimePickerDialog(
            currentHour = state.defaultReminderHour,
            onSelect = { hour -> viewModel.setDefaultReminderHour(hour); viewModel.hideReminderTimePicker() },
            onDismiss = viewModel::hideReminderTimePicker
        )
    }

    if (state.showNewAspectDialog) {
        NewAspectDialog(
            onConfirm = { name, color, icon -> viewModel.addAspect(name, color, icon); viewModel.hideNewAspectDialog() },
            onDismiss = viewModel::hideNewAspectDialog
        )
    }

    state.newAspectForCategoryId?.let { aspectId ->
        if (state.showNewCategoryDialog) {
            NewCategoryDialog(
                onConfirm = { name -> viewModel.addCategory(aspectId, name); viewModel.hideNewCategoryDialog() },
                onDismiss = viewModel::hideNewCategoryDialog
            )
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

    if (state.showNewProjectDialog) {
        NewProjectDialog(
            aspects = state.aspects.filter { !it.isArchived },
            onConfirm = { title, aspectId ->
                viewModel.addProject(title, aspectId)
            },
            onDismiss = viewModel::hideNewProjectDialog
        )
    }

    // Archive confirmation dialog
    val pendingAspectId = state.pendingArchiveAspectId
    val pendingCategoryId = state.pendingArchiveCategoryId
    if (pendingAspectId != null || pendingCategoryId != null) {
        val itemName = when {
            pendingAspectId != null -> state.aspects.firstOrNull { it.id == pendingAspectId }?.name ?: "this aspect"
            else -> {
                val cats = state.categories.values.flatten()
                cats.firstOrNull { it.id == pendingCategoryId }?.name ?: "this category"
            }
        }
        val itemType = if (pendingAspectId != null) "aspect" else "category"
        AlertDialog(
            onDismissRequest = viewModel::dismissArchiveConfirmation,
            title = { Text("Archive $itemType?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Are you sure you want to archive \"$itemName\"?")
                    if (pendingCategoryId != null) {
                        Text(
                            "Tasks in this category will be moved to Uncategorized.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmArchive,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Archive") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissArchiveConfirmation) { Text("Cancel") }
            }
        )
    }
}

private val presetSwatches = mapOf(
    ThemePreset.DEFAULT to Triple(Color(0xFF6200EE), Color(0xFF03DAC6), Color(0xFF3700B3)),
    ThemePreset.BEACON to Triple(Color(0xFF3B1F5E), Color(0xFFC8B3E0), Color(0xFFFFB74D)),
    ThemePreset.OCEAN to Triple(Color(0xFF0277BD), Color(0xFF4FC3F7), Color(0xFF80DEEA)),
    ThemePreset.SUNSET to Triple(Color(0xFFBF360C), Color(0xFFFF7043), Color(0xFFFFCC02)),
)

@Composable
private fun ThemeSection(
    selectedPreset: ThemePreset,
    isDarkMode: Boolean,
    onPresetSelect: (ThemePreset) -> Unit,
    onDarkModeToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (isDarkMode) "Dark mode" else "Light mode",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = isDarkMode, onCheckedChange = onDarkModeToggle)
            }
            HorizontalDivider()
            Text(
                "Color preset",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemePreset.entries.forEach { preset ->
                    PresetCard(
                        preset = preset,
                        isSelected = selectedPreset == preset,
                        onClick = { onPresetSelect(preset) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: ThemePreset,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val swatches = presetSwatches[preset] ?: return
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                 else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(modifier = Modifier.size(14.dp).background(swatches.first, shape = CircleShape))
                Box(modifier = Modifier.size(14.dp).background(swatches.second, shape = CircleShape))
                Box(modifier = Modifier.size(14.dp).background(swatches.third, shape = CircleShape))
            }
            Text(
                preset.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DataActionsSection(
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onExportCsv: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onBackup, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Backup JSON")
                }
                OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Restore")
                }
            }
            OutlinedButton(onClick = onExportCsv, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Export CSV")
            }
        }
    }
}

@Composable
private fun RestoreDialog(
    json: String,
    error: String?,
    onJsonChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from Backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pick backup file (.json)")
                }
                HorizontalDivider()
                Text(
                    "Or paste backup JSON below:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                OutlinedTextField(
                    value = json,
                    onValueChange = onJsonChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    placeholder = { Text("Paste backup JSON here…") }
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = json.isNotBlank()) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AspectItem(
    aspect: Aspect,
    categories: List<com.lifeops.app.data.model.Category>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onArchive: () -> Unit,
    onAddCategory: () -> Unit,
    onArchiveCategory: (String, Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(16.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = parseColor(aspect.color)
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    aspect.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    color = if (aspect.isArchived) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onArchive, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (aspect.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                        contentDescription = "Archive",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (isExpanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                categories.forEach { cat ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "• ${cat.name}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            color = if (cat.isArchived) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = { onArchiveCategory(cat.id, !cat.isArchived) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                if (cat.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                contentDescription = if (cat.isArchived) "Unarchive" else "Archive",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                TextButton(onClick = onAddCategory, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Category")
                }
            }
        }
    }
}

@Composable
private fun GameResourceItem(resource: GameResource, onRename: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var nameValue by remember(resource.name) { mutableStateOf(resource.name) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Slot ${resource.slotIndex + 1}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.width(48.dp))
            if (editing) {
                OutlinedTextField(
                    value = nameValue,
                    onValueChange = { nameValue = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                TextButton(onClick = { onRename(nameValue); editing = false }) { Text("Save") }
            } else {
                Text(resource.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { editing = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun NewAspectDialog(onConfirm: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("#6200EE") }
    var icon by remember { mutableStateOf("star") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Aspect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = color, onValueChange = { color = it }, label = { Text("Color (hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("Icon name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onConfirm(name, color, icon) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun hourLabel(hour: Int): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    val suffix = if (hour < 12) "AM" else "PM"
    return "$h:00 $suffix"
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

@Composable
private fun NotificationPreferenceRow(hour: Int, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.NotificationsActive, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Default reminder time", style = MaterialTheme.typography.bodyMedium)
                Text(hourLabel(hour), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            TextButton(onClick = onClick) { Text("Change") }
        }
    }
}

@Composable
private fun ReminderTimePickerDialog(
    currentHour: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val hours = (5..22).toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default reminder time") },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())) {
                hours.forEach { h ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = h == currentHour, onClick = { onSelect(h) })
                        Text(hourLabel(h), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun NewCategoryDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Category") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onConfirm(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ProjectItem(
    project: Project,
    aspectName: String?,
    onToggleStatus: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (project.status == ProjectStatus.ACTIVE) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                val statusLabel = if (project.status == ProjectStatus.ACTIVE) "Active" else "Completed"
                val subLabel = listOfNotNull(aspectName, statusLabel).joinToString(" · ")
                if (subLabel.isNotEmpty()) {
                    Text(
                        subLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            TextButton(onClick = onToggleStatus) {
                Text(if (project.status == ProjectStatus.ACTIVE) "Complete" else "Reopen")
            }
        }
    }
}

@Composable
private fun NewProjectDialog(
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
        title = { Text("New Project") },
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
