package com.project.app.ui.docs

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.operations.suite.ui.fields.SuiteNoteField
import com.operations.suite.ui.fields.SuiteTextField
import com.project.app.data.model.DocContent
import com.project.app.data.prefs.ProjectPrefs
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.BlockType
import com.project.app.logic.DocBlock
import com.project.app.logic.DocBlocks
import com.project.app.logic.DocHeading
import com.project.app.logic.MarkdownTables
import com.project.app.logic.ProjectPulse
import com.project.app.logic.RevisionReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One document, as blocks.
 *
 * Each block is its own text field, which is the whole reason to store blocks at all: a to-do can
 * be ticked without touching its text, a paragraph can be turned into a heading without retyping
 * it, and a line can be moved without a selection gesture. Typing saves as you go — there is no
 * save button, because a document editor that can lose the last paragraph to a back gesture is not
 * one anybody should trust their only copy to.
 *
 * **Reading mode** drops the fields and draws the same blocks as *Markdown*: emphasis reads as
 * emphasis, a quote gets its rule, code gets its ground, and a table is drawn as a table rather than
 * as the pipes it is spelled with. Nine tenths of the time a document is opened it is to be read,
 * and a page of outlined input boxes reads like a form.
 *
 * What is stored is still the text you typed, markers and all. Nothing here rewrites a document to
 * make it draw more prettily — the reading is computed on the way to the screen, which is what lets
 * the export be exactly what you wrote.
 *
 * The way writing gets **out** is Markdown, to the clipboard or the share sheet, matching the way it
 * comes in. That pairing is the point: a repository you cannot get writing out of is a hostage
 * situation, not a tool, and an export buried in a settings screen is one most people never find.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocEditorScreen(vm: DocEditorViewModel, onHistory: () -> Unit, onBack: () -> Unit) {
    val content by vm.content.collectAsStateWithLifecycle()
    val reading by vm.readingMode.collectAsStateWithLifecycle()
    val tableCards by vm.tableCards.collectAsStateWithLifecycle()
    val revisionCount by vm.revisionCount.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var renaming by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var addingAfter by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }

    val doc = content?.doc
    val blocks = content?.blocks.orEmpty()
    val headings = remember(blocks) { DocBlocks.headings(blocks) }
    // Offered only where there is something to repair, so the menu does not carry a permanent
    // invitation to fix a problem this document does not have.
    val hasFlattenedTables = remember(blocks) { DocBlocks.hasFlattenedTables(blocks) }
    val listState = rememberLazyListState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(doc?.title ?: "Document") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Only offered by a document that has any: a contents button on a page with no
                    // headings opens an empty sheet, which is a worse answer than no button.
                    if (headings.isNotEmpty()) {
                        IconButton(onClick = { showContents = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents")
                        }
                    }
                    IconButton(onClick = { vm.toggleReadingMode() }) {
                        Icon(
                            if (reading) Icons.Filled.Edit else Icons.Filled.MenuBook,
                            contentDescription = if (reading) "Edit" else "Read"
                        )
                    }
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
                                text = { Text("Copy as Markdown") },
                                onClick = {
                                    menuOpen = false
                                    vm.exportMarkdown { markdown ->
                                        clipboard.setText(AnnotatedString(markdown))
                                        scope.launch {
                                            snackbars.showSnackbar(
                                                "Copied ${ProjectPulse.count(DocBlocks.wordCount(blocks))} words"
                                            )
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share as text…") },
                                onClick = {
                                    menuOpen = false
                                    vm.exportMarkdown { markdown ->
                                        shareText(context, doc?.title ?: "Document", markdown)
                                    }
                                }
                            )
                            if (hasFlattenedTables) {
                                DropdownMenuItem(
                                    text = { Text("Rebuild tables") },
                                    onClick = {
                                        menuOpen = false
                                        vm.repairTables { count ->
                                            scope.launch {
                                                snackbars.showSnackbar(
                                                    if (count == 1) "Rebuilt 1 table"
                                                    else "Rebuilt $count tables"
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Replace with pasted Markdown…") },
                                onClick = {
                                    menuOpen = false
                                    importing = true
                                }
                            )
                            // Beneath the one destructive item in this menu, which is where the way
                            // back belongs: the moment somebody reads "Replace" is the moment to
                            // see that replacing is not final.
                            DropdownMenuItem(
                                text = { Text("Keep this version") },
                                onClick = {
                                    menuOpen = false
                                    vm.saveRevision { kept ->
                                        scope.launch {
                                            snackbars.showSnackbar(
                                                if (kept) "Version kept"
                                                // The two cases that file nothing, said rather than
                                                // silently doing nothing and looking broken.
                                                else "Nothing to keep — this is already the latest version"
                                            )
                                        }
                                    }
                                }
                            )
                            if (revisionCount > 0) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Version history ($revisionCount)"
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onHistory()
                                    }
                                )
                            }
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

            // A numbered list numbers itself. The ordinal is a fact about where a block sits among
            // its neighbours, so it is worked out here rather than stored — deleting the second item
            // of a list must not leave the third one calling itself "3".
            val ordinals = remember(blocks) { DocBlocks.ordinals(blocks) }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (reading) 8.dp else 2.dp)
            ) {
                itemsIndexed(blocks, key = { _, block -> block.id }) { index, block ->
                    if (reading) {
                        ReadOnlyBlock(
                            block = block,
                            ordinal = ordinals[index],
                            tableCards = tableCards,
                            onToggleTableView = { vm.toggleTableCards() },
                            onCheck = { checked -> vm.updateBlock(block.copy(checked = checked)) }
                        )
                    } else {
                        BlockRow(
                            block = block,
                            ordinal = ordinals[index],
                            tableCards = tableCards,
                            onToggleTableView = { vm.toggleTableCards() },
                            onChange = { vm.updateBlock(it) },
                            onRetype = { type -> vm.retypeBlock(block, type) },
                            onMove = { delta -> vm.moveBlock(block.id, delta) },
                            onDelete = { vm.deleteBlock(block.id) },
                            onAddAfter = {
                                addingAfter = block.id
                                showAddMenu = true
                            }
                        )
                    }
                }

                if (!reading) {
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

    if (showContents) {
        ContentsSheet(
            headings = headings,
            onDismiss = { showContents = false },
            onGo = { heading ->
                showContents = false
                val index = blocks.indexOfFirst { it.id == heading.blockId }
                if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
            }
        )
    }

    if (renaming && doc != null) {
        var title by remember(doc.id) { mutableStateOf(doc.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename document") },
            text = {
                SuiteTextField(
                    // Labelled, where it was not before: an outlined field with no floating label is a box
                    // whose meaning is carried entirely by the dialog's own title, and reads as unfinished.
                    label = "Title",
                    value = title,
                    onValueChange = { title = it }
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
                        "This replaces everything in the document. Headings, lists, to-dos, quotes, " +
                            "code fences and tables come through as blocks — including a table that " +
                            "lost its line breaks on the way here.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    SuiteNoteField(
                        label = "Paste here",
                        value = markdown,
                        onValueChange = { markdown = it }
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

