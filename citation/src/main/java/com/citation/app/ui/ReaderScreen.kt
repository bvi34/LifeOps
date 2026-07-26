package com.citation.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The reader UI. It renders the **internal representation** — chapters of flowing text — and is
 * therefore *format-blind*: an EPUB, a Royal Road serial, and a reconstructed PDF flow would all
 * arrive here as the same `Book`. Two surfaces: a library (import + list) and the reader itself
 * (chapter text, typography controls, chapter paging, note capture).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: ReaderViewModel) {
    val openBook by vm.openBook.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val pdfSession by vm.pdfSession.collectAsStateWithLifecycle()
    val oreillySession by vm.oreillySession.collectAsStateWithLifecycle()

    var browsingRoyalRoad by remember { mutableStateOf(false) }
    var viewingNotes by remember { mutableStateOf(false) }
    var viewingStorage by remember { mutableStateOf(false) }

    // The PDF and O'Reilly tracks preempt the flowing reader and the library.
    if (pdfSession != null) {
        PdfReaderScreen(pdfSession!!, vm)
        return
    }
    if (oreillySession != null) {
        OreillyReaderScreen(oreillySession!!, vm)
        return
    }

    if (openBook == null) {
        when {
            browsingRoyalRoad -> RoyalRoadCatalogScreen(
                onOpenFiction = { fictionId ->
                    browsingRoyalRoad = false
                    vm.openRoyalRoad(fictionId)
                },
                onBack = { browsingRoyalRoad = false }
            )
            viewingNotes -> NotesScreen(vm, onBack = { viewingNotes = false })
            viewingStorage -> StorageScreen(vm, onBack = { viewingStorage = false })
            else -> LibraryView(
                vm, status,
                onBrowseRoyalRoad = { browsingRoyalRoad = true },
                onViewNotes = { viewingNotes = true },
                onViewStorage = { viewingStorage = true }
            )
        }
    } else {
        val ordinal by vm.chapterOrdinal.collectAsStateWithLifecycle()
        val book = openBook!!
        val chapter = book.chapterAt(ordinal)
        var fontSize by remember { mutableFloatStateOf(18f) }
        var noteQuote by remember { mutableStateOf("") }
        var noteBody by remember { mutableStateOf("") }
        var showNote by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(book.metadata.title, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { vm.closeBook() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                        }
                    },
                    actions = {
                        if (vm.isRoyalRoadOpen) {
                            IconButton(onClick = { vm.favoriteRoyalRoad() }) {
                                Icon(Icons.Default.Star, contentDescription = "Favourite (full backfill)")
                            }
                        }
                        IconButton(onClick = { showNote = !showNote }) {
                            Icon(Icons.Default.Add, contentDescription = "Add note")
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                // Typography basics: font size.
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("A", fontSize = 12.sp)
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        valueRange = 12f..28f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text("A", fontSize = 22.sp)
                }

                if (showNote) {
                    NoteComposer(
                        quote = noteQuote,
                        body = noteBody,
                        onQuote = { noteQuote = it },
                        onBody = { noteBody = it },
                        onSave = {
                            vm.captureNoteForQuote(noteQuote, noteBody)
                            noteQuote = ""; noteBody = ""; showNote = false
                        },
                        onSaveSynthesis = {
                            // Freestanding synthesis: your own text, optionally citing the quote.
                            vm.captureSynthesis(listOf(noteQuote), noteBody)
                            noteQuote = ""; noteBody = ""; showNote = false
                        },
                        onCancel = { showNote = false }
                    )
                }

                status?.let {
                    Text(
                        it,
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(12.dp).clickable { vm.clearStatus() },
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                // Flowing chapter text. SelectionContainer lets the reader pick a passage to quote.
                val scroll = rememberScrollState()
                SelectionContainer(Modifier.weight(1f)) {
                    Column(Modifier.verticalScroll(scroll).padding(20.dp)) {
                        Text(
                            text = chapter?.title ?: "",
                            fontSize = (fontSize + 6).sp,
                            fontFamily = FontFamily.Serif,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = chapter?.text ?: "(chapter unavailable)",
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.6f).sp,
                            fontFamily = FontFamily.Serif,
                            modifier = Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                // Chapter paging.
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(onClick = { vm.goToChapter(ordinal - 1) }, enabled = ordinal > 0) {
                        Text("Previous")
                    }
                    Text("Chapter ${ordinal + 1} / ${book.chapters.size}", Modifier.align(Alignment.CenterVertically))
                    OutlinedButton(
                        onClick = { vm.goToChapter(ordinal + 1) },
                        enabled = ordinal < book.chapters.lastIndex
                    ) { Text("Next") }
                }
            }
        }
    }
}

@Composable
private fun NoteComposer(
    quote: String,
    body: String,
    onQuote: (String) -> Unit,
    onBody: (String) -> Unit,
    onSave: () -> Unit,
    onSaveSynthesis: () -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        OutlinedTextField(
            value = quote,
            onValueChange = onQuote,
            label = { Text("Passage to cite (paste the exact quote)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = body,
            onValueChange = onBody,
            label = { Text("Your note") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            // Synthesis: your own artifact; the quote is an optional citation, so body is enough.
            TextButton(
                onClick = onSaveSynthesis,
                enabled = body.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Save as synthesis") }
            // Passage-anchored: hangs off exactly one highlight, so the quote is required.
            Button(
                onClick = onSave,
                enabled = quote.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Save passage note") }
        }
    }
}

@Composable
private fun AddOreillyDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var bookId by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an O'Reilly book") },
        text = {
            Column {
                Text(
                    "Read-in-place: nothing is downloaded. We keep only a link to your spot and your notes.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                OutlinedTextField(
                    value = bookId, onValueChange = { bookId = it },
                    label = { Text("O'Reilly book id / ISBN") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(bookId.trim(), title.trim()) }, enabled = bookId.isNotBlank() && title.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryView(
    vm: ReaderViewModel,
    status: String?,
    onBrowseRoyalRoad: () -> Unit,
    onViewNotes: () -> Unit,
    onViewStorage: () -> Unit
) {
    val books by vm.books.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showOreilly by remember { mutableStateOf(false) }
    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null) vm.importEpub(bytes)
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        val title = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".pdf") ?: "PDF"
        if (bytes != null) vm.importPdf(bytes, title)
    }

    if (showOreilly) {
        AddOreillyDialog(
            onAdd = { id, title -> vm.addOreillyBook(id, title); showOreilly = false },
            onDismiss = { showOreilly = false }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Citation") }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Button(onClick = { epubPicker.launch("application/epub+zip") }, modifier = Modifier.fillMaxWidth()) {
                Text("Import EPUB")
            }
            OutlinedButton(
                onClick = { pdfPicker.launch("application/pdf") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Import PDF") }
            OutlinedButton(
                onClick = { showOreilly = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Add O'Reilly book") }
            OutlinedButton(
                onClick = onBrowseRoyalRoad,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Browse Royal Road") }
            OutlinedButton(
                onClick = onViewNotes,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Notes") }
            OutlinedButton(
                onClick = onViewStorage,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Storage") }
            OutlinedButton(
                onClick = { vm.sync() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Sync with LifeOps") }
            status?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(top = 12.dp).clickable { vm.clearStatus() },
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Your library is empty. Import an EPUB to start reading.")
                }
            } else {
                Column(Modifier.padding(top = 16.dp)) {
                    books.forEach { b ->
                        Column(
                            Modifier.fillMaxWidth().clickable { vm.open(b.key) }.padding(vertical = 12.dp)
                        ) {
                            Text(b.title, fontSize = 18.sp, fontFamily = FontFamily.Serif)
                            b.author?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary) }
                            Text(
                                "${b.readingState} · ${b.acquisitionState}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}
