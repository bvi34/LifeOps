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

class DocEditorViewModel(
    private val repo: ProjectRepository,
    private val prefs: ProjectPrefs,
    private val docId: String
) : ViewModel() {

    val content: StateFlow<DocContent?> =
        repo.docs.observeDocContent(docId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Reading or editing. Remembered across documents and launches, because it is a mode you are
     * *in* — somebody re-reading a draft wants every document they open to be readable, not to
     * reach for the toggle forty times.
     */
    private val _readingMode = MutableStateFlow(prefs.docReadingMode)
    val readingMode: StateFlow<Boolean> = _readingMode.asStateFlow()

    fun toggleReadingMode() {
        val next = !_readingMode.value
        _readingMode.value = next
        prefs.docReadingMode = next
    }

    /**
     * Whether tables read as cards rather than as a grid.
     *
     * Remembered like reading mode, and for the same reason: on a small screen a wide table is only
     * readable one way, and which way that is depends on the phone and the person, not on the
     * document. Asking again on every table would be asking the same question forty times.
     */
    private val _tableCards = MutableStateFlow(prefs.docTableCards)
    val tableCards: StateFlow<Boolean> = _tableCards.asStateFlow()

    fun toggleTableCards() {
        val next = !_tableCards.value
        _tableCards.value = next
        prefs.docTableCards = next
    }

    /**
     * How many versions this document has, so the menu can offer the history only when there is
     * one — an empty history screen is a worse answer than no way to reach it.
     */
    val revisionCount: StateFlow<Int> =
        repo.versions.observeRevisionCount(docId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Keep the document as it stands, by hand.
     *
     * The automatic versions cover the edits the app knows are destructive. This covers the ones it
     * cannot know about — the rewrite you are about to do yourself, a paragraph at a time.
     */
    fun saveRevision(onDone: (Boolean) -> Unit) = viewModelScope.launch {
        onDone(repo.versions.saveRevision(docId, RevisionReason.MANUAL) != null)
    }

    fun updateBlock(block: DocBlock) = viewModelScope.launch { repo.docs.updateBlock(docId, block) }

    /** Change a block's type, converting its text where the two shapes disagree. */
    fun retypeBlock(block: DocBlock, type: BlockType) =
        viewModelScope.launch { repo.docs.updateBlock(docId, DocBlocks.retype(block, type)) }

    fun addBlock(type: BlockType, after: String?) =
        viewModelScope.launch { repo.docs.addBlock(docId, type, after) }

    fun deleteBlock(blockId: String) = viewModelScope.launch { repo.docs.deleteBlock(docId, blockId) }

    fun moveBlock(blockId: String, delta: Int) =
        viewModelScope.launch { repo.docs.moveBlock(docId, blockId, delta) }

    fun rename(title: String) = viewModelScope.launch {
        val doc = content.value?.doc ?: return@launch
        repo.docs.updateDoc(doc.copy(title = title))
    }

    /** Rebuild the tables in this document that arrived as flattened paragraphs. */
    fun repairTables(onDone: (Int) -> Unit) =
        viewModelScope.launch { onDone(repo.docs.repairTables(docId)) }

    fun replaceFromMarkdown(markdown: String) =
        viewModelScope.launch { repo.docs.replaceDocFromMarkdown(docId, markdown) }

    /** The document as Markdown, handed to whoever asked — the clipboard or the share sheet. */
    fun exportMarkdown(onReady: (String) -> Unit) =
        viewModelScope.launch { onReady(repo.docs.docAsMarkdown(docId)) }

    class Factory(
        private val repo: ProjectRepository,
        private val prefs: ProjectPrefs,
        private val docId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DocEditorViewModel(repo, prefs, docId) as T
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

/**
 * Hand [markdown] to whatever the device can send text with.
 *
 * `ACTION_SEND` with plain text rather than a file: it needs no permission and no FileProvider, and
 * every note-taking app, mail client and messenger on the device accepts it. Writing a temporary
 * file to share would be a bigger promise than "get this text out of here".
 */
private fun shareText(context: android.content.Context, title: String, markdown: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, markdown)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share document")) }
}

/**
 * A block as it reads, not as it is edited.
 *
 * Everything a reader can act on stays live here — a to-do can be ticked, a link opens, a table can
 * be turned round — because reading mode is where documents are actually used, and a checklist you
 * cannot tick while reading it is a picture of a checklist.
 */
@Composable
private fun ReadOnlyBlock(
    block: DocBlock,
    ordinal: Int,
    tableCards: Boolean,
    onToggleTableView: () -> Unit,
    onCheck: (Boolean) -> Unit
) {
    when (block.type) {
        BlockType.DIVIDER -> HorizontalDivider(Modifier.padding(vertical = 8.dp))

        BlockType.CODE -> CodeSurface(block.text, Modifier.padding(vertical = 2.dp))

        BlockType.QUOTE -> QuoteSurface(block.text, Modifier.padding(vertical = 2.dp))

        BlockType.TABLE -> TableBlock(
            source = block.text,
            cards = tableCards,
            onToggleView = onToggleTableView
        )

        BlockType.TODO -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = block.checked, onCheckedChange = onCheck)
            MarkdownText(
                text = block.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (block.checked) TextDecoration.LineThrough else null
                ),
                color = if (block.checked) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        BlockType.BULLET, BlockType.NUMBERED -> Row(verticalAlignment = Alignment.Top) {
            Text(
                text = if (block.type == BlockType.NUMBERED) "$ordinal." else "•",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp)
            )
            MarkdownText(
                text = block.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
        }

        else -> MarkdownText(
            text = block.text,
            style = when (block.type) {
                BlockType.HEADING1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                BlockType.HEADING2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                BlockType.HEADING3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.bodyLarge
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (block.type.isHeading) 8.dp else 0.dp)
        )
    }
}

/**
 * A table, with the one control it needs: which way round to read it.
 *
 * The toggle sits on the table rather than in the document menu because it is a question you ask
 * *of a table* — and because a wide table's own top-right corner is where you are already looking
 * when it does not fit.
 */
@Composable
private fun TableBlock(
    source: String,
    cards: Boolean,
    onToggleView: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleView) {
                Icon(
                    if (cards) Icons.Filled.GridOn else Icons.Filled.ViewAgenda,
                    contentDescription = if (cards) "Show as a grid" else "Show a row at a time"
                )
            }
        }
        MarkdownTableView(source = source, cards = cards)
    }
}

/**
 * One block, editable.
 *
 * A divider draws itself; everything else is a text field styled for what it is, so a heading
 * *looks* like a heading while you are typing it. The controls sit on the row rather than in a
 * floating toolbar because there is no selection model here to attach one to.
 */
@Composable
private fun BlockRow(
    block: DocBlock,
    ordinal: Int,
    tableCards: Boolean,
    onToggleTableView: () -> Unit,
    onChange: (DocBlock) -> Unit,
    onRetype: (BlockType) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onAddAfter: () -> Unit
) {
    if (block.type == BlockType.TABLE) {
        TableBlockRow(
            block = block,
            cards = tableCards,
            onToggleView = onToggleTableView,
            onChange = onChange,
            menu = { BlockMenu(block, onRetype, onMove, onDelete, onAddAfter) }
        )
        return
    }

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
        markerFor(block.type, ordinal)?.let { marker ->
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
            singleLine = false,
            // A code block opens with room to be one: a fence that starts as a one-line field looks
            // like a sentence you are meant to finish.
            minLines = if (block.type.isMultiline) 3 else 1,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            )
        )

        BlockMenu(block, onRetype, onMove, onDelete, onAddAfter)
    }
}

/**
 * A table while the document is being edited.
 *
 * The table stays *drawn* — you edit the source underneath it, and see the grid redraw as you type.
 * A pipe table's source is unreadable on a phone-width line, so an editor that showed only the
 * source would be asking people to count columns by eye; and one that showed only the grid would
 * have no way to fix a cell at all.
 */
@Composable
private fun TableBlockRow(
    block: DocBlock,
    cards: Boolean,
    onToggleView: () -> Unit,
    onChange: (DocBlock) -> Unit,
    menu: @Composable () -> Unit
) {
    var showSource by remember(block.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Table",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            TextButton(onClick = { showSource = !showSource }) {
                Text(if (showSource) "Done" else "Edit cells")
            }
            IconButton(onClick = onToggleView) {
                Icon(
                    if (cards) Icons.Filled.GridOn else Icons.Filled.ViewAgenda,
                    contentDescription = if (cards) "Show as a grid" else "Show a row at a time"
                )
            }
            menu()
        }

        MarkdownTableView(source = block.text, cards = cards)

        if (showSource) {
            TextField(
                value = block.text,
                onValueChange = { onChange(block.copy(text = it)) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(placeholderFor(BlockType.TABLE), style = MaterialTheme.typography.bodySmall) },
                singleLine = false,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent
                )
            )
            TextButton(onClick = {
                MarkdownTables.coerce(block.text)?.let { onChange(block.copy(text = it.render())) }
            }) { Text("Tidy up columns") }
        }
    }
}

/** The actions any block has: what to put after it, what to turn it into, where to move it. */
@Composable
private fun BlockMenu(
    block: DocBlock,
    onRetype: (BlockType) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onAddAfter: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
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
                        onRetype(type)
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

/**
 * The mark that stands in front of a list item or a quote, or null when nothing does.
 *
 * A numbered item shows the number it will export as, not a permanent "1." — seeing the list count
 * up is half of what tells you it is a list.
 */
private fun markerFor(type: BlockType, ordinal: Int): String? = when (type) {
    BlockType.BULLET -> "•"
    BlockType.NUMBERED -> "$ordinal."
    BlockType.QUOTE -> "▍"
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
    BlockType.TABLE -> "| Column | Column |\n| --- | --- |\n| Cell | Cell |"
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

/**
 * The document's own contents, and a way to land on one.
 *
 * A long document is a scroll bar and a hope without one. Levels are shown as indentation rather
 * than as numbers, because the shape of a draft — three scenes under this chapter, none under that
 * one — is the thing you are looking at the list to see.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(
    headings: List<DocHeading>,
    onDismiss: () -> Unit,
    onGo: (DocHeading) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            item(key = "contents-title") {
                Text(
                    "Contents",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(headings, key = { it.blockId }) { heading ->
                Text(
                    text = heading.text.ifBlank { "Untitled section" },
                    style = when (heading.level) {
                        1 -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                        2 -> MaterialTheme.typography.bodyMedium
                        else -> MaterialTheme.typography.bodySmall
                    },
                    color = if (heading.level == 1) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onGo(heading) }
                        .padding(start = ((heading.level - 1) * 16).dp, top = 10.dp, bottom = 10.dp)
                )
            }
        }
    }
}
