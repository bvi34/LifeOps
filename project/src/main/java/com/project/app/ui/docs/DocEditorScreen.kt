package com.project.app.ui.docs

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.project.app.data.model.DocContent
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.BlockType
import com.project.app.logic.DocBlock
import com.project.app.logic.DocBlocks
import com.project.app.logic.ProjectPulse
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DocEditorViewModel(
    private val repo: ProjectRepository,
    private val docId: String
) : ViewModel() {

    val content: StateFlow<DocContent?> =
        repo.observeDocContent(docId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun updateBlock(block: DocBlock) = viewModelScope.launch { repo.updateBlock(docId, block) }

    fun addBlock(type: BlockType, after: String?) =
        viewModelScope.launch { repo.addBlock(docId, type, after) }

    fun deleteBlock(blockId: String) = viewModelScope.launch { repo.deleteBlock(docId, blockId) }

    fun moveBlock(blockId: String, delta: Int) =
        viewModelScope.launch { repo.moveBlock(docId, blockId, delta) }

    fun rename(title: String) = viewModelScope.launch {
        val doc = content.value?.doc ?: return@launch
        repo.updateDoc(doc.copy(title = title))
    }

    fun replaceFromMarkdown(markdown: String) =
        viewModelScope.launch { repo.replaceDocFromMarkdown(docId, markdown) }

    class Factory(
        private val repo: ProjectRepository,
        private val docId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DocEditorViewModel(repo, docId) as T
    }
}

/**
 * One document, as blocks.
 *
 * Each block is its own text field, which is the whole reason to store blocks at all: a to-do can
 * be ticked without touching its text, a paragraph can be turned into a heading without retyping
 * it, and a line can be moved without a selection gesture. Typing saves as you go — there is no
 * save button, because a document editor that can lose the last paragraph to a back gesture is not
 * one anybody should trust their only copy to.
 *
 * The paste-in and copy-out are Markdown (see `logic/DocBlocks`), and both are offered here rather
 * than hidden in a settings screen. A repository you cannot get writing *out* of is a hostage
 * situation, not a tool.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocEditorScreen(vm: DocEditorViewModel, onBack: () -> Unit) {
    val content by vm.content.collectAsStateWithLifecycle()

    var renaming by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var addingAfter by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }

    val doc = content?.doc
    val blocks = content?.blocks.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc?.title ?: "Document") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Document menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename…") },
                                onClick = {
                                    menuOpen = false
                                    renaming = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Replace with pasted Markdown…") },
                                onClick = {
                                    menuOpen = false
                                    importing = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "${ProjectPulse.count(DocBlocks.wordCount(blocks))} words",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val (done, total) = DocBlocks.todoProgress(blocks)
                if (total > 0) {
                    Text(
                        "$done of $total done",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(blocks, key = { it.id }) { block ->
                    BlockRow(
                        block = block,
                        onChange = { vm.updateBlock(it) },
                        onMove = { delta -> vm.moveBlock(block.id, delta) },
                        onDelete = { vm.deleteBlock(block.id) },
                        onAddAfter = {
                            addingAfter = block.id
                            showAddMenu = true
                        }
                    )
                }

                item(key = "add-at-end") {
                    TextButton(onClick = {
                        addingAfter = blocks.lastOrNull()?.id
                        showAddMenu = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("  Add a block")
                    }
                }
            }
        }
    }

    if (showAddMenu) {
        BlockTypeDialog(
            onDismiss = { showAddMenu = false },
            onPick = { type ->
                vm.addBlock(type, addingAfter)
                showAddMenu = false
            }
        )
    }

    if (renaming && doc != null) {
        var title by remember(doc.id) { mutableStateOf(doc.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename document") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    vm.rename(title)
                    renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
        )
    }

    if (importing) {
        var markdown by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Replace with Markdown") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This replaces everything in the document. Headings, lists, to-dos, quotes " +
                            "and code fences come through as blocks.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = markdown,
                        onValueChange = { markdown = it },
                        label = { Text("Paste here") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = markdown.isNotBlank(), onClick = {
                    vm.replaceFromMarkdown(markdown)
                    importing = false
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } }
        )
    }
}

/**
 * One block.
 *
 * A divider draws itself; everything else is a text field styled for what it is, so a heading
 * *looks* like a heading while you are typing it. The controls sit on the row rather than in a
 * floating toolbar because there is no selection model here to attach one to.
 */
@Composable
private fun BlockRow(
    block: DocBlock,
    onChange: (DocBlock) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onAddAfter: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    if (block.type == BlockType.DIVIDER) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Remove divider")
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (block.type == BlockType.TODO) {
            Checkbox(
                checked = block.checked,
                onCheckedChange = { onChange(block.copy(checked = it)) }
            )
        }

        // The list marker is drawn beside the field rather than inside it: it is not part of the
        // text, and a bullet you can backspace over is a bullet that ends up in the exported
        // Markdown twice.
        markerFor(block.type)?.let { marker ->
            Text(
                marker,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        TextField(
            value = block.text,
            onValueChange = { onChange(block.copy(text = it)) },
            modifier = Modifier.weight(1f),
            textStyle = when (block.type) {
                BlockType.HEADING1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                BlockType.HEADING2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                BlockType.HEADING3 -> MaterialTheme.typography.titleMedium
                BlockType.QUOTE -> MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
                BlockType.CODE -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                BlockType.TODO -> MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (block.checked) TextDecoration.LineThrough else null
                )
                else -> MaterialTheme.typography.bodyLarge
            },
            placeholder = { Text(placeholderFor(block.type), style = MaterialTheme.typography.bodyMedium) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            )
        )

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Block actions")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Add block below") },
                    onClick = {
                        menuOpen = false
                        onAddAfter()
                    }
                )
                BlockType.entries.filter { it != block.type }.forEach { type ->
                    DropdownMenuItem(
                        text = { Text("Turn into ${type.label.lowercase()}") },
                        onClick = {
                            menuOpen = false
                            onChange(block.copy(type = type))
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Move up") },
                    leadingIcon = { Icon(Icons.Filled.ArrowUpward, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onMove(-1)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Move down") },
                    leadingIcon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onMove(1)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete block") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

/** The character that stands in front of a list item or a quote, or null when nothing does. */
private fun markerFor(type: BlockType): String? = when (type) {
    BlockType.BULLET -> "•"
    BlockType.NUMBERED -> "1."
    BlockType.QUOTE -> "|"
    else -> null
}

private fun placeholderFor(type: BlockType): String = when (type) {
    BlockType.HEADING1 -> "Heading"
    BlockType.HEADING2 -> "Subheading"
    BlockType.HEADING3 -> "Small heading"
    BlockType.BULLET -> "List item"
    BlockType.NUMBERED -> "List item"
    BlockType.TODO -> "To do"
    BlockType.QUOTE -> "Quotation"
    BlockType.CODE -> "Code"
    else -> "Write something…"
}

@Composable
private fun BlockTypeDialog(onDismiss: () -> Unit, onPick: (BlockType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a block") },
        text = {
            LazyColumn {
                items(BlockType.entries.toList(), key = { it.key }) { type ->
                    TextButton(
                        onClick = { onPick(type) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(type.label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
