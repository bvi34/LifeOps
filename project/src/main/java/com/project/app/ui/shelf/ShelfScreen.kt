package com.project.app.ui.shelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.operations.backupkit.AppId
import com.operations.suite.ui.fields.SuiteNoteField
import com.operations.suite.ui.fields.SuiteTextField
import com.project.app.data.model.Project
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.ProjectKind
import com.project.app.logic.ProjectPulse
import com.project.app.ui.common.EmptyState
import com.project.app.ui.common.ProgressBar
import com.project.app.ui.common.ProjectMark
import com.project.app.ui.common.PulseLine
import com.project.app.ui.common.formatDayTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShelfViewModel(private val repo: ProjectRepository) : ViewModel() {

    val shelf: StateFlow<List<Pair<Project, ProjectPulse>>> =
        repo.observeShelf().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addProject(name: String, kind: ProjectKind, summary: String?, onCreated: (String) -> Unit) =
        viewModelScope.launch { onCreated(repo.addProject(name, kind, summary)) }

    fun setArchived(projectId: String, archived: Boolean) =
        viewModelScope.launch { repo.setArchived(projectId, archived) }

    fun deleteProject(projectId: String) = viewModelScope.launch { repo.deleteProject(projectId) }

    class Factory(private val repo: ProjectRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ShelfViewModel(repo) as T
    }
}

/**
 * The shelf: every project, with the one line that says where it has got to.
 *
 * This is the app's front door and it is deliberately a list rather than a dashboard. A repository
 * of projects is browsed, not monitored — what you need on arrival is to recognise the thing you
 * were working on and get into it, which is a name, a colour, and "34,200 of 80,000 words · 12 of
 * 40 scenes". Everything else is one level in.
 *
 * Archived projects stay on the shelf behind a filter rather than disappearing. A project you have
 * stopped working on is not a project you want deleted, and the difference between "put away" and
 * "gone" is the whole reason an archive exists.
 */
@Composable
fun ShelfScreen(vm: ShelfViewModel, onOpenProject: (Project) -> Unit) {
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showArchived by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Project?>(null) }

    val visible = shelf.filter { (project, _) -> showArchived || !project.archived }
    val archivedCount = shelf.count { (project, _) -> project.archived }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New project") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (archivedCount > 0) {
                item(key = "filter") {
                    FilterChip(
                        selected = showArchived,
                        onClick = { showArchived = !showArchived },
                        leadingIcon = { Icon(Icons.Filled.Inventory2, contentDescription = null) },
                        label = { Text("Show archived ($archivedCount)") }
                    )
                }
            }

            if (visible.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "No projects yet",
                        detail = "A project is a novel, a piece of software, a thesis — anything with " +
                            "an outline, documents, lore, a timeline and work to get through."
                    )
                }
            }

            items(visible, key = { (project, _) -> project.id }) { (project, pulse) ->
                ProjectRow(
                    project = project,
                    pulse = pulse,
                    onOpen = { onOpenProject(project) },
                    onArchive = { vm.setArchived(project.id, !project.archived) },
                    onDelete = { confirmDelete = project }
                )
            }
        }
    }

    if (showAdd) {
        NewProjectDialog(
            onDismiss = { showAdd = false },
            onCreate = { name, kind, summary ->
                showAdd = false
                vm.addProject(name, kind, summary) { }
            }
        )
    }

    confirmDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${project.name}?") },
            // Say what goes, in full. A project is the only copy of the writing in it.
            text = {
                Text(
                    "This deletes the project and everything in it — outline, documents, lore, " +
                        "timeline, board and the files attached to it. It cannot be undone. " +
                        "Archiving keeps it instead."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val going = project
                    vm.deleteProject(going.id)
                    // The files attached to it go too, and Repository does not cascade on somebody
                    // else's rules — the owning app says what deleting one of its records means.
                    // Saying it here, beside the sentence that promises "everything in it".
                    scope.launch {
                        com.repository.app.RepositoryApp.get(context).documents
                            .deleteFiledOn(AppId.PROJECT.key, going.id)
                    }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ProjectRow(
    project: Project,
    pulse: ProjectPulse,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProjectMark(project.name, project.colorArgb)

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        project.kind.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PulseLine(pulse)
                pulse.wordProgress?.let {
                    Spacer(Modifier.height(2.dp))
                    ProgressBar(it)
                }
                Text(
                    if (project.archived) "Archived · last worked on ${formatDayTime(project.updatedAt)}"
                    else "Last worked on ${formatDayTime(project.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Project actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (project.archived) "Restore" else "Archive") },
                        onClick = {
                            menuOpen = false
                            onArchive()
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
    }
}

@Composable
private fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, ProjectKind, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ProjectKind.WRITING) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SuiteTextField(label = "Name", value = name, onValueChange = { name = it })
                SuiteNoteField(
                    label = "What is it? (optional)",
                    value = summary,
                    onValueChange = { summary = it },
                    minLines = 1,
                    maxLines = 3
                )
                // The kind only decides what the app calls things — scenes or tasks, chapters or
                // features. It is offered here rather than buried in settings because renaming
                // everything a week in is disorienting, and choosing now costs one tap.
                Text("Its pieces are called…", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    ProjectKind.entries.forEach { candidate ->
                        FilterChip(
                            selected = kind == candidate,
                            onClick = { kind = candidate },
                            label = { Text(candidate.pieces) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name, kind, summary.ifBlank { null }) }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
