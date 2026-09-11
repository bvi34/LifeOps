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
import com.health.app.logic.WeightUnit
import com.health.app.ui.common.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Vitals tab's state: one person's measurements, and the units they are shown in.
 *
 * Temperatures are always stored in Celsius and weights in kilograms, converted for display, so the
 * units are a display concern that never reaches a stored value — see `logic/Temperature` and
 * `logic/Weight`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VitalsViewModel(private val repo: HealthRepository) : ViewModel() {

    val profiles: StateFlow<List<Profile>> =
        repo.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<Profile?> =
        repo.observeSelectedProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val unit: StateFlow<TempUnit> =
        repo.observeTemperatureUnit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TempUnit.CELSIUS)

    val weightUnit: StateFlow<WeightUnit> =
        repo.observeWeightUnit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUnit.KILOGRAMS)

    /** Deletes made on this tab, each with the way to put it back — see `ui/common/Undo`. */
    private val undoable = UndoOffers()
    val undoOffers: SharedFlow<UndoOffer> = undoable.offers

    val readings: StateFlow<List<Reading>> = selected
        .flatMapLatest { profile -> if (profile == null) flowOf(emptyList()) else repo.observeReadings(profile.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(profile: Profile) = repo.selectProfile(profile.id)

    fun logTemperature(celsius: Double, site: TempSite, note: String?, at: Long) = viewModelScope.launch {
        selected.value?.let { repo.logTemperature(it.id, celsius, site, takenAt = at, note = note) }
    }

    /**
     * [value] is already in the type's canonical unit — a weight arrives in kilograms — and already
     * checked against what its kind of measurement can plausibly be, in the dialog that typed it.
     *
     * [at] is when it was *taken*, not when it was typed, and the difference is load-bearing: the
     * repository files a reading against the illness that was going on at that instant.
     */
    fun logOther(
        type: ReadingType,
        value: Double,
        secondary: Double?,
        note: String?,
        at: Long
    ) = viewModelScope.launch {
        selected.value?.let { repo.logReading(it.id, type, value, secondary, takenAt = at, note = note) }
    }

    /**
     * Delete a reading, and offer it back.
     *
     * A temperature nobody can take again is exactly the kind of record that must survive a mis-tap,
     * and the list this button sits in is scrolled with a thumb.
     */
    fun delete(reading: Reading) = viewModelScope.launch {
        undoable.offer("${reading.type.label} deleted", repo.deleteReading(reading.id))
    }

    /** Correct a reading already recorded — same checks as typing it, same row afterwards. */
    fun update(reading: Reading) = viewModelScope.launch { repo.updateReading(reading) }

    fun undo(offer: UndoOffer) = viewModelScope.launch { offer.restore.undo() }

    class Factory(private val repo: HealthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = VitalsViewModel(repo) as T
    }
}
