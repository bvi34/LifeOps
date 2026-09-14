package com.project.app.ui.outline

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FormatIndentDecrease
import androidx.compose.material.icons.filled.FormatIndentIncrease
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.operations.suite.ui.fields.SuiteNoteField
import com.operations.suite.ui.fields.SuiteNumberField
import com.operations.suite.ui.fields.SuiteTextField
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.AttachKind
import com.project.app.logic.Outline
import com.project.app.logic.OutlineNode
import com.project.app.logic.OutlineRow
import com.project.app.logic.OutlineStatus
import com.project.app.logic.ProjectKind
import com.project.app.logic.ProjectPulse
import com.project.app.ui.common.EmptyState
import com.project.app.ui.common.ProgressBar
import com.project.app.ui.common.SectionCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OutlineViewModel(
    private val repo: ProjectRepository,
    private val projectId: String
) : ViewModel() {

    val rows: StateFlow<List<OutlineRow>> =
        repo.outline.observeOutline(projectId)
            .map { Outline.flatten(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _pendingDelete = MutableStateFlow<Pair<OutlineNode, Int>?>(null)
    val pendingDelete: StateFlow<Pair<OutlineNode, Int>?> = _pendingDelete.asStateFlow()

    fun add(parentId: String?, title: String) =
        viewModelScope.launch { repo.outline.addOutlineNode(projectId, parentId, title) }

    fun update(node: OutlineNode) = viewModelScope.launch { repo.outline.updateOutlineNode(node) }

    fun setStatus(id: String, status: OutlineStatus) =
        viewModelScope.launch { repo.outline.setOutlineStatus(id, status) }

    fun move(id: String, delta: Int) = viewModelScope.launch { repo.outline.moveOutlineNode(projectId, id, delta) }

    fun indent(id: String) = viewModelScope.launch { repo.outline.indentOutlineNode(projectId, id) }

    fun outdent(id: String) = viewModelScope.launch { repo.outline.outdentOutlineNode(projectId, id) }

    /**
     * Ask before deleting, and say how much goes.
     *
     * Deleting an act takes its chapters and their scenes with it, and the only honest way to offer
     * that is to count them first — "delete 14 pieces" is a decision; "delete" is a trap.
     */
    fun askDelete(node: OutlineNode) = viewModelScope.launch {
        _pendingDelete.value = node to repo.outline.subtreeSize(projectId, node.id)
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() = viewModelScope.launch {
        val (node, _) = _pendingDelete.value ?: return@launch
        _pendingDelete.value = null
        repo.outline.deleteOutlineSubtree(projectId, node.id)
    }

    class Factory(
        private val repo: ProjectRepository,
        private val projectId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OutlineViewModel(repo, projectId) as T
    }
}

/**
 * The outline: the shape of the thing, as a tree you can rearrange.
 *
 * This is the Reedsy half of the app. An outline is not a to-do list — its rows are the *parts of
 * the work itself*, they nest, and they carry a length as well as a state. So each row shows where
 * it has got to, and what it weighs: words written against a target, rolled up from everything
 * beneath it, so an act's line is the sum of its scenes without anyone maintaining it.
 *
 * The four structural edits are on the row rather than behind a drag gesture. Dragging a nested
 * tree on a phone is a coin toss between "moved" and "re-parented", and being able to say
 * *precisely* "down one" or "indent" is worth more than the gesture — especially at the point in a
 * draft where you are moving a scene between chapters and cannot afford to guess where it landed.
 */
@Composable
fun OutlineScreen(
    vm: OutlineViewModel,
    kind: ProjectKind,
    onOpenFiles: (AttachKind, String) -> Unit = { _, _ -> }
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val pending by vm.pendingDelete.collectAsStateWithLifecycle()

    var addingUnder by remember { mutableStateOf<String?>(null) }
    var addingRoot by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<OutlineNode?>(null) }

    val totals = remember(rows) { Outline.projectTotals(rows.map { it.node }) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addingRoot = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add ${kind.part}") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (rows.isNotEmpty()) {
                item(key = "totals") {
                    SectionCard(title = "The whole thing") {
                        Text(
                            buildString {
                                if (kind.tracksWords && totals.targetWords > 0) {
                                    append(ProjectPulse.count(totals.actualWords))
                                    append(" of ")
                                    append(ProjectPulse.count(totals.targetWords))
                                    append(" words · ")
                                } else if (kind.tracksWords && totals.actualWords > 0) {
                                    append(ProjectPulse.count(totals.actualWords))
                                    append(" words · ")
                                }
                                append("${totals.piecesComplete} of ${totals.pieces} ${kind.pieces}")
                                if (totals.cut > 0) append(" · ${totals.cut} cut")
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        ProgressBar(totals.progress)
                    }
                }
            }

            if (rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "Nothing outlined yet",
                        detail = "Start with the ${kind.parts} — the big shapes — and put the " +
                            "${kind.pieces} underneath them. Anything can be moved later."
                    )
                }
            }

            items(rows, key = { it.node.id }) { row ->
                OutlineRowCard(
                    row = row,
                    kind = kind,
                    onEdit = { editing = row.node },
                    onAddChild = { addingUnder = row.node.id },
                    onMove = { delta -> vm.move(row.node.id, delta) },
                    onIndent = { vm.indent(row.node.id) },
                    onOutdent = { vm.outdent(row.node.id) },
                    onStatus = { status -> vm.setStatus(row.node.id, status) },
                    onDelete = { vm.askDelete(row.node) }
                )
            }
        }
    }

    if (addingRoot || addingUnder != null) {
        val parent = addingUnder
        TitlePromptDialog(
            title = if (parent == null) "New ${kind.part}" else "New ${kind.piece}",
            onDismiss = {
                addingRoot = false
                addingUnder = null
            },
            onConfirm = { text ->
                vm.add(parent, text)
                addingRoot = false
                addingUnder = null
            }
        )
    }

    editing?.let { node ->
        EditNodeDialog(
            onOpenFiles = { onOpenFiles(AttachKind.OUTLINE, it) },
            node = node,
            kind = kind,
            onDismiss = { editing = null },
            onSave = {
                vm.update(it)
                editing = null
            }
        )
    }

    pending?.let { (node, size) ->
        AlertDialog(
            onDismissRequest = { vm.cancelDelete() },
            title = { Text("Delete “${node.title}”?") },
            text = {
                Text(
                    if (size > 1) {
                        "This also deletes the $size ${if (size == 2) "piece" else "pieces"} " +
                            "underneath it. Documents written for them are kept, but stop being linked."
                    } else {
                        "Documents written for it are kept, but stop being linked."
                    }
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirmDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { vm.cancelDelete() }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun OutlineRowCard(
    row: OutlineRow,
    kind: ProjectKind,
    onEdit: () -> Unit,
    onAddChild: () -> Unit,
    onMove: (Int) -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onStatus: (OutlineStatus) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val cut = row.node.status == OutlineStatus.CUT

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Indentation is the tree. Capped so a deeply nested scene never squeezes its own title
            // off the screen — depth beyond four is still shown by the number ("1.2.3.4.5").
            .padding(start = (row.depth.coerceAtMost(4) * 16).dp)
            .clickable(onClick = onEdit)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.number,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp)
                )
                Text(
                    row.node.title,
                    style = if (row.hasChildren) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (row.hasChildren) FontWeight.SemiBold else FontWeight.Normal,
                    textDecoration = if (cut) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f)
                )

                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Row actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Add ${kind.piece} under this") },
                            onClick = {
                                menuOpen = false
                                onAddChild()
                            }
                        )
                        OutlineStatus.entries.forEach { status ->
                            DropdownMenuItem(
                                text = { Text(status.label) },
                                onClick = {
                                    menuOpen = false
                                    onStatus(status)
                                }
                            )
                        }
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

            row.node.synopsis?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = onEdit,
                    label = { Text(row.node.status.label, style = MaterialTheme.typography.labelSmall) }
                )
                if (kind.tracksWords) {
                    val words = row.totals
                    Text(
                        if (words.targetWords > 0) {
                            "${ProjectPulse.count(words.actualWords)} / ${ProjectPulse.count(words.targetWords)}"
                        } else {
                            "${ProjectPulse.count(words.actualWords)} words"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (row.hasChildren) {
                    Text(
                        "${row.totals.piecesComplete}/${row.totals.pieces} ${kind.pieces}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = { onMove(-1) }) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(1) }) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                }
                IconButton(onClick = onOutdent) {
                    Icon(Icons.Filled.FormatIndentDecrease, contentDescription = "Outdent")
                }
                IconButton(onClick = onIndent) {
                    Icon(Icons.Filled.FormatIndentIncrease, contentDescription = "Indent")
                }
            }
        }
    }
}

@Composable
private fun TitlePromptDialog(
    title: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            SuiteTextField(label = "Title", value = text, onValueChange = { text = it })
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditNodeDialog(
    onOpenFiles: (String) -> Unit,
    node: OutlineNode,
    kind: ProjectKind,
    onDismiss: () -> Unit,
    onSave: (OutlineNode) -> Unit
) {
    var title by remember(node.id) { mutableStateOf(node.title) }
    var synopsis by remember(node.id) { mutableStateOf(node.synopsis.orEmpty()) }
    var target by remember(node.id) { mutableStateOf(if (node.targetWords > 0) node.targetWords.toString() else "") }
    var status by remember(node.id) { mutableStateOf(node.status) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.title.ifBlank { "Untitled" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SuiteTextField(label = "Title", value = title, onValueChange = { title = it })
                SuiteNoteField(
                    label = "What happens here",
                    value = synopsis,
                    onValueChange = { synopsis = it },
                    minLines = 1,
                    maxLines = 4
                )
                if (kind.tracksWords) {
                    SuiteNumberField(
                        // Words are counted, never fractional and never negative — so the field accepts digits and
                        // nothing else, and there is no wrong value left to complain about afterwards.
                        label = "Word target (blank for none)",
                        value = target,
                        onValueChange = { target = it }
                    )
                    Text(
                        // Said out loud because it is the question people ask of every writing tool.
                        "Words written come from the documents linked to this ${kind.piece}, so this " +
                            "is only the target.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("Status", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    OutlineStatus.entries.forEach { candidate ->
                        FilterChip(
                            selected = status == candidate,
                            onClick = { status = candidate },
                            label = { Text(candidate.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // Reference for this piece — the photograph of the street, the article it is based
                // on — filed on the scene rather than in the project's one flat pile.
                TextButton(onClick = { onOpenFiles(node.id) }) { Text("Files on this ${kind.piece}…") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    node.copy(
                        title = title,
                        synopsis = synopsis.ifBlank { null },
                        targetWords = target.toIntOrNull() ?: 0,
                        status = status
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
