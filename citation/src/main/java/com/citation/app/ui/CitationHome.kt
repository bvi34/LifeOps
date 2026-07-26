package com.citation.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.app.data.CitationRepository
import com.citation.core.sync.ReadingState

/**
 * The Citation home shell: a five-tab bottom bar consolidating the app the way the main LifeOps app
 * does. Immersive readers (flowing text, PDF, O'Reilly) preempt this shell from [ReaderScreen]; when
 * none is open the reader lands here.
 *
 *  - **New** — import / add sources (EPUB, PDF, O'Reilly, Royal Road).
 *  - **Personal** — your reading stats and every captured note.
 *  - **Read** — resume the last thing you opened, right where you left off.
 *  - **Library** — everything you've added.
 *  - **Settings** — storage, sync with LifeOps, and the rest.
 */
private enum class HomeTab(val label: String, val icon: ImageVector) {
    NEW("New", Icons.Filled.AddCircle),
    PERSONAL("Personal", Icons.Filled.Person),
    READ("Read", Icons.Filled.MenuBook),
    LIBRARY("Library", Icons.Filled.LocalLibrary),
    SETTINGS("Settings", Icons.Filled.Settings)
}

@Composable
fun CitationHome(vm: ReaderViewModel) {
    var browsingRoyalRoad by remember { mutableStateOf(false) }
    // Land on Read — the app's reason for being is resuming what you were reading.
    var tabOrdinal by rememberSaveable { mutableStateOf(HomeTab.READ.ordinal) }
    val tab = HomeTab.entries[tabOrdinal]

    // The Royal Road catalog (a full-screen WebView skim) preempts the tab shell while browsing.
    if (browsingRoyalRoad) {
        RoyalRoadCatalogScreen(
            onOpenFiction = { fictionId ->
                browsingRoyalRoad = false
                vm.openRoyalRoad(fictionId)
            },
            onBack = { browsingRoyalRoad = false }
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { t ->
                    NavigationBarItem(
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        selected = t == tab,
                        onClick = { tabOrdinal = t.ordinal }
                    )
                }
            }
        }
    ) { innerPadding ->
        // The tabs carry their own top app bars; only the bottom-bar inset needs reserving here.
        Box(Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding())) {
            when (tab) {
                HomeTab.NEW -> NewTab(vm, onBrowseRoyalRoad = { browsingRoyalRoad = true })
                HomeTab.PERSONAL -> PersonalTab(vm)
                HomeTab.READ -> ReadTab(vm, onGoToLibrary = { tabOrdinal = HomeTab.LIBRARY.ordinal })
                HomeTab.LIBRARY -> LibraryTab(vm)
                HomeTab.SETTINGS -> SettingsTab(vm)
            }
        }
    }
}

// --- New ---------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTab(vm: ReaderViewModel, onBrowseRoyalRoad: () -> Unit) {
    val status by vm.status.collectAsStateWithLifecycle()
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

    Scaffold(topBar = { TopAppBar(title = { Text("New") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text(
                "Import or add something to read.",
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
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
            status?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(top = 16.dp).clickable { vm.clearStatus() },
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// --- Personal (reading stats + notes) ----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalTab(vm: ReaderViewModel) {
    val books by vm.books.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()

    val reading = books.count { it.readingState == ReadingState.READING.name }
    val finished = books.count { it.readingState == ReadingState.DONE.name }
    val toRead = books.count { it.readingState == ReadingState.TO_READ.name }

    Scaffold(topBar = { TopAppBar(title = { Text("Personal") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Reading stats", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Stat("In library", books.size.toString())
                        Stat("Reading", reading.toString())
                        Stat("Finished", finished.toString())
                        Stat("To read", toRead.toString())
                        Stat("Notes", notes.size.toString())
                    }
                }
            }
            Divider()
            Text(
                "Notes",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
            )
            NotesList(vm, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

// --- Read (resume last opened) -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadTab(vm: ReaderViewModel, onGoToLibrary: () -> Unit) {
    val last by vm.lastOpened.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Read") }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            val book = last
            if (book == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Nothing open yet.",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "Open something from your Library and it'll wait for you here.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    OutlinedButton(onClick = onGoToLibrary, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Go to Library")
                    }
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "Pick up where you left off",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            book.title,
                            fontSize = 22.sp,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        book.author?.let {
                            Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                        Text(
                            resumeHint(book),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { vm.open(book.key) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Continue reading")
                        }
                    }
                }
            }
        }
    }
}

/** A small human hint about where "continue" will land, from what the summary knows. */
private fun resumeHint(book: CitationRepository.BookSummary): String = when {
    book.lastChapterOrdinal > 0 -> "Resumes at chapter ${book.lastChapterOrdinal + 1}"
    book.readingState == ReadingState.DONE.name -> "Finished — reopen to reread"
    else -> "Opens where you left off"
}

// --- Library -----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTab(vm: ReaderViewModel) {
    val books by vm.books.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Library") }) }) { padding ->
        if (books.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Your library is empty. Add something from the New tab.",
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                books.forEach { b ->
                    Column(
                        Modifier.fillMaxWidth().clickable { vm.open(b.key) }.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(b.title, fontSize = 18.sp, fontFamily = FontFamily.Serif, color = MaterialTheme.colorScheme.onBackground)
                        b.author?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary) }
                        Text(
                            "${b.readingState} · ${b.acquisitionState}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Divider()
                }
            }
        }
    }
}

// --- Settings (sync + storage) -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(vm: ReaderViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Sync", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                OutlinedButton(
                    onClick = { vm.sync() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Sync with LifeOps") }
                status?.let {
                    Text(
                        it,
                        Modifier.fillMaxWidth().padding(top = 8.dp).clickable { vm.clearStatus() },
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Divider()
            Text(
                "Storage",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
            )
            StorageReportBody(vm, Modifier.weight(1f))
        }
    }
}

// --- Shared dialog -----------------------------------------------------------------------------

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
