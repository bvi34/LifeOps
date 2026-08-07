package com.citation.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.app.data.CitationRepository
import com.citation.core.capture.CaptureClusterer
import com.citation.core.model.SourceType
import com.citation.core.sync.ReadingState

/**
 * The Citation home shell: a five-tab bottom bar consolidating the app the way the main LifeOps app
 * does. Immersive readers (flowing text, PDF, O'Reilly) preempt this shell from [ReaderScreen]; when
 * none is open the reader lands here.
 *
 *  - **New** — import / add sources (EPUB, PDF, O'Reilly, Royal Road, Archive of Our Own).
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
    var browsingAo3 by remember { mutableStateOf(false) }
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

    // The Archive of Our Own catalog (a full-screen WebView skim) preempts the shell while browsing —
    // the AO3 twin of the Royal Road catalog.
    if (browsingAo3) {
        Ao3CatalogScreen(
            onOpenWork = { workId ->
                browsingAo3 = false
                vm.openAo3(workId)
            },
            onBack = { browsingAo3 = false }
        )
        return
    }

    // The O'Reilly catalog (a proxied, library-card WebView skim) likewise preempts the shell. It's
    // VM-owned rather than local state because it carries the library credentials for auto-reauth.
    val oreillyCatalog by vm.oreillyCatalog.collectAsStateWithLifecycle()
    oreillyCatalog?.let { cat ->
        OreillyCatalogScreen(
            catalog = cat,
            onOpenBook = { bookId, title -> vm.openOreillyFromCatalog(bookId, title) },
            onBack = { vm.closeOreillyCatalog() }
        )
        return
    }

    // Your Kindle library (a WebView on read.amazon.com) preempts the shell while browsing — the
    // read-in-place counterpart to the O'Reilly catalog, but on Amazon's own sign-in/cookies.
    val kindleLibrary by vm.kindleLibrary.collectAsStateWithLifecycle()
    kindleLibrary?.let { lib ->
        KindleLibraryScreen(
            library = lib,
            onOpenBook = { asin, title -> vm.openKindleFromLibrary(asin, title) },
            onBack = { vm.closeKindleLibrary() }
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
                HomeTab.NEW -> NewTab(
                    vm,
                    onBrowseRoyalRoad = { browsingRoyalRoad = true },
                    onBrowseAo3 = { browsingAo3 = true },
                    onBrowseOreilly = { vm.browseOreilly() },
                    onBrowseKindle = { vm.browseKindle() }
                )
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
private fun NewTab(
    vm: ReaderViewModel,
    onBrowseRoyalRoad: () -> Unit,
    onBrowseAo3: () -> Unit,
    onBrowseOreilly: () -> Unit,
    onBrowseKindle: () -> Unit
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showOreilly by remember { mutableStateOf(false) }
    var showKindle by remember { mutableStateOf(false) }

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
    if (showKindle) {
        AddKindleDialog(
            onAdd = { asin, title -> vm.addKindleBook(asin, title); showKindle = false },
            onDismiss = { showKindle = false }
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
                onClick = onBrowseOreilly,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Browse O'Reilly") }
            TextButton(
                onClick = { showOreilly = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("…or add an O'Reilly book by id") }
            OutlinedButton(
                onClick = onBrowseRoyalRoad,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Browse Royal Road") }
            OutlinedButton(
                onClick = onBrowseAo3,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Browse Archive of Our Own") }
            OutlinedButton(
                onClick = onBrowseKindle,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Browse Kindle library") }
            TextButton(
                onClick = { showKindle = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("…or add a Kindle book by ASIN") }
            Text(
                "Kindle: read in place on read.amazon.com. Browse your own library and tap a book — " +
                    "Citation learns its title and ASIN for you. Nothing is downloaded. Copying the passage " +
                    "is blocked there, so a note cites your location (\"Location 156 of 3866\") instead of " +
                    "the words — the book is the source and the note is yours.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 4.dp)
            )
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
    val triage by vm.triage.collectAsStateWithLifecycle()

    val reading = books.count { it.readingState == ReadingState.READING.name }
    val finished = books.count { it.readingState == ReadingState.DONE.name }
    val toRead = books.count { it.readingState == ReadingState.TO_READ.name }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personal") },
                actions = {
                    if (notes.isNotEmpty()) {
                        IconButton(onClick = { vm.exportVisibleNotes(context) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Export notes")
                        }
                    }
                }
            )
        }
    ) { padding ->
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
            if (triage.isNotEmpty()) {
                TriageBanner(triage)
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

/**
 * The thin-context triage prompt: captures whose only identifier is an app name or a timestamp, so
 * you can tag them while you still remember the context. Read-only nudge — nothing is lost if ignored.
 */
@Composable
private fun TriageBanner(clusters: List<CaptureClusterer.ProvisionalSource>) {
    val count = clusters.sumOf { it.memberKeys.size }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "$count capture${if (count == 1) "" else "s"} need context",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Saved with only an app name or a timestamp. Tag them while you still remember.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 4.dp)
            )
            clusters.take(4).forEach { cluster ->
                Text(
                    "• ${cluster.displayTitle}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun LibraryTab(vm: ReaderViewModel) {
    val books by vm.books.collectAsStateWithLifecycle()
    // The book the user long-pressed and is being asked to confirm removing.
    var pendingRemoval by remember { mutableStateOf<CitationRepository.BookSummary?>(null) }

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
                        Modifier.fillMaxWidth()
                            // Tap to open, long-press to remove/uncache — no hidden gesture, the empty
                            // state and this list are the only library surfaces.
                            .combinedClickable(
                                onClick = { vm.open(b.key) },
                                onLongClick = { pendingRemoval = b }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp)
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

    pendingRemoval?.let { book ->
        RemoveBookDialog(
            book = book,
            onConfirm = { vm.deleteBook(book); pendingRemoval = null },
            onDismiss = { pendingRemoval = null }
        )
    }
}

/**
 * Confirm removing a book from the library. For a Royal Road serial this is also the "uncache /
 * unfavourite" the user reaches for — the copy spells that out so it's clear what's reclaimed.
 */
@Composable
private fun RemoveBookDialog(
    book: CitationRepository.BookSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isRoyalRoad = book.sourceType == SourceType.ROYAL_ROAD.name
    val isAo3 = book.sourceType == SourceType.AO3.name
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove “${book.title}”?") },
        text = {
            Text(
                when {
                    isRoyalRoad ->
                        "This un-favourites the serial and clears its cached chapters. Your notes are kept, " +
                            "and you can add it again from Browse Royal Road."
                    isAo3 ->
                        "This un-favourites the work and clears its cached chapters. Your notes are kept, " +
                            "and you can add it again from Browse Archive of Our Own."
                    else -> "This removes it from your library. Your notes are kept."
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.secondary
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Settings (sync + storage) -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(vm: ReaderViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

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
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Quick capture", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    "A floating bubble to jot a note over any app. Captures what you type, never what's " +
                        "on screen. Selections and shares don't need this — only the catch-all bubble does.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                OutlinedButton(
                    onClick = {
                        if (com.citation.app.QuickCaptureBubbleService.canDraw(context)) {
                            com.citation.app.QuickCaptureBubbleService.ensureRunning(context)
                        } else {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Enable quick-capture bubble") }
            }
            Divider()
            OreillyAccessSection(vm)
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

// --- O'Reilly library access -------------------------------------------------------------------

/**
 * Enter the library proxy + card/PIN that let O'Reilly open through your library card. The card/PIN
 * are stored encrypted on-device (Android Keystore) and never synced; the reader uses them only to
 * re-sign-in to the library's own page when the proxy session lapses.
 */
@Composable
private fun OreillyAccessSection(vm: ReaderViewModel) {
    val config by vm.oreillyConfig.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var proxyHost by rememberSaveable { mutableStateOf("") }
    var card by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var warmCacheBytes by remember { mutableStateOf(-1L) }

    // Measure the warm page cache footprint on entry (best-effort; browser-managed).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        warmCacheBytes = com.citation.app.data.OreillyWebCache.sizeBytes(context)
    }

    // Seed the proxy field from the stored config once it loads (leave card/PIN blank — never echoed).
    androidx.compose.runtime.LaunchedEffect(config.proxyHost) {
        if (proxyHost.isBlank()) proxyHost = config.proxyHost
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("O'Reilly via your library", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            "Read O'Reilly on a library card through your library's proxy. Your card + PIN are stored " +
                "encrypted on this device and used only to sign in to the library page — never synced, " +
                "never sent anywhere else.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            if (config.hasCredentials) "A library card + PIN are saved." else "No card + PIN saved yet.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp)
        )
        OutlinedTextField(
            value = proxyHost, onValueChange = { proxyHost = it },
            label = { Text("Library proxy host") },
            supportingText = { Text("e.g. mcpl.idm.oclc.org") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        OutlinedTextField(
            value = card, onValueChange = { card = it },
            label = { Text("Library card number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        OutlinedTextField(
            value = pin, onValueChange = { pin = it },
            label = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            if (config.hasCredentials) {
                TextButton(onClick = { vm.clearOreillyCredentials(); card = ""; pin = "" }) {
                    Text("Forget card + PIN")
                }
            }
            Button(
                onClick = { vm.saveOreillyAccess(proxyHost, card, pin); card = ""; pin = "" },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Save") }
        }

        // Warm page cache — reclaimable, evictable, never a permanent copy of licensed content.
        Text(
            "Warm page cache",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "Pages you've opened are kept in the browser cache so they reload fast and can be re-read " +
                "during a brief disconnect. It's refetchable (safe to clear), dropped automatically " +
                "after a week idle, and never a permanent copy.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (warmCacheBytes < 0) "Measuring…"
                else "Cached: " + com.citation.core.manifest.StorageInventory.formatBytes(warmCacheBytes),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            OutlinedButton(onClick = {
                com.citation.app.data.OreillyWebCache.clear(context)
                warmCacheBytes = com.citation.app.data.OreillyWebCache.sizeBytes(context)
            }) { Text("Clear O'Reilly cache") }
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

@Composable
private fun AddKindleDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var asin by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Read a Kindle book") },
        text = {
            Column {
                Text(
                    "Read-in-place on read.amazon.com: nothing is downloaded. Copying is blocked there, so " +
                        "notes cite your location rather than the passage. The ASIN is in the book's Amazon " +
                        "URL (the read.amazon.com “?asin=…”, or the product page's Product details).",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                OutlinedTextField(
                    value = asin, onValueChange = { asin = it },
                    label = { Text("Kindle ASIN") },
                    supportingText = { Text("e.g. B0H9Y3ZFJM") },
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
            Button(onClick = { onAdd(asin.trim(), title.trim()) }, enabled = asin.isNotBlank() && title.isNotBlank()) {
                Text("Read")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
