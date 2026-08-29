package com.project.app.ui.docs

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.project.app.data.model.Doc
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.Outline
import com.project.app.logic.OutlineNode
import com.project.app.logic.ProjectKind
import com.project.app.logic.ProjectPulse
import com.project.app.ui.common.EmptyState
import com.project.app.ui.common.formatDayTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DocsViewModel(
    private val repo: ProjectRepository,
    private val projectId: String
) : ViewModel() {

    val docs: StateFlow<List<Doc>> =
        repo.observeDocs(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The outline, so a document can be told which piece of the work it is the text of. */
    val outline: StateFlow<List<OutlineNode>> =
        repo.observeOutline(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(title: String, onCreated: (String) -> Unit) =
        viewModelScope.launch { onCreated(repo.addDoc(projectId, title)) }

    fun update(doc: Doc) = viewModelScope.launch { repo.updateDoc(doc) }

    fun delete(docId: String) = viewModelScope.launch { repo.deleteDoc(docId) }

    class Factory(
        private val repo: ProjectRepository,
        private val projectId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DocsViewModel(repo, projectId) as T
    }
}

/**
 * Docs: the project's writing, as pages.
 *
 * This is the Notion half. A document here is a stack of blocks (see [DocEditorScreen]) and it can
 * be *linked to a piece of the outline* — which is the join that makes the two sections one app
 * rather than two. Link a document to a scene and the scene's word count becomes the document's,
 * kept in step on every keystroke; the outline stops being a plan you maintain by hand beside the
 * writing and becomes a view of it.
 *
 * The list shows length and the link, because those are what tell one draft from another when there
 * are forty of them.
 */
@Composable
fun DocsScreen(
    vm: DocsViewModel,
    kind: ProjectKind,
    onOpenDoc: (Doc) -> Unit
) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val outline by vm.outline.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf<Doc?>(null) }
    var confirmDelete by remember { mutableStateOf<Doc?>(null) }

    val titles = remember(outline) { Outline.flatten(outline).associate { it.node.id to "${it.number} ${it.node.title}" } }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New doc") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (docs.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "No documents yet",
                        detail = "Documents are where the writing goes. Link one to a ${kind.piece} " +
                            "and its words count towards that ${kind.piece}'s target."
                    )
                }
            }

            items(docs, key = { it.id }) { doc ->
                DocRow(
                    doc = doc,
                    linkedTitle = doc.outlineNodeId?.let { titles[it] },
                    onOpen = { onOpenDoc(doc) },
                    onLink = { linking = doc },
                    onDelete = { confirmDelete = doc }
                )
            }
        }
    }

    if (showAdd) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("New document") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    showAdd = false
                    vm.add(title) { }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }

    linking?.let { doc ->
        LinkToOutlineDialog(
            doc = doc,
            options = Outline.flatten(outline),
            onDismiss = { linking = null },
            onPick = { nodeId ->
                vm.update(doc.copy(outlineNodeId = nodeId))
                linking = null
            }
        )
    }

    confirmDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${doc.title}”?") },
            text = { Text("The document and everything written in it goes. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(doc.id)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DocRow(
    doc: Doc,
    linkedTitle: String?,
    onOpen: () -> Unit,
    onLink: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(doc.icon ?: "", style = MaterialTheme.typography.titleLarge)
            if (doc.icon == null) {
                Icon(Icons.Filled.Description, contentDescription = null)
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(doc.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${ProjectPulse.count(doc.wordCount)} words · ${formatDayTime(doc.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                linkedTitle?.let {
                    Text(
                        "Linked to $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Document actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (linkedTitle == null) "Link to outline…" else "Change link…") },
                        onClick = {
                            menuOpen = false
                            onLink()
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
private fun LinkToOutlineDialog(
    doc: Doc,
    options: List<com.project.app.logic.OutlineRow>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link “${doc.title}”") },
        text = {
            if (options.isEmpty()) {
                Text("There is nothing in the outline to link to yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item(key = "none") {
                        Text(
                            "Not linked",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(null) }
                                .padding(vertical = 8.dp)
                        )
                    }
                    items(options, key = { it.node.id }) { row ->
                        Text(
                            "${row.number}  ${row.node.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (row.node.id == doc.outlineNodeId) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(row.node.id) }
                                .padding(start = (row.depth.coerceAtMost(4) * 12).dp, top = 8.dp, bottom = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
