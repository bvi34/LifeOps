package com.lifeops.app.ui.screens.planning

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.repository.CalendarSyncResult
import com.lifeops.app.data.repository.GoogleCalendarInfo
import com.lifeops.app.data.repository.GoogleCalendarSyncRepository
import com.lifeops.app.data.repository.PreferencesRepository
import com.lifeops.app.worker.GoogleCalendarSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalendarSyncUiState(
    val hasPermission: Boolean = false,
    val calendars: List<GoogleCalendarInfo> = emptyList(),
    val selectedCalendarId: Long? = null,
    val selectedCalendarName: String? = null,
    val syncEnabled: Boolean = false,
    val lastSyncedAt: String? = null,
    val isSyncing: Boolean = false,
    val lastResult: CalendarSyncResult? = null
)

class CalendarSyncViewModel(
    private val appContext: Context,
    private val syncRepository: GoogleCalendarSyncRepository,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CalendarSyncUiState(
            selectedCalendarId = preferencesRepository.googleCalendarId,
            selectedCalendarName = preferencesRepository.googleCalendarDisplayName,
            syncEnabled = preferencesRepository.googleCalendarSyncEnabled,
            lastSyncedAt = preferencesRepository.googleCalendarLastSyncedAt
        )
    )
    val uiState: StateFlow<CalendarSyncUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** Re-check the permission and (if granted) reload the device's calendar list. */
    fun refresh() {
        viewModelScope.launch {
            val granted = syncRepository.hasPermission()
            val calendars = if (granted) syncRepository.listCalendars() else emptyList()
            _uiState.update { it.copy(hasPermission = granted, calendars = calendars) }
        }
    }

    fun selectCalendar(info: GoogleCalendarInfo) {
        preferencesRepository.googleCalendarId = info.id
        preferencesRepository.googleCalendarDisplayName = info.displayName
        _uiState.update { it.copy(selectedCalendarId = info.id, selectedCalendarName = info.displayName) }
    }

    fun setSyncEnabled(enabled: Boolean) {
        preferencesRepository.googleCalendarSyncEnabled = enabled
        _uiState.update { it.copy(syncEnabled = enabled) }
        if (enabled && preferencesRepository.googleCalendarId != null) {
            GoogleCalendarSyncWorker.schedulePeriodic(appContext)
        } else {
            GoogleCalendarSyncWorker.cancel(appContext)
        }
    }

    fun syncNow() {
        val calendarId = _uiState.value.selectedCalendarId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, lastResult = null) }
            val result = syncRepository.sync(calendarId)
            if (result.error == null) preferencesRepository.googleCalendarLastSyncedAt = com.lifeops.app.util.DateUtil.now()
            _uiState.update {
                it.copy(isSyncing = false, lastResult = result, lastSyncedAt = preferencesRepository.googleCalendarLastSyncedAt)
            }
        }
    }
}

class CalendarSyncViewModelFactory(
    private val appContext: Context,
    private val syncRepository: GoogleCalendarSyncRepository,
    private val preferencesRepository: PreferencesRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CalendarSyncViewModel(appContext, syncRepository, preferencesRepository) as T
}
