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
                PublishedTaskAspectSection(
                    blurb = "Maintenance puts each upkeep job on your week as a task, dated the " +
                        "day it falls due — an oil change, a filter, a registration. This is the " +
                        "aspect they arrive under.",
                    aspects = state.aspects,
                    selectedAspectId = state.maintenanceAspectId,
                    onSelectAspect = viewModel::setMaintenanceAspect
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Finance bills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                PublishedTaskAspectSection(
                    blurb = "Finance puts each bill on your week as a task, dated the day it falls " +
                        "due — the card, the mortgage, the insurance. This is the aspect they " +
                        "arrive under. Finance ticks them off by itself when the payment lands.",
                    aspects = state.aspects,
                    selectedAspectId = state.financeAspectId,
                    onSelectAspect = viewModel::setFinanceAspect
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

internal val presetSwatches = mapOf(
    ThemePreset.DEFAULT to Triple(Color(0xFF6200EE), Color(0xFF03DAC6), Color(0xFF3700B3)),
    ThemePreset.BEACON to Triple(Color(0xFF3B1F5E), Color(0xFFC8B3E0), Color(0xFFFFB74D)),
    ThemePreset.OCEAN to Triple(Color(0xFF0277BD), Color(0xFF4FC3F7), Color(0xFF80DEEA)),
    ThemePreset.SUNSET to Triple(Color(0xFFBF360C), Color(0xFFFF7043), Color(0xFFFFCC02)),
)

