package com.lifeops.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import com.lifeops.app.util.nextAspectColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class SettingsUiState(
    val aspects: List<Aspect> = emptyList(),
    // Reading rewards: which aspect reading time earns into (null = off) and the flat points/hour.
    val readingAspectId: String? = null,
    val readingPointsPerHour: Int = com.lifeops.app.util.ReadingRewards.DEFAULT_POINTS_PER_HOUR,
    val categories: Map<String, List<Category>> = emptyMap(),
    val gameResources: List<GameResource> = emptyList(),
    val expandedAspectId: String? = null,
    val editingAspect: Aspect? = null,
    val editingCategory: Category? = null,
    val editingOperation: Operation? = null,
    val showNewAspectDialog: Boolean = false,
    val suggestedAspectColor: String = "#6200EE",
    val showNewCategoryDialog: Boolean = false,
    val newAspectForCategoryId: String? = null,
    val defaultReminderHour: Int = 9,
    val showReminderTimePicker: Boolean = false,
    val wellnessRemindersEnabled: Boolean = true,
    val sleepTrackingEnabled: Boolean = true,
    val wellnessSlotHours: List<Int> = listOf(10, 15, 21),
    val wellnessPickerSlot: Int? = null,
    val showRestoreDialog: Boolean = false,
    val restoreJson: String = "",
    val restoreError: String? = null,
    val restoreWarning: String? = null,
    val backupStatus: String? = null,
    val pendingArchiveAspectId: String? = null,
    val pendingArchiveCategoryId: String? = null,
    val costResources: List<CostResource> = emptyList(),
    val showNewCostResourceDialog: Boolean = false,
    val operations: List<Operation> = emptyList(),
    val showNewOperationDialog: Boolean = false,
    val themePreset: ThemePreset = ThemePreset.DEFAULT,
    val isDarkMode: Boolean = true,
    val customPalette: CustomPalette = CustomPalette(),
    val pendingExportJson: String? = null,
    val pendingExportCsv: String? = null,
    val pendingExportRingsCsv: String? = null,
    val pendingExportRingsSvg: String? = null,
    val pendingExportWellnessCsv: String? = null,
    val runbooks: List<RunbookWithSteps> = emptyList(),
    val templates: List<TemplateWithTasks> = emptyList(),
    val showNewRunbookDialog: Boolean = false,
    val editingRunbook: RunbookWithSteps? = null,
    val showNewTemplateDialog: Boolean = false,
    val editingTemplate: TemplateWithTasks? = null,
    val foodItemCount: Int = 0,
    val isImportingFoods: Boolean = false,
    val foodImportStatus: String? = null
)

class SettingsViewModel(
    private val aspectRepository: AspectRepository,
    private val gameResourceRepository: GameResourceRepository,
    private val preferencesRepository: PreferencesRepository,
    private val backupRepository: BackupRepository? = null,
    private val taskRepository: TaskRepository? = null,
    private val costResourceRepository: CostResourceRepository? = null,
    private val operationRepository: OperationRepository? = null,
    private val growthRepository: GrowthRepository? = null,
    private val runbookRepository: RunbookRepository? = null,
    private val templateRepository: TemplateRepository? = null,
    private val foodItemRepository: FoodItemRepository? = null,
    private val wellnessRepository: WellnessRepository? = null
) : ViewModel() {

    private val aspectService = com.lifeops.app.connection.service.AspectService(aspectRepository)
    // These repos are optional on this screen, so their services are too.
    private val costService = costResourceRepository?.let { com.lifeops.app.connection.service.CostService(it) }
    private val runbookService = runbookRepository?.let { com.lifeops.app.connection.service.RunbookService(it) }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                themePreset = preferencesRepository.themePreset,
                isDarkMode = preferencesRepository.isDarkMode,
                customPalette = preferencesRepository.customPalette,
                wellnessRemindersEnabled = preferencesRepository.wellnessRemindersEnabled,
                sleepTrackingEnabled = preferencesRepository.sleepTrackingEnabled,
                wellnessSlotHours = preferencesRepository.wellnessSlotHours,
                readingAspectId = preferencesRepository.readingAspectId,
                readingPointsPerHour = preferencesRepository.readingPointsPerHour
            )
        }
        foodItemRepository?.let { repo ->
            viewModelScope.launch {
                _uiState.update { it.copy(foodItemCount = repo.count()) }
            }
        }
        viewModelScope.launch {
            combine(
                aspectRepository.observeAllAspects(),
                aspectRepository.observeCategories(),
                gameResourceRepository.observeResources()
            ) { aspects, categories, resources -> Triple(aspects, categories, resources) }
                .collectLatest { (aspects, categories, resources) ->
                    val catsByAspect = categories.groupBy { it.aspectId }
                    _uiState.update {
                        it.copy(
                            aspects = aspects,
                            categories = catsByAspect,
                            gameResources = resources,
                            defaultReminderHour = preferencesRepository.defaultReminderHour
                        )
                    }
                }
        }
        costResourceRepository?.let { repo ->
            viewModelScope.launch {
                repo.observeAllResources().collectLatest { resources ->
                    _uiState.update { it.copy(costResources = resources) }
                }
            }
        }
        operationRepository?.let { repo ->
            viewModelScope.launch {
                repo.observeAll().collectLatest { operations ->
                    _uiState.update { it.copy(operations = operations) }
                }
            }
        }
        runbookRepository?.let { repo ->
            viewModelScope.launch {
                repo.observeRunbooks().collectLatest {
                    _uiState.update { state -> state.copy(runbooks = repo.getAllRunbooksWithSteps()) }
                }
            }
        }
        templateRepository?.let { repo ->
            viewModelScope.launch {
                repo.observeAll().collectLatest {
                    _uiState.update { state -> state.copy(templates = repo.getAllWithTasks()) }
                }
            }
        }
    }

    fun addAspect(name: String, color: String, icon: String) {
        viewModelScope.launch {
            val aspect = Aspect(UUID.randomUUID().toString(), name, color, icon)
            aspectService.upsertAspect(aspect)
        }
    }

    fun updateAspect(aspect: Aspect) {
        viewModelScope.launch { aspectService.updateAspect(aspect) }
    }

    fun showEditAspectDialog(aspect: Aspect) = _uiState.update { it.copy(editingAspect = aspect) }
    fun hideEditAspectDialog() = _uiState.update { it.copy(editingAspect = null) }

    fun saveAspectEdit(aspect: Aspect, name: String, color: String, icon: String) {
        viewModelScope.launch {
            aspectService.updateAspect(aspect.copy(name = name, color = color, icon = icon))
            _uiState.update { it.copy(editingAspect = null) }
        }
    }

    fun archiveAspect(id: String, archive: Boolean) {
        if (archive) {
            _uiState.update { it.copy(pendingArchiveAspectId = id) }
        } else {
            viewModelScope.launch { aspectService.setAspectArchived(id, false) }
        }
    }

    fun addCategory(aspectId: String, name: String) {
        viewModelScope.launch {
            val category = Category(UUID.randomUUID().toString(), aspectId, name)
            aspectService.upsertCategory(category)
        }
    }

    fun showEditCategoryDialog(category: Category) = _uiState.update { it.copy(editingCategory = category) }
    fun hideEditCategoryDialog() = _uiState.update { it.copy(editingCategory = null) }

    fun saveCategoryEdit(category: Category, name: String, aspectId: String) {
        viewModelScope.launch {
            aspectService.updateCategory(category.copy(name = name, aspectId = aspectId))
            _uiState.update { it.copy(editingCategory = null) }
        }
    }

    fun archiveCategory(id: String, archive: Boolean) {
        if (archive) {
            _uiState.update { it.copy(pendingArchiveCategoryId = id) }
        } else {
            viewModelScope.launch { aspectService.setCategoryArchived(id, false) }
        }
    }

    fun confirmArchive() {
        val state = _uiState.value
        viewModelScope.launch {
            state.pendingArchiveAspectId?.let { id ->
                aspectService.setAspectArchived(id, true)
            }
            state.pendingArchiveCategoryId?.let { id ->
                // Null out tasks' categoryId so they appear as Uncategorized (cross-domain, stays
                // on TaskRepository), then archive the category via the service.
                taskRepository?.clearCategoryFromTasks(id)
                aspectService.setCategoryArchived(id, true)
            }
            _uiState.update { it.copy(pendingArchiveAspectId = null, pendingArchiveCategoryId = null) }
        }
    }

    fun dismissArchiveConfirmation() {
        _uiState.update { it.copy(pendingArchiveAspectId = null, pendingArchiveCategoryId = null) }
    }

    fun renameGameResource(resource: GameResource, newName: String) {
        viewModelScope.launch {
            gameResourceRepository.upsertResource(resource.copy(name = newName))
        }
    }

    fun toggleAspectExpanded(id: String) {
        _uiState.update {
            it.copy(expandedAspectId = if (it.expandedAspectId == id) null else id)
        }
    }

    fun setDefaultReminderHour(hour: Int) {
        preferencesRepository.defaultReminderHour = hour
        _uiState.update { it.copy(defaultReminderHour = hour) }
    }

    /** Choose the aspect reading time earns into (null turns reading rewards off). */
    fun setReadingAspect(aspectId: String?) {
        preferencesRepository.readingAspectId = aspectId
        _uiState.update { it.copy(readingAspectId = aspectId) }
    }

    /** Set the flat reward rate (resource points per engaged hour of reading). */
    fun setReadingPointsPerHour(points: Int) {
        val clamped = points.coerceIn(0, 100)
        preferencesRepository.readingPointsPerHour = clamped
        _uiState.update { it.copy(readingPointsPerHour = clamped) }
    }

    fun showReminderTimePicker() = _uiState.update { it.copy(showReminderTimePicker = true) }
    fun hideReminderTimePicker() = _uiState.update { it.copy(showReminderTimePicker = false) }

    // --- Wellness check-in reminders ---

    fun setWellnessRemindersEnabled(enabled: Boolean) {
        preferencesRepository.wellnessRemindersEnabled = enabled
        wellnessRepository?.rescheduleReminders()
        _uiState.update { it.copy(wellnessRemindersEnabled = enabled) }
    }

    fun setSleepTrackingEnabled(enabled: Boolean) {
        // Falls back to the preference write when the repo isn't wired (e.g. previews/tests).
        wellnessRepository?.setSleepTrackingEnabled(enabled)
            ?: run { preferencesRepository.sleepTrackingEnabled = enabled }
        _uiState.update { it.copy(sleepTrackingEnabled = enabled) }
    }

    fun showWellnessSlotPicker(index: Int) = _uiState.update { it.copy(wellnessPickerSlot = index) }
    fun hideWellnessSlotPicker() = _uiState.update { it.copy(wellnessPickerSlot = null) }

    fun setWellnessSlot(index: Int, hour: Int) {
        val hours = _uiState.value.wellnessSlotHours.toMutableList()
        if (index in hours.indices) hours[index] = hour
        saveWellnessSlots(hours)
    }

    fun addWellnessSlot() {
        val hours = _uiState.value.wellnessSlotHours.toMutableList()
        if (hours.size >= 3) return
        val next = (6..23).firstOrNull { it !in hours } ?: return
        hours.add(next)
        saveWellnessSlots(hours)
    }

    fun removeWellnessSlot(index: Int) {
        val hours = _uiState.value.wellnessSlotHours.toMutableList()
        if (hours.size <= 1 || index !in hours.indices) return
        hours.removeAt(index)
        saveWellnessSlots(hours)
    }

    private fun saveWellnessSlots(hours: List<Int>) {
        preferencesRepository.wellnessSlotHours = hours
        val saved = preferencesRepository.wellnessSlotHours // normalized (clamped/distinct/sorted)
        wellnessRepository?.rescheduleReminders()
        _uiState.update { it.copy(wellnessSlotHours = saved, wellnessPickerSlot = null) }
    }

    fun showNewAspectDialog() = _uiState.update {
        it.copy(
            showNewAspectDialog = true,
            suggestedAspectColor = nextAspectColor(it.aspects.map { aspect -> aspect.color })
        )
    }
    fun hideNewAspectDialog() = _uiState.update { it.copy(showNewAspectDialog = false) }
    fun showNewCategoryDialog(aspectId: String) = _uiState.update {
        it.copy(showNewCategoryDialog = true, newAspectForCategoryId = aspectId)
    }
    fun hideNewCategoryDialog() = _uiState.update { it.copy(showNewCategoryDialog = false, newAspectForCategoryId = null) }

    fun showNewCostResourceDialog() = _uiState.update { it.copy(showNewCostResourceDialog = true) }
    fun hideNewCostResourceDialog() = _uiState.update { it.copy(showNewCostResourceDialog = false) }

    fun addCostResource(name: String, resetCycle: String, capacity: Int?) {
        val svc = costService ?: return
        viewModelScope.launch {
            svc.createResource(name, resetCycle, capacity)
            _uiState.update { it.copy(showNewCostResourceDialog = false) }
        }
    }

    fun setCostResourceActive(id: String, active: Boolean) {
        viewModelScope.launch { costService?.setResourceActive(id, active) }
    }

    fun setCustomPalette(palette: CustomPalette) {
        preferencesRepository.customPalette = palette
        _uiState.update { it.copy(customPalette = palette) }
    }

    fun prepareBackupExport() {
        val repo = backupRepository ?: return
        viewModelScope.launch {
            val json = repo.buildBackupJson(preferencesRepository.customPalette)
            _uiState.update { it.copy(pendingExportJson = json) }
        }
    }

    fun writeBackupToUri(context: Context, uri: Uri) {
        val json = _uiState.value.pendingExportJson ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                _uiState.update { it.copy(pendingExportJson = null, backupStatus = "Backup saved") }
            } catch (_: Exception) {
                _uiState.update { it.copy(pendingExportJson = null, backupStatus = "Export failed") }
            }
        }
    }

    fun prepareExportCsv() {
        val repo = backupRepository ?: return
        viewModelScope.launch {
            val csv = repo.buildCsvExport()
            _uiState.update { it.copy(pendingExportCsv = csv) }
        }
    }

    fun writeCsvToUri(context: Context, uri: Uri) {
        val csv = _uiState.value.pendingExportCsv ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
            } finally {
                _uiState.update { it.copy(pendingExportCsv = null) }
            }
        }
    }

    fun prepareExportWellnessCsv() {
        val repo = backupRepository ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingExportWellnessCsv = repo.buildWellnessCsvExport()) }
        }
    }

    fun writeWellnessCsvToUri(context: Context, uri: Uri) {
        val csv = _uiState.value.pendingExportWellnessCsv ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
            } finally {
                _uiState.update { it.copy(pendingExportWellnessCsv = null) }
            }
        }
    }

    fun prepareExportRingsCsv() {
        val repo = growthRepository ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingExportRingsCsv = repo.buildRingsCsv()) }
        }
    }

    fun writeRingsCsvToUri(context: Context, uri: Uri) {
        val csv = _uiState.value.pendingExportRingsCsv ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
            } finally {
                _uiState.update { it.copy(pendingExportRingsCsv = null) }
            }
        }
    }

    fun prepareExportRingsSvg() {
        val repo = growthRepository ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingExportRingsSvg = repo.buildRingsSvg()) }
        }
    }

    fun writeRingsSvgToUri(context: Context, uri: Uri) {
        val svg = _uiState.value.pendingExportRingsSvg ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(svg.toByteArray()) }
            } finally {
                _uiState.update { it.copy(pendingExportRingsSvg = null) }
            }
        }
    }

    fun cancelPendingExport() {
        _uiState.update {
            it.copy(
                pendingExportJson = null,
                pendingExportCsv = null,
                pendingExportRingsCsv = null,
                pendingExportRingsSvg = null,
                pendingExportWellnessCsv = null
            )
        }
    }

    fun showRestoreDialog() = _uiState.update { it.copy(showRestoreDialog = true, restoreError = null, restoreWarning = null) }
    fun hideRestoreDialog() = _uiState.update { it.copy(showRestoreDialog = false, restoreJson = "", restoreError = null) }
    fun onRestoreJsonChange(json: String) = _uiState.update { it.copy(restoreJson = json) }

    fun loadBackupFromUri(context: Context, uri: Uri) {
        val repo = backupRepository ?: return
        viewModelScope.launch {
            val content = repo.readFromUri(context, uri)
            if (content != null) {
                _uiState.update { it.copy(restoreJson = content, restoreError = null) }
            } else {
                _uiState.update { it.copy(restoreError = "Could not read backup file") }
            }
        }
    }

    fun restore() {
        val repo = backupRepository ?: return
        val json = _uiState.value.restoreJson
        viewModelScope.launch {
            val version = repo.parseVersion(json)
            repo.restore(json)
                .onSuccess {
                    // Restore custom palette if present in backup
                    val restoredPalette = backupRepository?.extractCustomPalette(json)
                    if (restoredPalette != null) {
                        preferencesRepository.customPalette = restoredPalette
                        _uiState.update { it.copy(customPalette = restoredPalette) }
                    }
                    val warning = when {
                        version < 2 -> "Older backup (v$version) — cost resource and operation data not included."
                        version < 3 -> "Backup from before operation tracking — operation assignments not included."
                        version < 6 -> "Backup from before the Collection hub — recipes, books, and future operations not included."
                        else -> null
                    }
                    _uiState.update { it.copy(showRestoreDialog = false, restoreJson = "", restoreError = null, restoreWarning = warning) }
                }
                .onFailure { e -> _uiState.update { it.copy(restoreError = e.message) } }
        }
    }

    fun showNewOperationDialog() = _uiState.update { it.copy(showNewOperationDialog = true) }
    fun hideNewOperationDialog() = _uiState.update { it.copy(showNewOperationDialog = false) }

    fun addOperation(title: String, aspectId: String?) {
        val repo = operationRepository ?: return
        viewModelScope.launch {
            repo.createOperation(java.util.UUID.randomUUID().toString(), title, aspectId)
            _uiState.update { it.copy(showNewOperationDialog = false) }
        }
    }

    fun setOperationStatus(id: String, status: OperationStatus) {
        viewModelScope.launch { operationRepository?.setStatus(id, status) }
    }

    fun showEditOperationDialog(operation: Operation) = _uiState.update { it.copy(editingOperation = operation) }
    fun hideEditOperationDialog() = _uiState.update { it.copy(editingOperation = null) }

    fun saveOperationEdit(operation: Operation, title: String, aspectId: String?, categoryId: String?, description: String?) {
        val repo = operationRepository ?: return
        viewModelScope.launch {
            repo.update(operation.copy(title = title, aspectId = aspectId, categoryId = categoryId, description = description))
            _uiState.update { it.copy(editingOperation = null) }
        }
    }

    fun setThemePreset(preset: ThemePreset) {
        preferencesRepository.themePreset = preset
        _uiState.update { it.copy(themePreset = preset) }
    }

    fun setDarkMode(dark: Boolean) {
        preferencesRepository.isDarkMode = dark
        _uiState.update { it.copy(isDarkMode = dark) }
    }

    // Runbook CRUD
    fun showNewRunbookDialog() = _uiState.update { it.copy(showNewRunbookDialog = true) }
    fun hideNewRunbookDialog() = _uiState.update { it.copy(showNewRunbookDialog = false) }

    fun addRunbook(name: String, steps: List<String>) {
        val svc = runbookService ?: return
        viewModelScope.launch {
            svc.create(name, steps)
            _uiState.update { it.copy(showNewRunbookDialog = false) }
        }
    }

    fun showEditRunbookDialog(rb: RunbookWithSteps) = _uiState.update { it.copy(editingRunbook = rb) }
    fun hideEditRunbookDialog() = _uiState.update { it.copy(editingRunbook = null) }

    fun saveRunbookEdit(rb: RunbookWithSteps, name: String, steps: List<String>) {
        val svc = runbookService ?: return
        viewModelScope.launch {
            svc.update(rb.runbook.copy(name = name), steps)
            _uiState.update { it.copy(editingRunbook = null) }
        }
    }

    fun deleteRunbook(id: String) {
        viewModelScope.launch { runbookService?.delete(id) }
    }

    // Template CRUD
    fun showNewTemplateDialog() = _uiState.update { it.copy(showNewTemplateDialog = true) }
    fun hideNewTemplateDialog() = _uiState.update { it.copy(showNewTemplateDialog = false) }

    fun addTemplate(name: String, tasks: List<TemplateTask>) {
        val repo = templateRepository ?: return
        viewModelScope.launch {
            repo.createTemplate(name, tasks)
            _uiState.update { it.copy(showNewTemplateDialog = false) }
        }
    }

    fun showEditTemplateDialog(t: TemplateWithTasks) = _uiState.update { it.copy(editingTemplate = t) }
    fun hideEditTemplateDialog() = _uiState.update { it.copy(editingTemplate = null) }

    fun saveTemplateEdit(t: TemplateWithTasks, tasks: List<TemplateTask>) {
        val repo = templateRepository ?: return
        viewModelScope.launch {
            repo.updateTemplate(t.template, tasks)
            _uiState.update { it.copy(editingTemplate = null) }
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch { templateRepository?.deleteTemplate(id) }
    }

    // USDA food database import — populates the table that daily-intake food search reads from.
    fun importUsdaFoods(context: Context, foodCsvUri: Uri, foodNutrientCsvUri: Uri, foodPortionCsvUri: Uri?) {
        val repo = foodItemRepository ?: return
        _uiState.update { it.copy(isImportingFoods = true, foodImportStatus = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                resolver.openInputStream(foodCsvUri)!!.bufferedReader().use { foodCsv ->
                    resolver.openInputStream(foodNutrientCsvUri)!!.bufferedReader().use { nutrientCsv ->
                        val portionReader = foodPortionCsvUri?.let { resolver.openInputStream(it)?.bufferedReader() }
                        try {
                            val count = repo.importUsda(foodCsv, nutrientCsv, portionReader)
                            _uiState.update {
                                it.copy(
                                    isImportingFoods = false,
                                    foodItemCount = repo.count(),
                                    foodImportStatus = "Imported $count foods"
                                )
                            }
                        } finally {
                            portionReader?.close()
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isImportingFoods = false, foodImportStatus = "Import failed: ${e.message}") }
            }
        }
    }

}

class SettingsViewModelFactory(
    private val aspectRepository: AspectRepository,
    private val gameResourceRepository: GameResourceRepository,
    private val preferencesRepository: PreferencesRepository,
    private val backupRepository: BackupRepository? = null,
    private val taskRepository: TaskRepository? = null,
    private val costResourceRepository: CostResourceRepository? = null,
    private val operationRepository: OperationRepository? = null,
    private val growthRepository: GrowthRepository? = null,
    private val runbookRepository: RunbookRepository? = null,
    private val templateRepository: TemplateRepository? = null,
    private val foodItemRepository: FoodItemRepository? = null,
    private val wellnessRepository: WellnessRepository? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(aspectRepository, gameResourceRepository, preferencesRepository, backupRepository, taskRepository, costResourceRepository, operationRepository, growthRepository, runbookRepository, templateRepository, foodItemRepository, wellnessRepository) as T
}
