@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.lifeops.app.data.model.CustomPalette
import com.lifeops.app.data.model.ThemePreset
import com.lifeops.app.ui.components.AppHeader
import com.operations.suite.ui.pickers.SuiteColorField
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

    val createWellnessCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) viewModel.writeWellnessCsvToUri(context, uri)
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

    var pickedFoodCsv by remember { mutableStateOf<android.net.Uri?>(null) }
    var pickedFoodNutrientCsv by remember { mutableStateOf<android.net.Uri?>(null) }
    var pickedFoodPortionCsv by remember { mutableStateOf<android.net.Uri?>(null) }

    val foodCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pickedFoodCsv = uri
    }
    val foodNutrientCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pickedFoodNutrientCsv = uri
    }
    val foodPortionCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pickedFoodPortionCsv = uri
    }

    LaunchedEffect(state.pendingExportJson) {
        if (state.pendingExportJson != null) createJsonLauncher.launch("lifeops_backup.json")
    }
    LaunchedEffect(state.pendingExportCsv) {
        if (state.pendingExportCsv != null) createCsvLauncher.launch("lifeops_tasks.csv")
    }
    LaunchedEffect(state.pendingExportWellnessCsv) {
        if (state.pendingExportWellnessCsv != null) createWellnessCsvLauncher.launch("lifeops_wellness.csv")
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
                Text("Wellness check-ins", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                WellnessReminderSection(
                    enabled = state.wellnessRemindersEnabled,
                    slotHours = state.wellnessSlotHours,
                    onToggle = viewModel::setWellnessRemindersEnabled,
                    onEditSlot = viewModel::showWellnessSlotPicker,
                    onRemoveSlot = viewModel::removeWellnessSlot,
                    onAddSlot = viewModel::addWellnessSlot
                )
                Spacer(Modifier.height(8.dp))
                SleepTrackingSection(
                    enabled = state.sleepTrackingEnabled,
                    onToggle = viewModel::setSleepTrackingEnabled
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Reading rewards", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                ReadingRewardsSection(
                    aspects = state.aspects,
                    selectedAspectId = state.readingAspectId,
                    pointsPerHour = state.readingPointsPerHour,
                    onSelectAspect = viewModel::setReadingAspect,
                    onSetPoints = viewModel::setReadingPointsPerHour
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Maintenance upkeep", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                MaintenanceAspectSection(
                    aspects = state.aspects,
                    selectedAspectId = state.maintenanceAspectId,
                    onSelectAspect = viewModel::setMaintenanceAspect
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Suite-wide: this is the same setting as the Operations Sandbox's gear, so it " +
                        "paints Citation, Logistics, Advisor, Health and People too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    onExportWellnessCsv = { viewModel.prepareExportWellnessCsv() },
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
                Text("Food Database", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                FoodDatabaseSection(
                    foodItemCount = state.foodItemCount,
                    isImporting = state.isImportingFoods,
                    status = state.foodImportStatus,
                    foodCsvPicked = pickedFoodCsv != null,
                    foodNutrientCsvPicked = pickedFoodNutrientCsv != null,
                    foodPortionCsvPicked = pickedFoodPortionCsv != null,
                    onPickFoodCsv = { foodCsvLauncher.launch("text/*") },
                    onPickFoodNutrientCsv = { foodNutrientCsvLauncher.launch("text/*") },
                    onPickFoodPortionCsv = { foodPortionCsvLauncher.launch("text/*") },
                    onImport = {
                        val foodCsv = pickedFoodCsv
                        val nutrientCsv = pickedFoodNutrientCsv
                        if (foodCsv != null && nutrientCsv != null) {
                            viewModel.importUsdaFoods(context, foodCsv, nutrientCsv, pickedFoodPortionCsv)
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
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

    state.wellnessPickerSlot?.let { slot ->
        val current = state.wellnessSlotHours.getOrNull(slot) ?: 10
        HourPickerDialog(
            title = "Check-in time",
            currentHour = current,
            onSelect = { hour -> viewModel.setWellnessSlot(slot, hour) },
            onDismiss = viewModel::hideWellnessSlotPicker
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

/**
 * Reading rewards: choose which aspect reading time in Citation earns into (or Off), and the flat
 * points-per-hour rate. Both reading categories fold into the one aspect; the economy stays simple.
 */
@Composable
private fun ReadingRewardsSection(
    aspects: List<Aspect>,
    selectedAspectId: String?,
    pointsPerHour: Int,
    onSelectAspect: (String?) -> Unit,
    onSetPoints: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Engaged reading time in Citation earns resources into the aspect you pick. Off by default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text("Earns into", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedAspectId == null,
                    onClick = { onSelectAspect(null) },
                    label = { Text("Off") }
                )
                aspects.filter { !it.isArchived }.forEach { aspect ->
                    FilterChip(
                        selected = selectedAspectId == aspect.id,
                        onClick = { onSelectAspect(aspect.id) },
                        label = { Text(aspect.name) }
                    )
                }
            }
            if (selectedAspectId != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rate", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onSetPoints(pointsPerHour - 1) }, enabled = pointsPerHour > 0) {
                        Icon(Icons.Default.Remove, contentDescription = "Fewer points")
                    }
                    Text(
                        "$pointsPerHour pts/hr",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = { onSetPoints(pointsPerHour + 1) }) {
                        Icon(Icons.Default.Add, contentDescription = "More points")
                    }
                }
            }
        }
    }
}

/**
 * Which aspect the upkeep tasks Maintenance puts on the week are filed under.
 *
 * The same shape as [ReadingRewardsSection], for the same reason: another app in the suite feeds
 * work into LifeOps, and LifeOps decides which part of your life it counts towards — not that app.
 *
 * The one difference is what "none" means. Reading rewards are *off* until an aspect is chosen;
 * upkeep tasks arrive either way, and an unfiled one still scores. So the chip says "Unfiled"
 * rather than "Off", because nothing here is being switched off.
 */
@Composable
private fun MaintenanceAspectSection(
    aspects: List<Aspect>,
    selectedAspectId: String?,
    onSelectAspect: (String?) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Maintenance puts each upkeep job on your week as a task, dated the day it falls " +
                    "due — an oil change, a filter, a registration. This is the aspect they arrive under.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text("Filed under", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedAspectId == null,
                    onClick = { onSelectAspect(null) },
                    label = { Text("Unfiled") }
                )
                aspects.filter { !it.isArchived }.forEach { aspect ->
                    FilterChip(
                        selected = selectedAspectId == aspect.id,
                        onClick = { onSelectAspect(aspect.id) },
                        label = { Text(aspect.name) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Changing this re-files the tasks published from now on. Anything already on a week " +
                    "keeps the aspect it arrived with — including one you moved by hand.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

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
    SuiteColorField(label = label, color = hexValue, onColorChange = onValidHex)
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
    onExportWellnessCsv: () -> Unit,
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
            OutlinedButton(onClick = onExportWellnessCsv, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Export Wellness CSV")
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
private fun FoodDatabaseSection(
    foodItemCount: Int,
    isImporting: Boolean,
    status: String?,
    foodCsvPicked: Boolean,
    foodNutrientCsvPicked: Boolean,
    foodPortionCsvPicked: Boolean,
    onPickFoodCsv: () -> Unit,
    onPickFoodNutrientCsv: () -> Unit,
    onPickFoodPortionCsv: () -> Unit,
    onImport: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$foodItemCount foods loaded",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Import the USDA FoodData Central bulk CSV download (food.csv + food_nutrient.csv, " +
                    "food_portion.csv optional) to populate food search for the daily intake log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            OutlinedButton(onClick = onPickFoodCsv, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (foodCsvPicked) "food.csv selected" else "Pick food.csv")
            }
            OutlinedButton(onClick = onPickFoodNutrientCsv, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (foodNutrientCsvPicked) "food_nutrient.csv selected" else "Pick food_nutrient.csv")
            }
            OutlinedButton(onClick = onPickFoodPortionCsv, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (foodPortionCsvPicked) "food_portion.csv selected" else "Pick food_portion.csv (optional)")
            }
            Button(
                onClick = onImport,
                enabled = foodCsvPicked && foodNutrientCsvPicked && !isImporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isImporting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isImporting) "Importing…" else "Import")
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
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
                SuiteColorField(label = "Custom color", color = color, onColorChange = { color = it })
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
                SuiteColorField(label = "Custom color", color = color, onColorChange = { color = it })
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
private fun SleepTrackingSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sleep tracking", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (enabled)
                            "Records screen & charging activity in the background to reconstruct your sleep. Shows an ongoing notification."
                        else
                            "Off — the morning report falls back to a rough screen-time estimate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun WellnessReminderSection(
    enabled: Boolean,
    slotHours: List<Int>,
    onToggle: (Boolean) -> Unit,
    onEditSlot: (Int) -> Unit,
    onRemoveSlot: (Int) -> Unit,
    onAddSlot: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Daytime & sleep prompts", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (enabled) "Better/worse + initiative check-ins plus the morning sleep report"
                        else "Turned off — no pop-ups or notifications",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled) {
                slotHours.forEachIndexed { index, hour ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(hourLabel(hour), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { onEditSlot(index) }) { Text("Change") }
                        if (slotHours.size > 1) {
                            IconButton(onClick = { onRemoveSlot(index) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove",
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                if (slotHours.size < 3) {
                    TextButton(onClick = onAddSlot) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add a time")
                    }
                }
            }
        }
    }
}

@Composable
private fun HourPickerDialog(
    title: String,
    currentHour: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val hours = (0..23).toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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

