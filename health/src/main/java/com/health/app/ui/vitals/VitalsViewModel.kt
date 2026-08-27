package com.health.app.ui.vitals

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.health.app.data.model.Profile
import com.health.app.data.model.Reading
import com.health.app.data.model.ReadingType
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
 * The Vitals tab's state: one person's measurements, and the unit they are shown in.
 *
 * Readings are always stored in Celsius and converted for display, so the unit is a display concern
 * that never reaches a stored value — see `logic/Temperature`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VitalsViewModel(private val repo: HealthRepository) : ViewModel() {

    val profiles: StateFlow<List<Profile>> =
        repo.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<Profile?> =
        repo.observeSelectedProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val unit: StateFlow<TempUnit> =
        repo.observeTemperatureUnit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TempUnit.CELSIUS)

    val readings: StateFlow<List<Reading>> = selected
        .flatMapLatest { profile -> if (profile == null) flowOf(emptyList()) else repo.observeReadings(profile.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(profile: Profile) = repo.selectProfile(profile.id)

    fun logTemperature(celsius: Double, site: TempSite, note: String?, at: Long) = viewModelScope.launch {
        selected.value?.let { repo.logTemperature(it.id, celsius, site, takenAt = at, note = note) }
    }

    fun logOther(type: ReadingType, value: Double, secondary: Double?, note: String?) = viewModelScope.launch {
        selected.value?.let { repo.logReading(it.id, type, value, secondary, note = note) }
    }

    fun delete(reading: Reading) = viewModelScope.launch { repo.deleteReading(reading.id) }

    class Factory(private val repo: HealthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = VitalsViewModel(repo) as T
    }
}
