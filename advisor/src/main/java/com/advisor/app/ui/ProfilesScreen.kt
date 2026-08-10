package com.advisor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advisor.app.logic.Profile
import com.advisor.app.logic.ProfileEntry
import com.advisor.app.logic.ProfileKind

/**
 * The standing-profiles manager: the named, always-on dossiers (user, LLM persona, projects) the
 * assistant references by name and can append to. Add a project profile, jot entries into any of
 * them, and see what the assistant itself has written back (via its `@remember` directive).
 */
@Composable
fun ProfilesScreen(vm: AdvisorViewModel, modifier: Modifier = Modifier) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Standing profiles are always in the assistant's context and referenced by name — " +
                        "no lookup needed. Both you and the assistant can add to them.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(profiles, key = { it.key }) { profile ->
                ProfileCard(
                    profile = profile,
                    onAppend = { text -> vm.appendToProfile(profile.key, text) },
                    onDelete = { vm.deleteProfile(profile.key) }
                )
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showCreate = true },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("New profile") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }

    if (showCreate) {
        CreateProfileDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, kind, summary ->
                vm.createProfile(name, kind, summary)
                showCreate = false
            }
        )
    }
}

@Composable
private fun ProfileCard(profile: Profile, onAppend: (String) -> Unit, onDelete: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${profile.kind.key} · key: ${profile.key}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // The two seeded profiles have no delete affordance to avoid accidental loss.
                if (profile.kind != ProfileKind.USER && profile.kind != ProfileKind.PERSONA) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete profile")
                    }
                }
            }
            if (profile.summary.isNotBlank()) {
                Text(profile.summary, style = MaterialTheme.typography.bodySmall)
            }

            if (profile.entries.isEmpty()) {
                Text(
                    "No entries yet.",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic
                )
            } else {
                profile.entries.forEach { e -> EntryLine(e) }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { entry = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Add an entry…") },
                    singleLine = true
                )
                FilledIconButton(
                    onClick = { onAppend(entry); entry = "" },
                    enabled = entry.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add entry")
                }
            }
        }
    }
}

@Composable
private fun EntryLine(entry: ProfileEntry) {
    val badge = if (entry.author == ProfileEntry.AUTHOR_ADVISOR) "advisor" else "you"
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Text(entry.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            badge,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateProfileDialog(
    onDismiss: () -> Unit,
    onCreate: (String, ProfileKind, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ProfileKind.PROJECT) }
    var kindMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onCreate(name, kind, summary) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (e.g. Project A)") },
                    singleLine = true
                )
                ExposedDropdownMenuBox(expanded = kindMenu, onExpandedChange = { kindMenu = it }) {
                    OutlinedTextField(
                        value = kind.key,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Kind") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = kindMenu) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                        ProfileKind.entries.forEach { k ->
                            DropdownMenuItem(
                                text = { Text(k.key) },
                                onClick = { kind = k; kindMenu = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Summary (optional)") }
                )
            }
        }
    )
}
