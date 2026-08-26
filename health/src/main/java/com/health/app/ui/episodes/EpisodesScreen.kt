package com.health.app.ui.episodes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.health.app.data.model.CareKind
import com.health.app.data.model.CareNote
import com.health.app.data.model.Episode
import com.health.app.data.model.Medication
import com.health.app.data.model.Profile
import com.health.app.data.repository.HealthRepository
import com.health.app.logic.CareLevel
import com.health.app.logic.EpisodeSummary
import com.health.app.logic.TempSite
import com.health.app.logic.TempTrend
import com.health.app.logic.TempUnit
import com.health.app.logic.Temperature
import com.health.app.logic.Timeline
import com.health.app.logic.TimelineDay
import com.health.app.logic.TimelineEntry
import com.health.app.logic.TimelineKind
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
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EpisodesViewModel(repo) as T
    }
}

/**
 * Something being added to the history after the fact.
 *
 * A sealed type rather than four view-model methods because the four record dialogs are reached
 * through one "add something that happened" chooser, and the chooser needs to hand back *whatever
 * was filled in* without the caller having to remember which of four callbacks matches which dialog.
 */
sealed interface BackfillRecord {
    val at: Long

    data class Temperature(
        val celsius: Double,
        val site: TempSite,
        val note: String?,
        override val at: Long
    ) : BackfillRecord

    data class Dose(
        val medication: Medication?,
        val name: String,
        val amount: Double,
        val unit: String,
        val note: String?,
        override val at: Long
    ) : BackfillRecord

    data class Symptom(
        val name: String,
        val severity: Int,
        val note: String?,
        override val at: Long
    ) : BackfillRecord

    data class Care(val kind: CareKind, val text: String, override val at: Long) : BackfillRecord
}

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
fun EpisodesScreen(vm: EpisodesViewModel, onOpenPeople: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val careNotes by vm.careNotes.collectAsStateWithLifecycle()
    val summary by vm.openSummary.collectAsStateWithLifecycle()
    val history by vm.openHistory.collectAsStateWithLifecycle()
    val medications by vm.medications.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()

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
                    DisplayUnitCard(unit = unit, onSelect = vm::setUnit)
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

/**
 * The illness, hour by hour: what was done and when.
 *
 * The other half of reading an episode back. The summary says *how it went*; this says *what
 * happened* — which is the version a doctor asks for, and the version the person who was up all
 * three nights cannot produce from memory.
 *
 * Days read newest first, and each day reads forwards, because that is how the two are actually
 * used: you want the latest day immediately, and then to read it the way it was lived.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    episode: Episode,
    days: List<TimelineDay>,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    val total = Timeline.entryCount(days)
    val filledIn = Timeline.filledInCount(days)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(episode.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (episode.isOpen) "Open since ${formatStamp(episode.startedAt)}"
                        else "${formatDay(episode.startedAt)} – ${formatDay(episode.endedAt!!)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        buildString {
                            append(if (total == 1) "1 record" else "$total records")
                            // Said plainly, because filling the history in is the encouraged thing
                            // to do — not a defect to apologise for.
                            if (filledIn > 0) append(" · $filledIn added afterwards")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "add") {
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Text("Add something that happened")
                }
            }

            if (days.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing was recorded during this one. Anything you remember can still go " +
                            "in — a dose, a temperature, the day the cough started — and it will be " +
                            "filed under this illness by the time you give it, not by today's date.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            days.forEach { day ->
                item(key = "day:${day.date}") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            day.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            formatDay(day.entries.first().atMillis),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(day.entries, key = { it.id }) { entry -> HistoryRow(entry) }
            }

            item(key = "disclaimer") { DisclaimerText(Modifier.padding(top = 12.dp)) }
        }
    }
}

/** One thing that happened: the time, what it was, and — when it applies — that it was remembered. */
@Composable
private fun HistoryRow(entry: TimelineEntry) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            formatTime(entry.atMillis),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp)
        )
        Column(Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.kind == TimelineKind.EPISODE) FontWeight.Bold else FontWeight.Normal,
                    color = entry.careLevel
                        ?.takeIf { it != CareLevel.ROUTINE }
                        ?.let { careColor(it) }
                        ?: MaterialTheme.colorScheme.onSurface
                )
                entry.careLevel?.let { CareBadge(it) }
            }
            listOfNotNull(entry.kindLabel(), entry.detail).takeIf { it.isNotEmpty() }?.let {
                Text(
                    it.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            entry.filledInLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The kind, where saying it adds something.
 *
 * A dose row already says "Calpol, 5 mL" and a reading already says "38.4 °C (ear)"; prefixing those
 * with their own category is noise. A care note is the one that genuinely needs it — "rang the
 * surgery" reads very differently depending on whether it was filed as an appointment or a note.
 */
private fun TimelineEntry.kindLabel(): String? = when (kind) {
    TimelineKind.DOSE, TimelineKind.EPISODE, TimelineKind.READING -> null
    TimelineKind.SYMPTOM_STARTED -> TimelineKind.SYMPTOM_STARTED.label
    TimelineKind.SYMPTOM_ENDED -> null
    TimelineKind.CARE -> null
}

/**
 * When an illness actually ran.
 *
 * The piece that makes reconstructing a past illness possible: "we had the flu the first week of
 * March" is an episode with **both** ends in the past, and without a way to say so there is nowhere
 * to hang the records of it.
 *
 * Moving the dates re-files the records — anything unattached inside the new span is adopted,
 * anything of this episode's now outside it is released — so the span always means what it says. The
 * warning below is worth showing because that is a bigger consequence than "edit dates" suggests.
 */
@Composable
private fun EpisodeDatesDialog(
    episode: Episode,
    onDismiss: () -> Unit,
    onConfirm: (startedAt: Long, endedAt: Long?) -> Unit
) {
    var startedAt by remember { mutableLongStateOf(episode.startedAt) }
    var stillGoing by remember { mutableStateOf(episode.isOpen) }
    var endedAt by remember { mutableLongStateOf(episode.endedAt ?: System.currentTimeMillis()) }

    val backwards = !stillGoing && endedAt < startedAt

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(episode.title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WhenField(value = startedAt, onValueChange = { startedAt = it }, label = "Started")

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Still going", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = stillGoing, onCheckedChange = { stillGoing = it })
                }

                if (!stillGoing) {
                    WhenField(value = endedAt, onValueChange = { endedAt = it }, label = "Over")
                }

                if (backwards) {
                    Text(
                        "It can't have ended before it started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    "Records inside these dates are filed under this illness. Widening the span " +
                        "adopts anything that wasn't filed anywhere; narrowing it releases what " +
                        "falls outside. Nothing filed under another illness is touched, and no " +
                        "record is ever deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !backwards,
                onClick = { onConfirm(startedAt, endedAt.takeUnless { stillGoing }) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Pick what to add, then add it — the four record dialogs, reached from the history.
 *
 * The same dialogs the Today tab uses, deliberately: one form per kind of record, and the "when"
 * field they all carry is what makes them work as a backfill tool without needing a second set of
 * forms that could drift out of step with the first.
 */
@Composable
private fun BackfillDialogs(
    profile: Profile?,
    medications: List<Medication>,
    unit: TempUnit,
    onRecord: (BackfillRecord) -> Unit,
    onDismiss: () -> Unit
) {
    var choice by remember { mutableStateOf<TimelineKind?>(null) }

    when (choice) {
        null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("What happened?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Anything you remember. Health files it under the illness that was going " +
                            "on when it happened, so the date you give it is what matters — not " +
                            "today's.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    listOf(
                        TimelineKind.READING to "A temperature",
                        TimelineKind.DOSE to "A dose given",
                        TimelineKind.SYMPTOM_STARTED to "A symptom",
                        TimelineKind.CARE to "Something done — fluids, a call, a test"
                    ).forEach { (kind, label) ->
                        TextButton(onClick = { choice = kind }) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )

        TimelineKind.READING -> LogTemperatureDialog(
            unit = unit,
            ageMonths = profile?.ageMonthsAt(System.currentTimeMillis()),
            onDismiss = onDismiss,
            onConfirm = { celsius, site, note, at ->
                onRecord(BackfillRecord.Temperature(celsius, site, note, at))
                onDismiss()
            }
        )

        TimelineKind.DOSE -> LogDoseDialog(
            medications = medications,
            onDismiss = onDismiss,
            onConfirm = { medication, name, amount, doseUnit, note, at ->
                onRecord(BackfillRecord.Dose(medication, name, amount, doseUnit, note, at))
                onDismiss()
            }
        )

        TimelineKind.SYMPTOM_STARTED -> AddSymptomDialog(
            onDismiss = onDismiss,
            onConfirm = { name, severity, note, startedAt ->
                onRecord(BackfillRecord.Symptom(name, severity, note, startedAt))
                onDismiss()
            }
        )

        else -> CareNoteDialog(
            onDismiss = onDismiss,
            onConfirm = { kind, text, at ->
                onRecord(BackfillRecord.Care(kind, text, at))
                onDismiss()
            }
        )
    }
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    expanded: Boolean,
    summary: EpisodeSummary?,
    historyCount: Int,
    unit: TempUnit,
    onToggle: () -> Unit,
    onHistory: () -> Unit,
    onEditDates: () -> Unit,
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
                TextButton(onClick = onHistory) {
                    Text(if (expanded && historyCount > 0) "History ($historyCount)" else "History")
                }
                TextButton(onClick = onEditDates) { Text("Dates") }
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

// --- the person, and what is normal for them -------------------------------------------------------
//
// These four sit at the top of the Information tab because they are the context every other number in
// Health is read against. They also inherit what used to be the People tab: Health no longer keeps its
// own household screen, because the household is the People app's to own and two places to edit it
// would be two answers to "who lives here".

/**
 * Who this person is, as the household directory has them.
 *
 * Everything on this card is **read-only here on purpose**. Name, relationship and birth date come
 * over the People seam and are edited in People; showing them with an edit field would invite
 * somebody to change a name in Health and find it changed back on the next sync round, which is a
 * worse experience than not offering it.
 *
 * The birth date gets its own line when it is missing, because it is the one field here that is
 * load-bearing rather than decorative: it is what makes the fever thresholds age-aware, and a profile
 * without one silently gets the adult rules.
 */
@Composable
private fun AboutPersonCard(person: Profile, onEdit: () -> Unit) {
    val ageLabel = person.ageLabelAt(System.currentTimeMillis())

    SectionCard(
        title = person.name,
        trailing = { TextButton(onClick = onEdit) { Text("Edit") } }
    ) {
        val descriptor = listOfNotNull(
            person.relationship?.trim()?.ifBlank { null },
            ageLabel
        ).joinToString(" · ")
        if (descriptor.isNotBlank()) {
            Text(descriptor, style = MaterialTheme.typography.bodyMedium)
        }

        if (person.birthDate.isNullOrBlank()) {
            Text(
                "No birth date. Health will use the adult fever thresholds for them — the ones for a " +
                    "six-week-old are very different, so it is worth adding in People.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        Text(
            "Their name, relationship and birth date belong to the household directory — change them " +
                "in People and Health follows. Their usual temperature and the note below are " +
                "Health's own and never leave it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The person's own normal — the baseline every reading is implicitly compared against, and the note
 * you would want in front of you at 3am.
 *
 * Neither field is published over the People seam and neither has a column anywhere else in the
 * suite, so this is the only screen in the household that can change them. The note in particular is
 * refused by the packet mapper explicitly: People has a field called `note` too, and it means "likes
 * hiking, hates crowds".
 */
@Composable
private fun NormalForThemCard(person: Profile, unit: TempUnit, onEdit: () -> Unit) {
    SectionCard(
        title = "What's normal for them",
        trailing = { TextButton(onClick = onEdit) { Text("Edit") } }
    ) {
        val baseline = person.baselineTempC
        if (baseline == null) {
            Text(
                "No usual temperature recorded. Health will band readings against the published " +
                    "thresholds, which is the right default — but some people simply run at 36.4, and " +
                    "a 37.6 means more for them than the general rule suggests.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                "Usually ${Temperature.format(baseline, unit)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Recorded as their own normal, so a reading can be read against them rather than " +
                    "only against the population.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val notes = person.notes?.trim().orEmpty()
        if (notes.isNotBlank()) {
            HorizontalDivider()
            Text("Notes", style = MaterialTheme.typography.labelMedium)
            Text(notes, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * °C or °F.
 *
 * It lives on this tab rather than in a settings screen because choosing the unit and recording that
 * somebody runs at 36.4 are the same act — saying how temperatures should read for this household —
 * and Health has no settings screen otherwise. Readings are always stored in Celsius and converted
 * for display, so changing this never rewrites anything already recorded, and the card says so.
 */
@Composable
private fun DisplayUnitCard(unit: TempUnit, onSelect: (TempUnit) -> Unit) {
    SectionCard(title = "Display") {
        Text("Show temperatures in", style = MaterialTheme.typography.bodySmall)
        ChoiceRow(
            options = TempUnit.entries,
            selected = unit,
            onSelect = onSelect,
            label = { if (it == TempUnit.CELSIUS) "Celsius (°C)" else "Fahrenheit (°F)" }
        )
        Text(
            "Readings are always stored in Celsius and converted for display, so changing this never " +
                "rewrites anything already recorded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The two fields Health owns outright.
 *
 * Deliberately **not** a profile editor: there is no name, no relationship and no birth date on it,
 * because those are the directory's and offering them here would mean an edit that silently reverts
 * on the next sync round. The dialog says where to go for them instead of pretending.
 *
 * The baseline is typed in whatever unit the household is using and converted on the way in, because
 * somebody who reads temperatures in Fahrenheit does not know their child's normal in Celsius.
 */
@Composable
private fun HealthDetailsDialog(
    profile: Profile,
    unit: TempUnit,
    onDismiss: () -> Unit,
    onConfirm: (baselineC: Double?, notes: String?) -> Unit
) {
    var baseline by remember {
        mutableStateOf(profile.baselineTempC?.let { Temperature.formatBare(it, unit) }.orEmpty())
    }
    var notes by remember { mutableStateOf(profile.notes.orEmpty()) }

    val parsedBaseline = Temperature.parseToCelsius(baseline, unit)
    val baselineInvalid = baseline.isNotBlank() && parsedBaseline == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DecimalField(
                    value = baseline,
                    onValueChange = { baseline = it },
                    label = "Their usual temperature (${unit.symbol})",
                    isError = baselineInvalid,
                    supportingText = if (baselineInvalid) "That isn't a temperature Health can read"
                    else "Optional. Leave it empty and readings are banded against the published thresholds.",
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    supportingText = {
                        Text("Allergies, conditions, the doctor's number — whatever you'd want in front of you at 3am")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Neither of these ever leaves Health — they are not published to People, LifeOps " +
                        "or anywhere else. Name, relationship and birth date are the directory's: " +
                        "change those in People.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !baselineInvalid,
                onClick = {
                    onConfirm(
                        if (baseline.isBlank()) null else parsedBaseline,
                        notes.trim().ifBlank { null }
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
