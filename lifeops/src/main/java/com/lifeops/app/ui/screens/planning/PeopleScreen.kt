@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.planning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Person
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.util.PersonBookingStats

/**
 * The People page: household members whose weather-comfort preferences and notes drive
 * outdoor-task recommendations. Tap a person to edit their profile, jot notes, and mark which
 * tasks involve them.
 */
@Composable
fun PeopleScreen(
    viewModel: PeopleViewModel,
    onOpenPerson: (String) -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { AppHeader(navigationIcon = { onBack?.let { BackNavIcon(it) } }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "New person")
            }
        }
    ) { padding ->
        if (state.people.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No people yet. Tap + to add someone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.people, key = { it.id }) { person ->
                    PersonCard(
                        person = person,
                        taskCount = state.taskCounts[person.id] ?: 0,
                        bookingStats = state.bookingStats[person.id],
                        onOpen = { onOpenPerson(person.id) },
                        onArchiveToggle = { viewModel.setArchived(person, !person.isArchived) },
                        onDelete = { viewModel.delete(person) }
                    )
                }
            }
        }
    }

    if (showCreate) {
        NewPersonDialog(
            onConfirm = { name -> viewModel.createPerson(name); showCreate = false },
            onDismiss = { showCreate = false }
        )
    }
}

@Composable
private fun PersonCard(
    person: Person,
    taskCount: Int,
    bookingStats: PersonBookingStats?,
    onOpen: () -> Unit,
    onArchiveToggle: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dim = if (person.isArchived) 0.5f else 1f

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        person.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim)
                    )
                    person.relationship?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            it.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (person.isArchived) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "archived",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                Text(
                    buildString {
                        append(preferenceSummary(person))
                        append("   ·   ")
                        append("$taskCount task${if (taskCount == 1) "" else "s"}")
                        lastBookedSummary(bookingStats)?.let { append("   ·   "); append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (person.isArchived) "Unarchive" else "Archive") },
                        onClick = { menuOpen = false; onArchiveToggle() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

/** A one-line digest of whichever comfort ceilings the person has set. */
private fun preferenceSummary(person: Person): String {
    val parts = buildList {
        person.heatToleranceMaxF?.let { add("≤${it}°") }
        person.coldToleranceMinF?.let { add("≥${it}°") }
        person.uvMax?.let { add("UV≤$it") }
        person.windMaxMph?.let { add("wind≤${it}mph") }
        person.maxPrecipitationPct?.let { add("rain≤${it}%") }
    }
    return if (parts.isEmpty()) "No weather limits set" else parts.joinToString("  ")
}

/** "Booked 2d ago" / "Never booked" for a person with a relationship tracked; null if untracked. */
private fun lastBookedSummary(stats: PersonBookingStats?): String? {
    if (stats == null) return null
    val days = stats.daysSinceLastBooked ?: return "Never booked"
    return when (days) {
        0 -> "Booked today"
        1 -> "Booked 1d ago"
        else -> "Booked ${days}d ago"
    }
}

@Composable
private fun NewPersonDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New person") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
