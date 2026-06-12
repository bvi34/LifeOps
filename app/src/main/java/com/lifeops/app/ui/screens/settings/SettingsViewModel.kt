package com.lifeops.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.*
import com.lifeops.app.data.model.*
import com.lifeops.app.data.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class SettingsUiState(
    val aspects: List<Aspect> = emptyList(),
    val categories: Map<String, List<Category>> = emptyMap(),
    val gameResources: List<GameResource> = emptyList(),
    val expandedAspectId: String? = null,
    val editingAspect: Aspect? = null,
    val editingCategory: Category? = null,
    val showNewAspectDialog: Boolean = false,
    val showNewCategoryDialog: Boolean = false,
    val newAspectForCategoryId: String? = null,
    val defaultReminderHour: Int = 9,
    val showReminderTimePicker: Boolean = false,
    val showRestoreDialog: Boolean = false,
    val restoreJson: String = "",
    val restoreError: String? = null,
    val restoreWarning: String? = null,
    val backupStatus: String? = null,
    val pendingArchiveAspectId: String? = null,
    val pendingArchiveCategoryId: String? = null,
    val costResources: List<CostResource> = emptyList(),
    val showNewCostResourceDialog: Boolean = false,
    val projects: List<Project> = emptyList(),
    val showNewProjectDialog: Boolean = false,
    val themePreset: ThemePreset = ThemePreset.DEFAULT,
    val isDarkMode: Boolean = true
)

class SettingsViewModel(
    private val aspectRepository: AspectRepository,
    private val gameResourceRepository: GameResourceRepository,
    private val preferencesRepository: PreferencesRepository,
    private val backupRepository: BackupRepository? = null,
    private val taskRepository: TaskRepository? = null,
    private val costResourceRepository: CostResourceRepository? = null,
    private val projectRepository: ProjectRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                themePreset = preferencesRepository.themePreset,
                isDarkMode = preferencesRepository.isDarkMode
            )
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
        projectRepository?.let { repo ->
            viewModelScope.launch {
                repo.observeAll().collectLatest { projects ->
                    _uiState.update { it.copy(projects = projects) }
                }
            }
        }
    }

    fun addAspect(name: String, color: String, icon: String) {
        viewModelScope.launch {
            val aspect = Aspect(UUID.randomUUID().toString(), name, color, icon)
            aspectRepository.upsertAspect(aspect)
        }
    }

    fun updateAspect(aspect: Aspect) {
        viewModelScope.launch { aspectRepository.upsertAspect(aspect) }
    }

    fun archiveAspect(id: String, archive: Boolean) {
        if (archive) {
            _uiState.update { it.copy(pendingArchiveAspectId = id) }
        } else {
            viewModelScope.launch { aspectRepository.setAspectArchived(id, false) }
        }
    }

    fun addCategory(aspectId: String, name: String) {
        viewModelScope.launch {
            val category = Category(UUID.randomUUID().toString(), aspectId, name)
            aspectRepository.upsertCategory(category)
        }
    }

    fun archiveCategory(id: String, archive: Boolean) {
        if (archive) {
            _uiState.update { it.copy(pendingArchiveCategoryId = id) }
        } else {
            viewModelScope.launch { aspectRepository.setCategoryArchived(id, false) }
        }
    }

    fun confirmArchive() {
        val state = _uiState.value
        viewModelScope.launch {
            state.pendingArchiveAspectId?.let { id ->
                aspectRepository.setAspectArchived(id, true)
            }
            state.pendingArchiveCategoryId?.let { id ->
                // Null out tasks' categoryId so they appear as Uncategorized
                taskRepository?.clearCategoryFromTasks(id)
                aspectRepository.setCategoryArchived(id, true)
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

    fun showReminderTimePicker() = _uiState.update { it.copy(showReminderTimePicker = true) }
    fun hideReminderTimePicker() = _uiState.update { it.copy(showReminderTimePicker = false) }

    fun showNewAspectDialog() = _uiState.update { it.copy(showNewAspectDialog = true) }
    fun hideNewAspectDialog() = _uiState.update { it.copy(showNewAspectDialog = false) }
    fun showNewCategoryDialog(aspectId: String) = _uiState.update {
        it.copy(showNewCategoryDialog = true, newAspectForCategoryId = aspectId)
    }
    fun hideNewCategoryDialog() = _uiState.update { it.copy(showNewCategoryDialog = false, newAspectForCategoryId = null) }

    fun showNewCostResourceDialog() = _uiState.update { it.copy(showNewCostResourceDialog = true) }
    fun hideNewCostResourceDialog() = _uiState.update { it.copy(showNewCostResourceDialog = false) }

    fun addCostResource(name: String, resetCycle: String, capacity: Int?) {
        val repo = costResourceRepository ?: return
        viewModelScope.launch {
            repo.addResource(name, resetCycle, capacity)
            _uiState.update { it.copy(showNewCostResourceDialog = false) }
        }
    }

    fun setCostResourceActive(id: String, active: Boolean) {
        viewModelScope.launch { costResourceRepository?.setActive(id, active) }
    }

    fun backup(context: Context) {
        val repo = backupRepository ?: return
        viewModelScope.launch {
            val json = repo.buildBackupJson()
            val uri = repo.saveBackupFile(context, json)
            if (uri != null) {
                repo.shareBackupFile(context, uri)
            } else {
                // Fallback to text share if file save fails
                repo.shareText(context, json, "LifeOps Backup")
            }
            _uiState.update { it.copy(backupStatus = "Backup saved") }
        }
    }

    fun exportCsv(context: Context) {
        val repo = backupRepository ?: return
        viewModelScope.launch {
            val csv = repo.buildCsvExport()
            repo.shareText(context, csv, "LifeOps Tasks Export")
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
                    val warning = when {
                        version < 2 -> "Older backup (v$version) — cost resource and project data not included."
                        version < 3 -> "Backup from before project tracking — project assignments not included."
                        else -> null
                    }
                    _uiState.update { it.copy(showRestoreDialog = false, restoreJson = "", restoreError = null, restoreWarning = warning) }
                }
                .onFailure { e -> _uiState.update { it.copy(restoreError = e.message) } }
        }
    }

    fun showNewProjectDialog() = _uiState.update { it.copy(showNewProjectDialog = true) }
    fun hideNewProjectDialog() = _uiState.update { it.copy(showNewProjectDialog = false) }

    fun addProject(title: String, aspectId: String?) {
        val repo = projectRepository ?: return
        viewModelScope.launch {
            repo.createProject(java.util.UUID.randomUUID().toString(), title, aspectId)
            _uiState.update { it.copy(showNewProjectDialog = false) }
        }
    }

    fun setProjectStatus(id: String, status: ProjectStatus) {
        viewModelScope.launch { projectRepository?.setStatus(id, status) }
    }

    fun setThemePreset(preset: ThemePreset) {
        preferencesRepository.themePreset = preset
        _uiState.update { it.copy(themePreset = preset) }
    }

    fun setDarkMode(dark: Boolean) {
        preferencesRepository.isDarkMode = dark
        _uiState.update { it.copy(isDarkMode = dark) }
    }
}

class SettingsViewModelFactory(
    private val aspectRepository: AspectRepository,
    private val gameResourceRepository: GameResourceRepository,
    private val preferencesRepository: PreferencesRepository,
    private val backupRepository: BackupRepository? = null,
    private val taskRepository: TaskRepository? = null,
    private val costResourceRepository: CostResourceRepository? = null,
    private val projectRepository: ProjectRepository? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(aspectRepository, gameResourceRepository, preferencesRepository, backupRepository, taskRepository, costResourceRepository, projectRepository) as T
}
