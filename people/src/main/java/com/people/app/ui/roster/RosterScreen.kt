package com.people.app.ui.roster

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.people.app.data.model.PartnerLink
import com.people.app.data.model.PartnerLinkState
import com.people.app.data.model.PartnerSyncStatus
import com.people.app.data.model.Person
import com.people.app.data.model.SyncStatus
import com.people.app.data.prefs.PartnerPrefs
import com.people.app.data.repository.CheckInRepository
import com.people.app.data.repository.PartnerRepository
import com.people.app.data.repository.PartnerSyncService
import com.people.app.data.repository.PeopleRepository
import com.people.app.data.repository.PeopleSyncService
import com.people.app.logic.ImportantDates
import com.people.app.logic.UpcomingDate
import com.people.app.ui.common.HouseholdToggle
import com.people.app.ui.common.PersonDot
import com.people.app.ui.common.SectionCard
import com.people.app.ui.common.formatDayTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

class RosterViewModel(
    private val repo: PeopleRepository,
    private val syncService: PeopleSyncService,
    private val peers: List<String>,
    partnerRepo: PartnerRepository,
    checkInRepo: CheckInRepository,
    private val partnerSync: PartnerSyncService,
    private val partnerPrefs: PartnerPrefs,
    /** Where partner envelopes are exchanged, shown so a person can find it from outside the app. */
    private val partnerSyncDir: File
) : ViewModel() {

    /**
     * Unread partner changes per person.
     *
     * On the roster because a partner round runs while the app is *closed*: without a mark here,
     * the only way to discover that somebody changed their week is to open their page and look,
     * which is not a notification.
     */
    val partnerBadges: StateFlow<Map<String, Int>> = partnerRepo.observeUnseenByPerson()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Every pairing, whatever state it is in — the roster's account of the partner seam.
     *
     * The seam had no representation outside a person's page, which made it invisible until you
     * already knew where to look and impossible to act on when nothing was paired yet. This is the
     * one place that can say "set up" or "nothing arrived" about the seam as a whole.
     */
    val partnerLinks: StateFlow<List<PartnerLink>> = partnerRepo.observeLinks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Who has a check-in form and nothing recorded on it today.
     *
     * On the roster for the same reason the partner badge is: a daily thing that is only visible
     * inside a person's page is a daily thing nobody does. It names the people, not a count, so the
     * mark sits on the row somebody has to open anyway.
     */
    val checkInsDue: StateFlow<Set<String>> = checkInRepo.observeOutstanding(LocalDate.now())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val people: StateFlow<List<Person>> =
        repo.observeAllPeople().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val upcoming: StateFlow<List<Pair<Person, UpcomingDate>>> =
        repo.observeUpcoming().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncStatus = MutableStateFlow<SyncStatus?>(null)
    val syncStatus: StateFlow<SyncStatus?> = _syncStatus.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    // --- the partner seam ---

    /**
     * This install's identity on the partner seam, or null while it has none.
     *
     * Read through [PartnerPrefs.existingInstanceId] rather than [PartnerPrefs.instanceId], because
     * the latter mints one: a screen that asked the minting question to decide whether to offer
     * "set up" would have done the setting up itself, and every household that ever opened People
     * would own a partner identity it never asked for.
     */
    private val _partnerInstanceId = MutableStateFlow(partnerPrefs.existingInstanceId)
    val partnerInstanceId: StateFlow<String?> = _partnerInstanceId.asStateFlow()

    private val _partnerName = MutableStateFlow(partnerPrefs.displayName)
    val partnerName: StateFlow<String> = _partnerName.asStateFlow()

    private val _partnerLastRoundAt = MutableStateFlow(partnerPrefs.lastRoundAt)
    val partnerLastRoundAt: StateFlow<Long> = _partnerLastRoundAt.asStateFlow()

    private val _partnerStatus = MutableStateFlow<PartnerSyncStatus?>(null)
    val partnerStatus: StateFlow<PartnerSyncStatus?> = _partnerStatus.asStateFlow()

    private val _partnerSyncing = MutableStateFlow(false)
    val partnerSyncing: StateFlow<Boolean> = _partnerSyncing.asStateFlow()

    /** The folder partner envelopes are exchanged in, for the person who wants to move them. */
    val partnerFolder: String get() = partnerSyncDir.absolutePath

    fun setPartnerName(name: String) {
        _partnerName.value = name
        partnerPrefs.displayName = name
    }

    /**
     * Run a partner round now — and, on a household that has never paired with anybody, *be* the
     * setting up: the round mints this install's identity, creates the exchange folder and publishes
     * an envelope naming us, none of which existed before somebody pressed this.
     *
     * Worth having a button for even once pairing is done. Rounds otherwise happen only when the app
     * comes to the foreground, so "they said they'd added it — has it arrived?" had no answer short
     * of leaving the app and coming back.
     */
    fun syncPartners() = viewModelScope.launch {
        _partnerSyncing.value = true
        _partnerStatus.value = runCatching { partnerSync.sync() }
            .getOrElse {
                PartnerSyncStatus(
                    ranAt = System.currentTimeMillis(),
                    linksSynced = 0,
                    changes = 0,
                    takenOntoOurWeek = 0,
                    error = it.message ?: it::class.java.simpleName
                )
            }
        _partnerInstanceId.value = partnerPrefs.existingInstanceId
        _partnerLastRoundAt.value = partnerPrefs.lastRoundAt
        _partnerSyncing.value = false
    }

    fun addPerson(
        name: String,
        relationship: String?,
        birthDate: String?,
        email: String?,
        phone: String?,
        household: Boolean
    ) = viewModelScope.launch {
        val color = PeopleSyncService.PROFILE_COLORS[people.value.size % PeopleSyncService.PROFILE_COLORS.size]
        repo.addPerson(name, relationship, birthDate, email, phone, note = null, colorArgb = color, household = household)
        // Publish straight away so the other apps see the new person without waiting for a launch,
        // and re-read theirs in full: this may be a second row for somebody the seam already carries
        // under another key, and the packet that would prove it is behind our cursor.
        sync(rescan = true)
    }

    fun sync(rescan: Boolean = false) = viewModelScope.launch {
        _syncing.value = true
        _syncStatus.value = runCatching { syncService.sync(peers, rescan) }
            .getOrElse { SyncStatus(System.currentTimeMillis(), emptyList(), 0, 0, it.message) }
        _syncing.value = false
    }

    class Factory(
        private val repo: PeopleRepository,
        private val syncService: PeopleSyncService,
        private val peers: List<String>,
        private val partnerRepo: PartnerRepository,
        private val checkInRepo: CheckInRepository,
        private val partnerSync: PartnerSyncService,
        private val partnerPrefs: PartnerPrefs,
        private val partnerSyncDir: File
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RosterViewModel(
                repo, syncService, peers, partnerRepo, checkInRepo, partnerSync, partnerPrefs,
                partnerSyncDir
            ) as T
    }
}

/**
 * The household directory: who is in it, what's coming up for them, and the state of the seam that
 * keeps the other apps in step.
 */
@Composable
fun RosterScreen(vm: RosterViewModel, onOpenPerson: (Person) -> Unit) {
    val people by vm.people.collectAsStateWithLifecycle()
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val partnerBadges by vm.partnerBadges.collectAsStateWithLifecycle()
    val checkInsDue by vm.checkInsDue.collectAsStateWithLifecycle()
    val partnerLinks by vm.partnerLinks.collectAsStateWithLifecycle()
    val partnerInstanceId by vm.partnerInstanceId.collectAsStateWithLifecycle()
    val partnerName by vm.partnerName.collectAsStateWithLifecycle()
    val partnerLastRoundAt by vm.partnerLastRoundAt.collectAsStateWithLifecycle()
    val partnerStatus by vm.partnerStatus.collectAsStateWithLifecycle()
    val partnerSyncing by vm.partnerSyncing.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                text = { Text("Add person") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (upcoming.isNotEmpty()) {
                item(key = "upcoming") {
                    SectionCard(title = "Coming up") {
                        upcoming.take(6).forEach { (person, date) ->
                            ListItem(
                                headlineContent = { Text(date.label) },
                                supportingContent = {
                                    val turning = date.turning?.let { " · turns $it" } ?: ""
                                    Text(
                                        "${ImportantDates.describe(date.daysUntil)}$turning",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                leadingContent = { PersonDot(person, size = 32) },
                                modifier = Modifier.clickable { onOpenPerson(person) }
                            )
                        }
                    }
                }
            }

            if (people.isEmpty()) {
                item(key = "empty") {
                    SectionCard(title = "Nobody here yet") {
                        Text(
                            "People is the directory the rest of the suite refers to. Add someone " +
                                "here and they turn up in LifeOps too — and anyone LifeOps already " +
                                "knows about arrives here on the next sync.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                item(key = "roster-header") {
                    Text("Household", style = MaterialTheme.typography.titleSmall)
                }
                items(people, key = { it.id }) { person ->
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenPerson(person) }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PersonDot(person)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(person.name, style = MaterialTheme.typography.titleMedium)
                                val details = listOfNotNull(
                                    person.relationship,
                                    person.email,
                                    if (person.id in checkInsDue) "check-in due" else null,
                                    if (person.household) "in Health" else null,
                                    if (person.archived) "archived" else null
                                )
                                if (details.isNotEmpty()) {
                                    Text(
                                        details.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            partnerBadges[person.id]?.takeIf { it > 0 }?.let { unseen ->
                                Badge { Text("$unseen") }
                            }
                        }
                    }
                }
            }

            item(key = "sync") {
                SectionCard(
                    title = "Sync",
                    trailing = {
                        TextButton(onClick = { vm.sync() }, enabled = !syncing) {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Sync now")
                        }
                    }
                ) {
                    Text(
                        "People, LifeOps and Health each keep their own roster and reconcile over a " +
                            "shared mailbox — the same seam Citation rides. Edits flow both ways; " +
                            "whoever edited most recently wins a field, and a blank never " +
                            "overwrites a value.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Health only takes the people marked as household members — that switch is " +
                            "on each person's page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    status?.let { result ->
                        val summary = buildString {
                            append("Last run ").append(formatDayTime(result.lastRunAt ?: 0L))
                            append(" · ").append(result.received).append(" in, ")
                            append(result.sent).append(" out")
                            if (result.peersSeen.isEmpty()) append(" · no peer has published yet")
                        }
                        Text(summary, style = MaterialTheme.typography.bodySmall)
                        result.error?.let {
                            Text(
                                "Trouble: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item(key = "partner-sync") {
                PartnerSeamCard(
                    instanceId = partnerInstanceId,
                    displayName = partnerName,
                    links = partnerLinks,
                    lastRoundAt = partnerLastRoundAt,
                    status = partnerStatus,
                    syncing = partnerSyncing,
                    folder = vm.partnerFolder,
                    onNameChange = vm::setPartnerName,
                    onSync = vm::syncPartners
                )
            }
        }
    }

    if (showAdd) {
        AddPersonDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, relationship, birthDate, email, phone, household ->
                vm.addPerson(name, relationship, birthDate, email, phone, household)
                showAdd = false
            }
        )
    }
}

/**
 * The partner seam, on the screen everybody opens first.
 *
 * It is here because the seam had nowhere else to be. Pairing lives on a person's page — correctly,
 * since a pairing is with a person — but everything *about the seam itself* had no home: this
 * install's identity, the name partners see, when a round last ran, and the folder envelopes are
 * exchanged in. A household that had not paired with anybody had no way to set any of it up, and one
 * that had could only make a round happen by leaving the app and coming back.
 *
 * The button is the same action either way, and says which it is: on a household with no identity
 * yet, running a round *is* the setting up.
 */
@Composable
private fun PartnerSeamCard(
    instanceId: String?,
    displayName: String,
    links: List<PartnerLink>,
    lastRoundAt: Long,
    status: PartnerSyncStatus?,
    syncing: Boolean,
    folder: String,
    onNameChange: (String) -> Unit,
    onSync: () -> Unit
) {
    SectionCard(
        title = "Partner sync",
        trailing = {
            TextButton(onClick = onSync, enabled = !syncing) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (instanceId == null) "Set up" else "Sync now")
            }
        }
    ) {
        Text(
            "A separate seam from the one above: it pairs this household with somebody else's " +
                "LifeOps, by QR code, on that person's page. Their week is shown in People and " +
                "never joins your own.",
            style = MaterialTheme.typography.bodySmall
        )

        if (instanceId == null) {
            Text(
                "Not set up on this device yet. Setting up gives this install its identity on the " +
                    "seam and creates the folder envelopes are exchanged in. It pairs you with " +
                    "nobody — that still takes two people and two scans.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedTextField(
            value = displayName,
            onValueChange = onNameChange,
            label = { Text("Your name, as partners see it") },
            supportingText = { Text("Shown beside your week on their device.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (links.isEmpty()) {
            Text(
                "Nobody paired. Open a person and swap codes — you scan theirs, they scan yours.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            links.forEach { link ->
                Text(
                    "${link.partnerName.ifBlank { "Unnamed partner" }} · ${describePairing(link)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())

        // A round from this session, with what it found — or, in the frames before the one that runs
        // on foreground has finished, the timestamp of the last round of all. The stamp is kept in
        // prefs and the result is not, so after a restart the second line is the only one there is.
        if (status != null) {
            Text(
                buildString {
                    append("Last run ").append(formatDayTime(status.ranAt))
                    append(" · ").append(status.linksSynced).append(" paired, ")
                    append(status.changes).append(" change").append(if (status.changes == 1) "" else "s")
                    if (status.takenOntoOurWeek > 0) {
                        append(" · ").append(status.takenOntoOurWeek).append(" onto your week")
                    }
                },
                style = MaterialTheme.typography.bodySmall
            )
            status.error?.let { trouble ->
                Text(
                    "Trouble: $trouble",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else if (lastRoundAt > 0L) {
            Text(
                "Last partner round ${formatDayTime(lastRoundAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (instanceId != null) {
            Text(
                "Envelopes are exchanged in $folder",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Which half of the handshake a pairing is waiting on, in the words the person needs. */
private fun describePairing(link: PartnerLink): String = when (link.state) {
    PartnerLinkState.LINKED ->
        if (link.unseenChanges > 0) "connected · ${link.unseenChanges} unread" else "connected"

    PartnerLinkState.AWAITING_THEM -> "waiting for them to scan your code"
    PartnerLinkState.AWAITING_SCAN -> "code shown; you have not scanned theirs"
}

@Composable
private fun AddPersonDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        relationship: String?,
        birthDate: String?,
        email: String?,
        phone: String?,
        household: Boolean
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var household by remember { mutableStateOf(false) }

    val birthDateValid = birthDate.isBlank() || ImportantDates.parseIso(birthDate) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a person") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = relationship,
                    onValueChange = { relationship = it },
                    label = { Text("Who they are (optional)") },
                    placeholder = { Text("Me, Daughter, Mum…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = birthDate,
                    onValueChange = { birthDate = it },
                    label = { Text("Birth date (YYYY-MM-DD)") },
                    isError = !birthDateValid,
                    supportingText = { Text(if (birthDateValid) "Shows up under Coming up." else "Use YYYY-MM-DD.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (optional)") },
                    supportingText = { Text("How the other apps recognise the same person.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                HouseholdToggle(checked = household, onCheckedChange = { household = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && birthDateValid,
                onClick = {
                    onConfirm(
                        name.trim(),
                        relationship.ifBlank { null },
                        birthDate.ifBlank { null },
                        email.ifBlank { null },
                        phone.ifBlank { null },
                        household
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
