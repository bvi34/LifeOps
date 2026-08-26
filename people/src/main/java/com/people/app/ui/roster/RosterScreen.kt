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
import com.people.app.data.model.Person
import com.people.app.data.model.SyncStatus
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

class RosterViewModel(
    private val repo: PeopleRepository,
    private val syncService: PeopleSyncService,
    private val peers: List<String>
) : ViewModel() {

    val people: StateFlow<List<Person>> =
        repo.observeAllPeople().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val upcoming: StateFlow<List<Pair<Person, UpcomingDate>>> =
        repo.observeUpcoming().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncStatus = MutableStateFlow<SyncStatus?>(null)
    val syncStatus: StateFlow<SyncStatus?> = _syncStatus.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

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
        private val peers: List<String>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RosterViewModel(repo, syncService, peers) as T
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
