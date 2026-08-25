package com.health.app.ui.episodes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.health.app.data.model.CareNote
import com.health.app.data.model.Episode
import com.health.app.data.model.Profile
import com.health.app.data.repository.HealthRepository
import com.health.app.logic.EpisodeSummary
import com.health.app.logic.TempTrend
import com.health.app.logic.TempUnit
import com.health.app.logic.Temperature
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

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodesViewModel(private val repo: HealthRepository) : ViewModel() {

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

    private val _openSummary = MutableStateFlow<EpisodeSummary?>(null)

    /** Which episode [openSummary] describes. Two illnesses can share a title; ids can't. */
    private var summaryEpisodeId: String? = null

    /**
     * The summary of whichever episode is expanded. Read once on demand rather than observed: it
     * folds four tables through `logic/EpisodeSummaries`, which is worth doing when a card is opened
     * and not worth redoing on every unrelated write.
     */
    val openSummary: StateFlow<EpisodeSummary?> = _openSummary.asStateFlow()

    fun select(profile: Profile) = repo.selectProfile(profile.id)

    fun loadSummary(episodeId: String) = viewModelScope.launch {
        summaryEpisodeId = episodeId
        _openSummary.value = repo.summarizeEpisode(episodeId)
    }

    fun clearSummary() {
        summaryEpisodeId = null
        _openSummary.value = null
    }

    fun endEpisode(episode: Episode) = viewModelScope.launch {
        repo.endEpisode(episode.id)
        // Closing an episode changes its summary (it stops accruing, symptoms resolve), so refresh
        // it if that is the one on screen.
        if (summaryEpisodeId == episode.id) loadSummary(episode.id)
    }

    fun reopenEpisode(episode: Episode) = viewModelScope.launch { repo.reopenEpisode(episode.id) }

    fun deleteEpisode(episode: Episode) = viewModelScope.launch {
        repo.deleteEpisode(episode.id)
        clearSummary()
    }

    fun deleteCareNote(note: CareNote) = viewModelScope.launch { repo.deleteCareNote(note.id) }

    class Factory(private val repo: HealthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EpisodesViewModel(repo) as T
    }
}

/**
 * Illnesses, past and present, and the care log that goes with them. Opening one reads it back as
 * the story it was: how long, how high, which way it's going, what was given, what was done.
 */
@Composable
fun EpisodesScreen(vm: EpisodesViewModel, onAddProfile: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val careNotes by vm.careNotes.collectAsStateWithLifecycle()
    val summary by vm.openSummary.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()

    var expandedId by remember { mutableStateOf<String?>(null) }

    if (profiles.isEmpty()) {
        NoProfiles(onAddProfile)
        return
    }

    Column(Modifier.fillMaxSize()) {
        ProfileBar(profiles, selected?.id, vm::select, onAddProfile)
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (episodes.isEmpty()) {
                item(key = "empty") {
                    SectionCard(title = "No illnesses recorded") {
                        Text(
                            "Start one from the Today tab when someone comes down with something. " +
                                "Everything recorded while it's open is kept together, so afterwards " +
                                "you can answer \"how long was the fever\" without reconstructing it.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                items(episodes, key = { it.id }) { episode ->
                    EpisodeCard(
                        episode = episode,
                        expanded = expandedId == episode.id,
                        summary = summary.takeIf { expandedId == episode.id },
                        unit = unit,
                        onToggle = {
                            if (expandedId == episode.id) {
                                expandedId = null
                                vm.clearSummary()
                            } else {
                                expandedId = episode.id
                                vm.loadSummary(episode.id)
                            }
                        },
                        onEnd = { vm.endEpisode(episode) },
                        onReopen = { vm.reopenEpisode(episode) },
                        onDelete = { vm.deleteEpisode(episode) }
                    )
                }
            }

            item(key = "care-header") {
                Text("Care log", style = MaterialTheme.typography.titleSmall)
            }
            if (careNotes.isEmpty()) {
                item(key = "no-care") {
                    Text(
                        "Fluids, rest, a call to the doctor and what they said — recorded from the " +
                            "Today tab, and kept with whichever illness was open at the time.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                items(careNotes, key = { it.id }) { note ->
                    RecordRow(
                        headline = note.text,
                        support = "${note.kind.label} · ${formatStamp(note.at)}",
                        onDelete = { vm.deleteCareNote(note) }
                    )
                }
            }

            item(key = "disclaimer") { DisclaimerText() }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    expanded: Boolean,
    summary: EpisodeSummary?,
    unit: TempUnit,
    onToggle: () -> Unit,
    onEnd: () -> Unit,
    onReopen: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(episode.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (episode.isOpen) "Open since ${formatStamp(episode.startedAt)}"
                        else "${formatDay(episode.startedAt)} – ${formatDay(episode.endedAt!!)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (episode.isOpen) {
                    AssistChip(onClick = {}, label = { Text("Open") })
                }
            }

            if (expanded && summary != null) {
                EpisodeSummaryBlock(summary, unit)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onToggle) { Text(if (expanded) "Hide" else "Summary") }
                if (episode.isOpen) {
                    TextButton(onClick = onEnd) { Text("Mark over") }
                } else {
                    TextButton(onClick = onReopen) { Text("Reopen") }
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/** The episode read back: headline, the numbers, and the observations only the span can make. */
@Composable
private fun EpisodeSummaryBlock(summary: EpisodeSummary, unit: TempUnit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(summary.headline, style = MaterialTheme.typography.bodyMedium)
        CareBadge(summary.careLevel)

        val facts = buildList {
            add("${summary.readingCount} reading(s)")
            summary.peak?.let { add("peak ${Temperature.format(it.celsius, unit)}") }
            if (summary.feverRunHours > 0) add("fever running ${summary.feverRunHours}h")
            if (summary.trend != TempTrend.UNKNOWN) add(summary.trend.label.lowercase())
            if (summary.doseCount > 0) add("${summary.doseCount} dose(s)")
        }
        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)

        if (summary.activeSymptoms.isNotEmpty()) {
            Text(
                "Still going: ${summary.activeSymptoms.joinToString { "${it.name} (${it.severity})" }}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (summary.resolvedSymptoms.isNotEmpty()) {
            Text(
                "Passed: ${summary.resolvedSymptoms.joinToString { it.name }}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        summary.advice.forEach {
            Text("• $it", style = MaterialTheme.typography.bodySmall, color = careColor(summary.careLevel))
        }
    }
}
