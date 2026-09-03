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
import androidx.compose.material.icons.filled.Folder
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
import com.operations.backupkit.AppId
import com.project.app.data.model.Doc
import com.project.app.data.model.Project
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.Outline
import com.project.app.logic.Tree
import com.project.app.logic.OutlineNode
import com.project.app.logic.ProjectPulse
import com.project.app.ui.common.DocPickerDialog
import com.project.app.ui.common.EmptyState
import com.project.app.ui.common.OutlinePickerDialog
import com.project.app.ui.common.formatDayTime
import com.repository.app.logic.DocumentKind
import com.repository.app.ui.attach.DocumentsPanel
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

    fun move(docId: String, newParentId: String?) =
        viewModelScope.launch { repo.moveDoc(projectId, docId, newParentId) }

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
 * Documents nest, so a project can keep its chapters in folders and its research beside them rather
 * than in one flat list of forty. The nesting is the same parent-pointer tree as the outline and is
 * walked by the same code (`logic/Tree`), which is why an orphaned document is still drawn — at the
 * top level — instead of vanishing with the folder that held it.
 *
 * The list shows length and the link, because those are what tell one draft from another when there
 * are forty of them.
 */
@Composable
fun DocsScreen(
    vm: DocsViewModel,
    project: Project,
    onOpenDoc: (Doc) -> Unit
) {
    val kind = project.kind
    val docs by vm.docs.collectAsStateWithLifecycle()
    val outline by vm.outline.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf<Doc?>(null) }
    var moving by remember { mutableStateOf<Doc?>(null) }
    var confirmDelete by remember { mutableStateOf<Doc?>(null) }

    val outlineRows = remember(outline) { Outline.flatten(outline) }
    val titles = remember(outlineRows) { outlineRows.associate { it.node.id to "${it.number} ${it.node.title}" } }
    val tree = remember(docs) {
        Tree.flatten(docs, { it.id }, { it.parentDocId }, compareBy({ it.sortOrder }, { it.title.lowercase() }))
    }

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
            // The project's *files*, as against its writing. A brief that arrived as a PDF, the
            // signed contract, the reference images somebody was sent — none of them are documents
            // this app should be re-typing into blocks, and all of them are things the household
            // will later look for from the shelf rather than from here. So they are Repository's
            // rows, shown in place: `com.repository.app.ui.attach.DocumentsPanel`.
            item(key = "files") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Files", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Attached to the project, filed on the suite's shelf.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DocumentsPanel(
                            appKey = AppId.PROJECT.key,
                            recordKey = project.id,
                            recordLabel = project.name,
                            modifier = Modifier.padding(top = 8.dp),
                            kinds = PROJECT_KINDS,
                            emptyLine = "The brief, the contract, the reference PDFs — add them from " +
                                "this phone, get them off Google Drive or OneDrive, or attach " +
                                "something already on the shelf."
                        )
                    }
                }
            }

            if (docs.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "No documents yet",
                        detail = "Documents are where the writing goes. Link one to a ${kind.piece} " +
                            "and its words count towards that ${kind.piece}'s target."
                    )
                }
            }

            items(tree, key = { it.item.id }) { row ->
                DocRow(
                    doc = row.item,
                    depth = row.depth,
                    hasChildren = row.hasChildren,
                    linkedTitle = row.item.outlineNodeId?.let { titles[it] },
                    onOpen = { onOpenDoc(row.item) },
                    onLink = { linking = row.item },
                    onMove = { moving = row.item },
                    onDelete = { confirmDelete = row.item }
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
        OutlinePickerDialog(
            title = "Link “${doc.title}”",
            rows = outlineRows,
            selectedId = doc.outlineNodeId,
            onDismiss = { linking = null },
            onPick = { nodeId ->
                vm.update(doc.copy(outlineNodeId = nodeId))
                linking = null
            }
        )
    }

    moving?.let { doc ->
        DocPickerDialog(
            title = "Move “${doc.title}” into",
            rows = tree,
            selectedId = doc.parentDocId,
            onDismiss = { moving = null },
            onPick = { parentId ->
                vm.move(doc.id, parentId)
                moving = null
            },
            noneLabel = "Top level",
            // A document cannot be filed inside itself or anything under it.
            excluded = Tree.subtree(docs, { it.id }, { it.parentDocId }, doc.id).toSet()
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
    depth: Int,
    hasChildren: Boolean,
    linkedTitle: String?,
    onOpen: () -> Unit,
    onLink: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Capped like the outline's: depth beyond four stops eating the title.
            .padding(start = (depth.coerceAtMost(4) * 16).dp)
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (doc.icon != null) {
                Text(doc.icon, style = MaterialTheme.typography.titleLarge)
            } else {
                // A document holding others reads as a folder, without being a different kind of
                // thing: it still has its own text, and can still be opened and written in.
                Icon(
                    if (hasChildren) Icons.Filled.Folder else Icons.Filled.Description,
                    contentDescription = null
                )
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
                        text = { Text("Move into…") },
                        onClick = {
                            menuOpen = false
                            onMove()
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

/**
 * What a project is usually handed, offered first when filing.
 *
 * A brief is a contract-shaped thing, a signed agreement is a contract, and everything else a
 * project accumulates is correspondence or reference. The list is only the *order the chips come
 * in* — every kind is still reachable — and Repository behaves identically whichever is chosen,
 * because it does not read documents.
 */
private val PROJECT_KINDS = listOf(
    DocumentKind.CONTRACT,
    DocumentKind.CORRESPONDENCE,
    DocumentKind.REPORT,
    DocumentKind.RECEIPT,
    DocumentKind.RECORD,
    DocumentKind.OTHER
)
