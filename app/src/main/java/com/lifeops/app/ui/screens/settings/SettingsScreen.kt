@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.lifeops.app.data.model.CustomPalette
import com.lifeops.app.data.model.GameResource
import com.lifeops.app.data.model.Project
import com.lifeops.app.data.model.ProjectStatus
import com.lifeops.app.data.model.ResourceResetCycle
import com.lifeops.app.data.model.RunbookWithSteps
import com.lifeops.app.data.model.TemplateTask
import com.lifeops.app.data.model.TemplateWithTasks
import com.lifeops.app.data.model.ThemePreset
import java.util.UUID
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.ColorPickerField
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

    val createJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.writeBackupToUri(context, uri)
        else viewModel.cancelPendingExport()
    }

    val createCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) viewModel.writeCsvToUri(context, uri)
        else viewModel.cancelPendingExport()
    }

    val createRingsCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) viewModel.writeRingsCsvToUri(context, uri)
        else viewModel.cancelPendingExport()
    }

    val createRingsSvgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/svg+xml")
    ) { uri ->
        if (uri != null) viewModel.writeRingsSvgToUri(context, uri)
        else viewModel.cancelPendingExport()
    }

    LaunchedEffect(state.pendingExportJson) {
        if (state.pendingExportJson != null) createJsonLauncher.launch("lifeops_backup.json")
    }
    LaunchedEffect(state.pendingExportCsv) {
        if (state.pendingExportCsv != null) createCsvLauncher.launch("lifeops_tasks.csv")
    }
    LaunchedEffect(state.pendingExportRingsCsv) {
        if (state.pendingExportRingsCsv != null) createRingsCsvLauncher.launch("growth_rings.csv")
    }
    LaunchedEffect(state.pendingExportRingsSvg) {
        if (state.pendingExportRingsSvg != null) createRingsSvgLauncher.launch("growth_record.svg")
    }

    Scaffold(
        topBar = { AppHeader() }
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
                    onEdit = { viewModel.showEditAspectDialog(aspect) },
                    onAddCategory = { viewModel.showNewCategoryDialog(aspect.id) },
                    onArchiveCategory = { catId, archive -> viewModel.archiveCategory(catId, archive) },
                    onEditCategory = { category -> viewModel.showEditCategoryDialog(category) }
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
                    customPalette = state.customPalette,
                    onPresetSelect = viewModel::setThemePreset,
                    onDarkModeToggle = viewModel::setDarkMode,
                    onCustomPaletteChange = viewModel::setCustomPalette
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                DataActionsSection(
                    onBackup = { viewModel.prepareBackupExport() },
                    onRestore = viewModel::showRestoreDialog,
                    onExportCsv = { viewModel.prepareExportCsv() },
                    onExportRingsCsv = { viewModel.prepareExportRingsCsv() },
                    onExportRingsSvg = { viewModel.prepareExportRingsSvg() }
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
                    },
                    onEdit = { viewModel.showEditProjectDialog(project) }
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
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
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reusable step lists stamped onto tasks as subtasks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(8.dp))
            }
            items(state.runbooks, key = { it.runbook.id }) { rb ->
                RunbookItem(
                    rb = rb,
                    onEdit = { viewModel.showEditRunbookDialog(rb) },
                    onDelete = { viewModel.deleteRunbook(rb.runbook.id) }
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
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
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reusable task sets applied to a week via the + menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(8.dp))
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
            suggestedColor = state.suggestedAspectColor,
            onConfirm = { name, color, icon -> viewModel.addAspect(name, color, icon); viewModel.hideNewAspectDialog() },
            onDismiss = viewModel::hideNewAspectDialog
        )
    }

    state.editingAspect?.let { aspect ->
        EditAspectDialog(
            aspect = aspect,
            onConfirm = { name, color, icon -> viewModel.saveAspectEdit(aspect, name, color, icon) },
            onDismiss = viewModel::hideEditAspectDialog
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

    state.editingCategory?.let { category ->
        EditCategoryDialog(
            category = category,
            aspects = state.aspects,
            onConfirm = { name, aspectId -> viewModel.saveCategoryEdit(category, name, aspectId) },
            onDismiss = viewModel::hideEditCategoryDialog
        )
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

    state.editingProject?.let { project ->
        EditProjectDialog(
            project = project,
            aspects = state.aspects,
            categories = state.categories,
            onConfirm = { title, aspectId, categoryId, description ->
                viewModel.saveProjectEdit(project, title, aspectId, categoryId, description)
            },
            onDismiss = viewModel::hideEditProjectDialog
        )
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
    customPalette: CustomPalette,
    onPresetSelect: (ThemePreset) -> Unit,
    onDarkModeToggle: (Boolean) -> Unit,
    onCustomPaletteChange: (CustomPalette) -> Unit
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
                    val swatches = if (preset == ThemePreset.CUSTOM)
                        Triple(parseColor(customPalette.primary), parseColor(customPalette.secondary), parseColor(customPalette.tertiary))
                    else
                        presetSwatches[preset]
                    PresetCard(
                        preset = preset,
                        isSelected = selectedPreset == preset,
                        swatches = swatches,
                        onClick = { onPresetSelect(preset) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (selectedPreset == ThemePreset.CUSTOM) {
                HorizontalDivider()
                Text(
                    "Custom colors",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                CustomPaletteEditor(palette = customPalette, onChange = onCustomPaletteChange)
            }
        }
    }
}

@Composable
private fun CustomPaletteEditor(palette: CustomPalette, onChange: (CustomPalette) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorRow("Primary",    palette.primary)    { onChange(palette.copy(primary    = it)) }
        ColorRow("Secondary",  palette.secondary)  { onChange(palette.copy(secondary  = it)) }
        ColorRow("Tertiary",   palette.tertiary)   { onChange(palette.copy(tertiary   = it)) }
        ColorRow("Dark BG",    palette.darkBackground)  { onChange(palette.copy(darkBackground  = it)) }
        ColorRow("Light BG",   palette.lightBackground) { onChange(palette.copy(lightBackground = it)) }
    }
}

@Composable
private fun ColorRow(label: String, hexValue: String, onValidHex: (String) -> Unit) {
    ColorPickerField(label = label, color = hexValue, onColorChange = onValidHex)
}

@Composable
private fun PresetCard(
    preset: ThemePreset,
    isSelected: Boolean,
    swatches: Triple<Color, Color, Color>?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (swatches == null) return
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
    onExportCsv: () -> Unit,
    onExportRingsCsv: () -> Unit,
    onExportRingsSvg: () -> Unit
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
                Text("Export Tasks CSV")
            }
            Text(
                "Growth Record",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onExportRingsCsv, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rings CSV")
                }
                OutlinedButton(onClick = onExportRingsSvg, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rings SVG")
                }
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
    onEdit: () -> Unit,
    onAddCategory: () -> Unit,
    onArchiveCategory: (String, Boolean) -> Unit,
    onEditCategory: (com.lifeops.app.data.model.Category) -> Unit
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
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit aspect",
                        modifier = Modifier.size(18.dp)
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
                            onClick = { onEditCategory(cat) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit category",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
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
    var nameValue by remember(resource.id) { mutableStateOf(resource.name) }

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
private fun NewAspectDialog(suggestedColor: String, onConfirm: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(suggestedColor) }
    var icon by remember { mutableStateOf("star") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Aspect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Color", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                ColorSwatchPicker(selectedColor = color, onSelect = { color = it })
                ColorPickerField(label = "Custom color", color = color, onColorChange = { color = it })
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

@Composable
private fun EditAspectDialog(aspect: Aspect, onConfirm: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(aspect.id) { mutableStateOf(aspect.name) }
    var color by remember(aspect.id) { mutableStateOf(aspect.color) }
    var icon by remember(aspect.id) { mutableStateOf(aspect.icon) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Aspect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Color", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                ColorSwatchPicker(selectedColor = color, onSelect = { color = it })
                ColorPickerField(label = "Custom color", color = color, onColorChange = { color = it })
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("Icon name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), color, icon) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ColorSwatchPicker(selectedColor: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        com.lifeops.app.util.aspectColorPalette.forEach { hex ->
            val isSelected = hex.equals(selectedColor, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(parseColor(hex), shape = CircleShape)
                    .then(
                        if (isSelected)
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, shape = CircleShape)
                        else Modifier
                    )
                    .clickable { onSelect(hex) }
            ) {}
        }
    }
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
private fun EditCategoryDialog(
    category: com.lifeops.app.data.model.Category,
    aspects: List<Aspect>,
    onConfirm: (name: String, aspectId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    var selectedAspectId by remember(category.id) { mutableStateOf(category.aspectId) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val selectedAspect = aspects.firstOrNull { it.id == selectedAspectId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedAspect?.name ?: "Select aspect",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Aspect") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        aspects.forEach { aspect ->
                            DropdownMenuItem(
                                text = { Text(aspect.name) },
                                onClick = { selectedAspectId = aspect.id; dropdownExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedAspectId) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ProjectItem(
    project: Project,
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
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit project", modifier = Modifier.size(18.dp))
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

@Composable
private fun EditProjectDialog(
    project: Project,
    aspects: List<Aspect>,
    categories: Map<String, List<com.lifeops.app.data.model.Category>>,
    onConfirm: (title: String, aspectId: String?, categoryId: String?, description: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(project.id) { mutableStateOf(project.title) }
    var description by remember(project.id) { mutableStateOf(project.description ?: "") }
    var selectedAspectId by remember(project.id) { mutableStateOf(project.aspectId) }
    var selectedCategoryId by remember(project.id) { mutableStateOf(project.categoryId) }
    var aspectExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }

    val selectedAspect = aspects.firstOrNull { it.id == selectedAspectId }
    val categoriesForAspect = categories[selectedAspectId] ?: emptyList()
    val selectedCategory = categoriesForAspect.firstOrNull { it.id == selectedCategoryId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Project") },
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

// --- Runbook composables ---

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

// --- Template composables ---

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
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
    categories: Map<String, List<com.lifeops.app.data.model.Category>>,
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
    categories: Map<String, List<com.lifeops.app.data.model.Category>>,
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
    categories: Map<String, List<com.lifeops.app.data.model.Category>>,
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

