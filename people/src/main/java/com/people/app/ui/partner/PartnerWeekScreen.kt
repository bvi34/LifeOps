package com.people.app.ui.partner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.people.app.data.model.PartnerEvent
import com.people.app.data.model.PartnerEventKind
import com.people.app.data.model.PartnerLink
import com.people.app.data.model.PartnerPendingTask
import com.people.app.data.model.PartnerTask
import com.people.app.data.repository.PartnerRepository
import com.people.app.data.repository.PartnerSyncService
import com.people.app.data.repository.PeopleRepository
import com.people.app.partner.SharedWeeks
import com.people.app.ui.common.SectionCard
import com.people.app.ui.common.formatDayTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class PartnerWeekViewModel(
    private val repo: PartnerRepository,
    private val syncService: PartnerSyncService,
    peopleRepo: PeopleRepository,
    private val personId: String
) : ViewModel() {

    val link: StateFlow<PartnerLink?> =
        repo.observeLink(personId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The directory's name for this person, resolved here rather than carried through the
     * navigation route: a household is full of names with spaces and apostrophes in them, and a
     * route argument is a URL.
     */
    val personName: StateFlow<String> = peopleRepo.observePerson(personId)
        .map { it?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val linkId = link.map { it?.id }

    val week: StateFlow<List<PartnerTask>> = linkId
        .flatMapLatest { id -> if (id == null) emptyFlow() else repo.observeWeek(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pending: StateFlow<List<PartnerPendingTask>> = linkId
        .flatMapLatest { id -> if (id == null) emptyFlow() else repo.observePending(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _news = MutableStateFlow<List<PartnerEvent>>(emptyList())

    /**
     * What the partner did since this screen was last opened.
     *
     * Held as a snapshot rather than observed. The screen marks these read as it shows them — that
     * is what opening the screen means — and an observed list would empty itself the instant it did,
     * so the reader would watch the news they came for vanish as it arrived.
     */
    val news: StateFlow<List<PartnerEvent>> = _news.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /**
     * Whether the first round has finished.
     *
     * Without it the screen cannot tell "no pairing" from "the database has not answered yet", and
     * would flash *This pairing has gone* at somebody whose pairing is perfectly fine.
     */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * Run a round, then collect what it turned up and mark it read.
     *
     * The link id is read from the database rather than from [link], which is a Room flow that may
     * still be emitting its initial null on the first frame — the one frame this runs on.
     */
    fun openAndCatchUp() = viewModelScope.launch {
        _syncing.value = true
        runCatching { syncService.sync() }
        repo.link(personId)?.let { current ->
            _news.value = repo.unseenEvents(current.id)
            repo.markSeen(current.id)
        }
        _loaded.value = true
        _syncing.value = false
    }

    fun addTask(title: String, dueDate: String?) = viewModelScope.launch {
        val current = repo.link(personId) ?: return@launch
        repo.addToPartnerWeek(current.id, title, dueDate)
        // Publish straight away. The whole point of adding something to somebody else's week is that
        // they see it; waiting for the next app open is how this would look broken.
        //
        // Publishing rather than a full catch-up, deliberately: a catch-up would replace the change
        // panel the user is reading with the empty result of a round they just triggered themselves.
        publish()
    }

    fun withdraw(pending: PartnerPendingTask) = viewModelScope.launch {
        repo.withdrawPending(pending.id)
        publish()
    }

    private suspend fun publish() {
        _syncing.value = true
        runCatching { syncService.sync() }
        _syncing.value = false
    }

    class Factory(
        private val repo: PartnerRepository,
        private val syncService: PartnerSyncService,
        private val peopleRepo: PeopleRepository,
        private val personId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PartnerWeekViewModel(repo, syncService, peopleRepo, personId) as T
    }
}

/**
 * A partner's current week.
 *
 * This screen is the reason the rest of the feature is shaped the way it is. It shows **their**
 * week — the tasks on their planner, ticked as they tick them — and it is the only place those tasks
 * appear. They are not on this household's week, not counted in its capacity, not filed under its
 * aspects; the copy behind this screen lives in People's own tables and goes no further.
 *
 * The one thing that travels the other way is the add box: a task written here is put on *their*
 * week, and is shown as pending until their app has taken it.
 */
@Composable
fun PartnerWeekScreen(vm: PartnerWeekViewModel, onBack: () -> Unit) {
    val link by vm.link.collectAsStateWithLifecycle()
    val personName by vm.personName.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val news by vm.news.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }

    // Sync on arrival, then clear the badge: opening this screen is reading what is on it.
    LaunchedEffect(Unit) { vm.openAndCatchUp() }

    val partnerName = link?.partnerName?.takeIf { it.isNotBlank() } ?: personName

    Scaffold(
        floatingActionButton = {
            if (link != null) {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add to their week") }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("$partnerName's week", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            weekLabel(week, pending),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(enabled = !syncing, onClick = { vm.openAndCatchUp() }) {
                        Text(if (syncing) "Syncing…" else "Sync now")
                    }
                    TextButton(onClick = onBack) { Text("Back") }
                }
            }

            if (news.isNotEmpty()) {
                item(key = "changes") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Since your last sync",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            news.take(8).forEach { event ->
                                Text(
                                    describe(event, partnerName),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            if (news.size > 8) {
                                Text(
                                    "and ${news.size - 8} more",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }

            if (link == null) {
                if (loaded) {
                    item(key = "unlinked") {
                        SectionCard(title = "Not paired") {
                            Text(
                                "This pairing has gone. Pair again from $personName's page to see " +
                                    "their week.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            } else {
                // Their week, by day. Days with nothing on them are still shown: a week is seven
                // days, and a list that silently omits Thursday reads as "nothing due" rather than
                // "we have not heard".
                val byDay = week.filter { it.dueDate != null }.groupBy { it.dueDate }
                items(
                    SharedWeeks.daysOf(SharedWeeks.startOf(LocalDate.now()).toString()),
                    key = { "day-$it" }
                ) { day ->
                    DayCard(day = day, tasks = byDay[day.toString()].orEmpty())
                }
            }

            val undated = week.filter { it.dueDate == null }
            if (undated.isNotEmpty()) {
                item(key = "undated") {
                    SectionCard(title = "Some time this week") {
                        undated.forEach { PartnerTaskRow(it) }
                    }
                }
            }

            if (pending.isNotEmpty()) {
                item(key = "pending") {
                    SectionCard(title = "Waiting for $partnerName's app") {
                        Text(
                            "Added by you. They will appear on their week when their app next opens.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        pending.forEach { task ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(task.title) },
                                supportingContent = task.dueDate?.let { due ->
                                    { Text(due, style = MaterialTheme.typography.bodySmall) }
                                },
                                trailingContent = {
                                    TextButton(onClick = { vm.withdraw(task) }) { Text("Withdraw") }
                                }
                            )
                        }
                    }
                }
            }

            item(key = "boundary") {
                Text(
                    "$partnerName's week is shown here only. It is not added to your own week, and " +
                        "nothing here counts towards your aspects or your capacity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showAdd) {
        AddPartnerTaskDialog(
            partnerName = partnerName,
            onDismiss = { showAdd = false },
            onConfirm = { title, due ->
                vm.addTask(title, due)
                showAdd = false
            }
        )
    }
}

@Composable
private fun DayCard(day: LocalDate, tasks: List<PartnerTask>) {
    SectionCard(title = dayFormat.format(day)) {
        if (tasks.isEmpty()) {
            Text(
                "Nothing on this day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            tasks.forEach { PartnerTaskRow(it) }
        }
    }
}

/**
 * One of their tasks.
 *
 * The checkbox is disabled on purpose rather than absent. It is their week: they tick their own
 * tasks, and a control that looked tappable would promise something this seam deliberately does not
 * allow. Showing it greyed keeps the state legible — done or not — without offering to change it.
 */
@Composable
private fun PartnerTaskRow(task: PartnerTask) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = task.done, onCheckedChange = null, enabled = false)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (task.done) TextDecoration.LineThrough else null
            )
            if (task.addedByUs) {
                Text(
                    "Added by you",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AddPartnerTaskDialog(
    partnerName: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, dueDate: String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }

    val days = SharedWeeks.daysOf(SharedWeeks.startOf(LocalDate.now()).toString())
    val dueValid = due.isBlank() || runCatching { LocalDate.parse(due) }.isSuccess

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to $partnerName's week") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("What needs doing?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Which day?", style = MaterialTheme.typography.bodySmall)
                // A week is seven buttons. A date picker for a choice this small is a dialog inside
                // a dialog, and the answer is never outside these seven days.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    days.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { day ->
                                val iso = day.toString()
                                TextButton(onClick = { due = if (due == iso) "" else iso }) {
                                    Text(
                                        shortDayFormat.format(day),
                                        style = if (due == iso) MaterialTheme.typography.labelLarge
                                        else MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    if (due.isBlank()) "No day set — some time this week." else "Due ${due}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "This becomes a task on $partnerName's LifeOps week. It is not added to yours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && dueValid,
                onClick = { onConfirm(title.trim(), due.ifBlank { null }) }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun weekLabel(week: List<PartnerTask>, pending: List<PartnerPendingTask>): String {
    val done = week.count { it.done }
    val parts = buildList {
        add("${week.size} task${if (week.size == 1) "" else "s"}")
        if (week.isNotEmpty()) add("$done done")
        if (pending.isNotEmpty()) add("${pending.size} waiting to send")
    }
    return parts.joinToString(" · ")
}

private fun describe(event: PartnerEvent, partnerName: String): String = when (event.kind) {
    PartnerEventKind.ADDED -> "$partnerName added \"${event.title}\""
    PartnerEventKind.EDITED -> "$partnerName changed \"${event.title}\""
    PartnerEventKind.COMPLETED -> "$partnerName finished \"${event.title}\""
    PartnerEventKind.REOPENED -> "$partnerName reopened \"${event.title}\""
    PartnerEventKind.REMOVED -> "$partnerName removed \"${event.title}\""
    PartnerEventKind.ADDED_TO_OUR_WEEK -> "$partnerName put \"${event.title}\" on your week"
    PartnerEventKind.CONNECTED -> "Connected to ${event.title} — ${formatDayTime(event.at)}"
}

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault())
private val shortDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
