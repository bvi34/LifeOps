package com.health.app.ui.today

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.health.app.data.model.CareKind
import com.health.app.data.model.Medication
import com.health.app.data.model.Profile
import com.health.app.data.model.ProfileSnapshot
import com.health.app.data.repository.HealthRepository
import com.health.app.logic.TempSite
import com.health.app.logic.TempUnit
import com.health.app.ui.common.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Today tab's state: whoever is selected, their snapshot, and the four one-tap records.
 *
 * A snapshot folds four tables through `logic/` into the one answer the tab exists to give — how is
 * she right now — so it is assembled in the repository rather than here, and this class only chooses
 * whose.
 */
/**
 * The Today screen's state. Everything is derived from the selected profile, so switching person
 * switches the whole screen without any screen-level bookkeeping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val repo: HealthRepository) : ViewModel() {

    val profiles: StateFlow<List<Profile>> =
        repo.profiles.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<Profile?> =
        repo.profiles.observeSelectedProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val unit: StateFlow<TempUnit> =
        repo.profiles.observeTemperatureUnit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TempUnit.CELSIUS)

    val snapshot: StateFlow<ProfileSnapshot?> = selected
        .flatMapLatest { profile -> if (profile == null) flowOf(null) else repo.snapshots.observeSnapshot(profile) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val medications: StateFlow<List<Medication>> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repo.medications.observeMedications(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(profile: Profile) = repo.profiles.selectProfile(profile.id)

    fun logTemperature(celsius: Double, site: TempSite, note: String?, at: Long) = viewModelScope.launch {
        selected.value?.let { repo.readings.logTemperature(it.id, celsius, site, takenAt = at, note = note) }
    }

    fun logDose(
        medication: Medication?,
        name: String,
        amount: Double,
        unit: String,
        note: String?,
        at: Long
    ) =
        viewModelScope.launch {
            val profile = selected.value ?: return@launch
            repo.doses.logDose(profile.id, medication?.id, name, amount, unit, takenAt = at, note = note)
        }

    fun addSymptom(name: String, severity: Int, note: String?, startedAt: Long) = viewModelScope.launch {
        selected.value?.let { repo.symptoms.addSymptom(it.id, name, severity, startedAt = startedAt, note = note) }
    }

    fun resolveSymptom(symptomId: String) = viewModelScope.launch { repo.symptoms.setSymptomEnded(symptomId) }

    fun addCareNote(kind: CareKind, text: String, at: Long) = viewModelScope.launch {
        selected.value?.let { repo.careNotes.addCareNote(it.id, kind, text, at = at) }
    }

    fun startEpisode(title: String, startedAt: Long = System.currentTimeMillis()) = viewModelScope.launch {
        selected.value?.let { repo.episodes.startEpisode(it.id, title, startedAt = startedAt) }
    }

    fun endEpisode(episodeId: String) = viewModelScope.launch { repo.episodes.endEpisode(episodeId) }

    class Factory(private val repo: HealthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TodayViewModel(repo) as T
    }
}
