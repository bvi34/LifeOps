package com.health.app.ui.information

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.health.app.data.model.Episode
import com.health.app.data.model.Profile
import com.health.app.logic.Timeline
import com.health.app.ui.common.*

/**
 * The Information tab: **who this person is, what is normal for them, and what has gone wrong.**
 *
 * It used to be the Illness tab, and the rename is a change of emphasis rather than a change of
 * contents. "Is anyone ill right now" is the rarer question; the one that gets asked far more often —
 * and that every reading in the app is implicitly measured against — is **what does normal look like
 * for this person**. A 37.6 means one thing for somebody who runs at 36.4 and another for somebody
 * who runs at 37.1, and until now the number that settles it was buried in a profile editor on a tab
 * about the household.
 *
 * So the tab now reads top-down as the answer to "tell me about her": who she is and how old,
 * what her own normal is, how temperatures are shown, and then the illnesses she has had — past and
 * present — with the care log underneath. Opening an illness still shows exactly what it always
 * showed: how long, how high, which way it's going, what was given, what was done.
 *
 * The split of what can be edited here is the People seam's, not a UI decision. Name, relationship
 * and birth date belong to the household directory and are changed in **People**; the baseline
 * temperature and the medical note are Health's own, are never published, and can only be changed
 * here.
 */
@Composable
fun InformationScreen(vm: InformationViewModel, onOpenPeople: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val careNotes by vm.careNotes.collectAsStateWithLifecycle()
    val summary by vm.openSummary.collectAsStateWithLifecycle()
    val history by vm.openHistory.collectAsStateWithLifecycle()
    val medications by vm.medications.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    val weightUnit by vm.weightUnit.collectAsStateWithLifecycle()

    var expandedId by remember { mutableStateOf<String?>(null) }
    var showHistoryFor by remember { mutableStateOf<String?>(null) }
    var backfilling by remember { mutableStateOf(false) }
    var editingDates by remember { mutableStateOf<Episode?>(null) }
    var editingDetails by remember { mutableStateOf<Profile?>(null) }

    if (profiles.isEmpty()) {
        NoProfiles(onOpenPeople)
        return
    }

    Column(Modifier.fillMaxSize()) {
        ProfileBar(profiles, selected?.id, vm::select, onOpenPeople)
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            selected?.let { person ->
                item(key = "about") {
                    AboutPersonCard(person = person, onEdit = { editingDetails = person })
                }
                item(key = "normal") {
                    NormalForThemCard(
                        person = person,
                        unit = unit,
                        onEdit = { editingDetails = person }
                    )
                }
                item(key = "display") {
                    DisplayUnitCard(
                        unit = unit,
                        weightUnit = weightUnit,
                        onSelect = vm::setUnit,
                        onSelectWeight = vm::setWeightUnit
                    )
                }
                item(key = "illness-header") {
                    Text("Illnesses", style = MaterialTheme.typography.titleSmall)
                }
            }

            if (episodes.isEmpty()) {
                item(key = "empty") {
                    SectionCard(title = "No illnesses recorded") {
                        Text(
                            "Start one from the Today tab when someone comes down with something. " +
                                "Everything recorded while it's open is kept together, so afterwards " +
                                "you can answer \"how long was the fever\" without reconstructing it.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "An illness that's already been and gone can go in too: start it, set " +
                                "its dates to when it actually ran, and fill the history in from " +
                                "memory. Late is better than never — and Health marks which entries " +
                                "were added afterwards, so the record stays honest about itself.",
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
                        historyCount = if (expandedId == episode.id) Timeline.entryCount(history) else 0,
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
                        onHistory = {
                            // Opening the history from a collapsed card has to load it first —
                            // the sheet reads whatever the expanded episode last loaded.
                            if (expandedId != episode.id) {
                                expandedId = episode.id
                                vm.loadSummary(episode.id)
                            }
                            showHistoryFor = episode.id
                        },
                        onEditDates = { editingDates = episode },
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
                            "Today tab, or added to an illness's history afterwards. Each one is " +
                            "kept with the illness that was going on when it happened.",
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

    showHistoryFor?.let { episodeId ->
        val episode = episodes.firstOrNull { it.id == episodeId }
        if (episode == null) {
            showHistoryFor = null
        } else {
            HistorySheet(
                episode = episode,
                days = history,
                onAdd = { backfilling = true },
                onDismiss = { showHistoryFor = null }
            )
        }
    }

    editingDetails?.let { person ->
        HealthDetailsDialog(
            profile = person,
            unit = unit,
            onDismiss = { editingDetails = null },
            onConfirm = { baseline, notes ->
                vm.updateHealthDetails(person, baseline, notes)
                editingDetails = null
            }
        )
    }
    editingDates?.let { episode ->
        EpisodeDatesDialog(
            episode = episode,
            onDismiss = { editingDates = null },
            onConfirm = { startedAt, endedAt ->
                vm.setDates(episode, startedAt, endedAt)
                editingDates = null
            }
        )
    }

    if (backfilling) {
        BackfillDialogs(
            profile = selected,
            medications = medications.filter { it.active },
            unit = unit,
            onRecord = vm::recordDuring,
            onDismiss = { backfilling = false }
        )
    }
}
