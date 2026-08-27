package com.health.app.ui.information

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.health.app.data.model.CareNote
import com.health.app.data.model.Episode
import com.health.app.data.model.Medication
import com.health.app.data.model.Profile
import com.health.app.data.repository.HealthRepository
import com.health.app.logic.EpisodeSummary
import com.health.app.logic.TempUnit
import com.health.app.logic.Temperature
import com.health.app.logic.TimelineDay
import com.health.app.ui.common.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Information tab's state.
 *
 * One view model over the whole tab: the person as the directory has them, the two fields Health owns
 * about them, the display unit, and their illnesses with the care log. They share a view model
 * because they share a subject — everything here is scoped to whoever the profile bar has selected,
 * and splitting them would mean two objects re-deriving the same person.
 */

@OptIn(ExperimentalCoroutinesApi::class)
class InformationViewModel(private val repo: HealthRepository) : ViewModel() {

    val profiles: StateFlow<List<Profile>> =
        repo.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<Profile?> =
        repo.observeSelectedProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val unit: StateFlow<TempUnit> =
        repo.observeTemperatureUnit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TempUnit.CELSIUS)

    val episodes: StateFlow<List<Episode>> = selected
        .flatMapLatest { profile -> if (profile == null) flowOf(emptyList()) else repo.observeEpisodes(profile.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val careNotes: StateFlow<List<CareNote>> = selected
        .flatMapLatest { profile -> if (profile == null) flowOf(emptyList()) else repo.observeCareNotes(profile.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val medications: StateFlow<List<Medication>> = selected
        .flatMapLatest { profile -> if (profile == null) flowOf(emptyList()) else repo.observeMedications(profile.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _openSummary = MutableStateFlow<EpisodeSummary?>(null)

    /** The history of whichever episode is expanded — see [openHistory]. */
    private val _openHistory = MutableStateFlow<List<TimelineDay>>(emptyList())

    /** Which episode [openSummary] describes. Two illnesses can share a title; ids can't. */
    private var summaryEpisodeId: String? = null

    /**
     * The summary of whichever episode is expanded. Read once on demand rather than observed: it
     * folds four tables through `logic/EpisodeSummaries`, which is worth doing when a card is opened
     * and not worth redoing on every unrelated write.
     */
    val openSummary: StateFlow<EpisodeSummary?> = _openSummary.asStateFlow()

    /**
     * Everything that was done during the expanded episode, in order.
     *
     * Read on demand alongside the summary, and for the same reason: it folds four tables, which is
     * worth doing when a card is opened and not worth redoing on every unrelated write. Anything
     * added from the history reloads it explicitly — see [recordDuring].
     */
    val openHistory: StateFlow<List<TimelineDay>> = _openHistory.asStateFlow()

    fun select(profile: Profile) = repo.selectProfile(profile.id)

    /**
     * The display unit lives on this tab because this is where a person's *normal* is set.
     *
     * Choosing °C or °F and recording that somebody runs at 36.4 are the same act of saying how
     * temperatures should read for this household, so they sit together rather than in a settings
     * screen the app otherwise doesn't have. Readings are always stored in Celsius, so this never
     * rewrites anything already recorded.
     */
    fun setUnit(unit: TempUnit) = repo.setTemperatureUnit(unit)

    /**
     * Edit the two things about a person that are **Health's own**.
     *
     * Their name, relationship and birth date belong to the household directory and are edited in
     * People — this app is a peer on that seam, not an authority on it. Their baseline temperature
     * and their medical note are the opposite: neither is ever published (see
     * `HealthRepository.toPacket`), no other app has a column for them, and this is the only screen
     * that can change them.
     */
    fun updateHealthDetails(profile: Profile, baselineTempC: Double?, notes: String?) =
        viewModelScope.launch {
            repo.updateProfile(profile.copy(baselineTempC = baselineTempC, notes = notes))
        }

    fun loadSummary(episodeId: String) = viewModelScope.launch {
        summaryEpisodeId = episodeId
        _openSummary.value = repo.summarizeEpisode(episodeId)
        _openHistory.value = repo.episodeHistory(episodeId)
    }

    fun clearSummary() {
        summaryEpisodeId = null
        _openSummary.value = null
        _openHistory.value = emptyList()
    }

    /**
     * Add something that happened during an illness, after the fact.
     *
     * The point of the whole feature: the 2am dose nobody stopped to log, the doctor's call on day
     * three, the day the cough started. The repository files each one against the illness that was
     * going on **at that instant**, so a record backdated into last month's flu lands there rather
     * than in whatever is open today — which means this does not have to pass an episode id, and
     * cannot put a record in the wrong story by passing the wrong one.
     *
     * The summary and the history are both re-read afterwards, because a backfilled dose changes
     * both — a peak, a fever run, a dose count — and a history that doesn't show what you just added
     * looks broken.
     */
    fun recordDuring(record: BackfillRecord) = viewModelScope.launch {
        val profile = selected.value ?: return@launch
        when (record) {
            is BackfillRecord.Temperature ->
                repo.logTemperature(profile.id, record.celsius, record.site, record.at, record.note)
            is BackfillRecord.Dose ->
                repo.logDose(
                    profileId = profile.id,
                    medicationId = record.medication?.id,
                    medicationName = record.name,
                    amount = record.amount,
                    unit = record.unit,
                    takenAt = record.at,
                    note = record.note
                )
            is BackfillRecord.Symptom ->
                repo.addSymptom(profile.id, record.name, record.severity, record.at, record.note)
            is BackfillRecord.Care ->
                repo.addCareNote(profile.id, record.kind, record.text, record.at)
        }
        summaryEpisodeId?.let { loadSummary(it) }
    }

    fun endEpisode(episode: Episode) = viewModelScope.launch {
        repo.endEpisode(episode.id)
        // Closing an episode changes its summary (it stops accruing, symptoms resolve), so refresh
        // it if that is the one on screen.
        if (summaryEpisodeId == episode.id) loadSummary(episode.id)
    }

    fun reopenEpisode(episode: Episode) = viewModelScope.launch { repo.reopenEpisode(episode.id) }

    /**
     * Move an illness's dates — how one that was never recorded at the time gets entered at all.
     *
     * The repository re-files as it goes: records that now fall inside are adopted, records that
     * now fall outside are released. Which is why the summary and history are re-read afterwards;
     * both can change substantially from moving one date.
     */
    fun setDates(episode: Episode, startedAt: Long, endedAt: Long?) = viewModelScope.launch {
        repo.setEpisodeDates(episode.id, startedAt, endedAt)
        if (summaryEpisodeId == episode.id) loadSummary(episode.id)
    }

    fun deleteEpisode(episode: Episode) = viewModelScope.launch {
        repo.deleteEpisode(episode.id)
        clearSummary()
    }

    fun deleteCareNote(note: CareNote) = viewModelScope.launch { repo.deleteCareNote(note.id) }

    class Factory(private val repo: HealthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = InformationViewModel(repo) as T
    }
}
