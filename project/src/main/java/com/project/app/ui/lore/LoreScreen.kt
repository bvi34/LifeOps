package com.project.app.ui.lore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.operations.suite.ui.fields.SuiteNoteField
import com.operations.suite.ui.fields.SuiteTextField
import com.project.app.data.model.LoreEntryView
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.Lore
import com.project.app.logic.LoreCategory
import com.project.app.logic.LoreEntry
import com.project.app.ui.common.EmptyState
import com.project.app.ui.common.SectionCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LoreViewModel(
    private val repo: ProjectRepository,
    private val projectId: String
) : ViewModel() {

    val entries: StateFlow<List<LoreEntryView>> =
        repo.observeLore(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val brokenLinks: StateFlow<List<String>> =
        repo.observeBrokenLinks(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String, category: LoreCategory) =
        viewModelScope.launch { repo.addLoreEntry(projectId, name, category) }

    fun update(entry: LoreEntry, category: LoreCategory) =
        viewModelScope.launch { repo.updateLoreEntry(projectId, entry, category) }

    fun delete(id: String) = viewModelScope.launch { repo.deleteLoreEntry(projectId, id) }

    class Factory(
        private val repo: ProjectRepository,
        private val projectId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LoreViewModel(repo, projectId) as T
    }
}

/**
 * Lore: the project's wiki — the people, places and rules the work has to stay consistent with.
 *
 * Entries link to each other with `[[double brackets]]`, and only with those. Nothing is linked
 * because a word happened to appear in a sentence: automatic detection in a fiction wiki produces a
 * graph of coincidences, and the link you actually wanted is then lost among forty you didn't.
 *
 * Two lists here earn their place over any amount of browsing. **Backlinks** tell an entry where it
 * is spoken of, collected without anybody having recorded them — write "trained under [[Kestrel]]"
 * anywhere and Kestrel's page knows. And **broken links** are every name the project has referred to
 * and never written down, which is exactly the list of pages worth writing next; each one is one tap
 * from becoming an entry.
 */
@Composable
fun LoreScreen(vm: LoreViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val broken by vm.brokenLinks.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<LoreCategory?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LoreEntryView?>(null) }
    var confirmDelete by remember { mutableStateOf<LoreEntryView?>(null) }

    val names = remember(entries) { entries.associate { it.entry.id to it.entry.name } }
    val visible = remember(entries, query, category) {
        val byCategory = category?.let { wanted -> entries.filter { it.category == wanted } } ?: entries
        val matched = Lore.search(byCategory.map { it.entry }, query).map { it.id }.toSet()
        byCategory.filter { it.entry.id in matched }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New entry") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "search") {
                SuiteTextField(
                    label = "Search lore",
                    value = query,
                    onValueChange = { query = it },
                    // A query is not a sentence. Every screen that capitalises one makes somebody reach for
                    // shift-backspace before their first search of the day.
                    capitalise = KeyboardCapitalization.None
                )
            }

            item(key = "categories") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = category == null,
                        onClick = { category = null },
                        label = { Text("All") }
                    )
                    LoreCategory.entries.forEach { candidate ->
                        FilterChip(
                            selected = category == candidate,
                            onClick = { category = if (category == candidate) null else candidate },
                            label = { Text(candidate.label) }
                        )
                    }
                }
            }

            if (broken.isNotEmpty()) {
                item(key = "broken") {
                    SectionCard(title = "Referred to but never written") {
                        Text(
                            "Each of these is linked from somewhere and has no entry. Tap one to write it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            broken.forEach { name ->
                                AssistChip(
                                    onClick = { vm.add(name, LoreCategory.OTHER) },
                                    leadingIcon = { Icon(Icons.Filled.LinkOff, contentDescription = null) },
                                    label = { Text(name) }
                                )
                            }
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "No lore yet",
                        detail = "People, places, factions, rules — anything the work has to stay " +
                            "consistent about. Link them to each other by writing [[their name]]."
                    )
                }
            }

            items(visible, key = { it.entry.id }) { view ->
                LoreCard(
                    view = view,
                    names = names,
                    onEdit = { editing = view },
                    onDelete = { confirmDelete = view }
                )
            }
        }
    }

    if (adding) {
        NewEntryDialog(
            onDismiss = { adding = false },
            onCreate = { name, chosen ->
                vm.add(name, chosen)
                adding = false
            }
        )
    }

    editing?.let { view ->
        EditEntryDialog(
            view = view,
            onDismiss = { editing = null },
            onSave = { entry, chosen ->
                vm.update(entry, chosen)
                editing = null
            }
        )
    }

    confirmDelete?.let { view ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${view.entry.name}”?") },
            text = {
                Text(
                    if (view.backlinkIds.isEmpty()) {
                        "Nothing links to it."
                    } else {
                        "${view.backlinkIds.size} other " +
                            "${if (view.backlinkIds.size == 1) "entry links" else "entries link"} to it. " +
                            "Those links will show as broken, which is how you find them again."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(view.entry.id)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun LoreCard(
    view: LoreEntryView,
    names: Map<String, String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    view.entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(view.colorArgb),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    view.category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Entry actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit…") },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete…") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            view.entry.summary?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            if (view.entry.aliases.isNotEmpty()) {
                Text(
                    "Also: ${view.entry.aliases.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (view.links.isNotEmpty()) {
                Text(
                    "Links to " + view.links.joinToString(", ") { mention ->
                        when {
                            mention.ambiguous -> "${mention.target} (ambiguous)"
                            mention.isBroken -> "${mention.target} (not written)"
                            else -> mention.target
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (view.backlinkIds.isNotEmpty()) {
                Text(
                    "Mentioned by " + view.backlinkIds.mapNotNull { names[it] }.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun NewEntryDialog(onDismiss: () -> Unit, onCreate: (String, LoreCategory) -> Unit) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(LoreCategory.CHARACTER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New lore entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SuiteTextField(label = "Name", value = name, onValueChange = { name = it })
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    LoreCategory.entries.forEach { candidate ->
                        FilterChip(
                            selected = category == candidate,
                            onClick = { category = candidate },
                            label = { Text(candidate.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, category) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditEntryDialog(
    view: LoreEntryView,
    onDismiss: () -> Unit,
    onSave: (LoreEntry, LoreCategory) -> Unit
) {
    val id = view.entry.id
    var name by remember(id) { mutableStateOf(view.entry.name) }
    var summary by remember(id) { mutableStateOf(view.entry.summary.orEmpty()) }
    var body by remember(id) { mutableStateOf(view.entry.body) }
    var aliases by remember(id) { mutableStateOf(view.entry.aliases.joinToString(", ")) }
    var category by remember(id) { mutableStateOf(view.category) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(view.entry.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SuiteTextField(label = "Name", value = name, onValueChange = { name = it })
                SuiteTextField(
                    label = "Also called (comma separated)",
                    value = aliases,
                    onValueChange = { aliases = it }
                )
                SuiteNoteField(
                    label = "In one line",
                    value = summary,
                    onValueChange = { summary = it },
                    // Prose, but one sentence of it: it starts at a single line the way it always has, and
                    // stops growing before it pushes the dialog's buttons off the screen.
                    minLines = 1,
                    maxLines = 3
                )
                SuiteNoteField(
                    label = "Everything else — link with [[name]]",
                    value = body,
                    onValueChange = { body = it }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    LoreCategory.entries.forEach { candidate ->
                        FilterChip(
                            selected = category == candidate,
                            onClick = { category = candidate },
                            label = { Text(candidate.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    view.entry.copy(
                        name = name,
                        summary = summary.ifBlank { null },
                        body = body,
                        aliases = Lore.parseAliases(aliases)
                    ),
                    category
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
