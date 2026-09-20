package com.citation.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.app.data.bookAsset
import com.citation.app.data.captureSynthesis
import com.citation.app.data.deleteBookmark
import com.citation.app.data.setBookmarkLabel
import com.citation.app.data.setNoteHighlight
import com.citation.app.data.setNoteTags
import com.citation.app.ui.reader.ChapterRender
import com.citation.app.ui.reader.ReaderTypography
import com.citation.app.ui.reader.ReaderWindowEffects
import com.citation.app.ui.reader.RenderedChapter
import com.citation.app.ui.reader.rememberChapterImages
import com.citation.app.ui.reader.rememberReaderFontFamily
import com.citation.core.anchor.FuzzyAnchor
import com.citation.core.anchor.TextAnchor
import com.citation.core.model.Book
import com.citation.core.model.SourceType
import com.citation.core.model.TocEntry
import com.citation.core.note.HighlightColor
import com.citation.core.note.Note
import com.citation.core.reader.BookReferences
import com.citation.core.reader.Lookup
import com.citation.core.reader.ReferenceTarget
import com.citation.core.reader.PageTurn
import com.citation.core.reader.Paginator
import com.citation.core.reader.ReaderColors
import com.citation.core.reader.ReaderPalette
import com.citation.core.reader.ReaderSettings
import com.citation.core.reader.VolumeKeys
import com.citation.core.speech.NarrationState
import com.citation.core.speech.NarrationStatus
import com.citation.core.speech.SleepTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
    val pdfSession by vm.pdfSession.collectAsStateWithLifecycle()
    val oreillySession by vm.oreillySession.collectAsStateWithLifecycle()
    val kindleSession by vm.kindleSession.collectAsStateWithLifecycle()
    val catalogsOpen by vm.catalogsOpen.collectAsStateWithLifecycle()
    val readerSettings by vm.settings.collectAsStateWithLifecycle()

    // Pause the engaged-reading meter whenever the app leaves the foreground, and resume on return —
    // so backgrounded time never accrues. Guarded inside the VM (no-op when no book is being read),
    // so a single observer here covers all three reader tracks and is harmless on the home screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onReaderHidden()
                Lifecycle.Event.ON_START -> vm.onReaderVisible()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Screen behaviour applies to whichever reader is open — the flowing text, a PDF's pages, a
    // licensed book in its own WebView — rather than only to the one whose Display sheet sets it.
    val reading = openBook != null || pdfSession != null || oreillySession != null || kindleSession != null
    ReaderWindowEffects(settings = readerSettings, active = reading)

    // The immersive readers (PDF, O'Reilly, flowing text) each preempt the tab shell. When none is
    // open, the app lands on the consolidated home with its bottom tabs.
    if (pdfSession != null) {
        PdfReaderScreen(pdfSession!!, vm)
        return
    }
    if (oreillySession != null) {
        OreillyReaderScreen(oreillySession!!, vm)
        return
    }
    if (kindleSession != null) {
        KindleReaderScreen(kindleSession!!, vm)
        return
    }

    // Catalog browsing preempts the shell too: it is a place you go into and come back from, not a
    // tab you leave half-scrolled.
    if (catalogsOpen) {
        CatalogsScreen(vm, onClose = { vm.closeCatalogs() })
        return
    }

    if (openBook == null) {
        CitationHome(vm)
    } else {
        FlowingReader(vm)
    }
}

/**
 * The flowing-text reader proper. Everything that makes it feel like a *reader* rather than a text
 * dump lives here: your highlights drawn back into the page and tappable, typography + reading themes,
 * a chapter drawer, resume-where-you-left-off, animated page turns, and swipe/tap paging — all over
 * the format-blind [Book].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowingReader(vm: ReaderViewModel) {
    val book = vm.openBook.collectAsStateWithLifecycle().value ?: return
    val ordinal by vm.chapterOrdinal.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val highlights by vm.openHighlights.collectAsStateWithLifecycle()
    val searchRanges by vm.searchRanges.collectAsStateWithLifecycle()
    val bookmarkHere by vm.bookmarkHere.collectAsStateWithLifecycle()
    val searchOpen by vm.searchOpen.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val timeLeft by vm.timeLeft.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchHits by vm.searchHits.collectAsStateWithLifecycle()
    val narration by vm.narration.collectAsStateWithLifecycle()

    // Typography, theme and screen behaviour — persisted, and per-book when this book keeps its own.
    val settings by vm.settings.collectAsStateWithLifecycle()
    val perBook by vm.perBookSettings.collectAsStateWithLifecycle()
    val fonts by vm.readerFonts.collectAsStateWithLifecycle()
    val family = rememberReaderFontFamily(settings)

    var showFormat by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showFontPicker by remember { mutableStateOf(false) }
    // A selection the reader asked to look up; the sheet decides where to send it.
    var lookup by remember { mutableStateOf<Lookup.Query?>(null) }

    // Note composer + the note opened by tapping a highlight.
    var noteQuote by remember { mutableStateOf("") }
    var noteBody by remember { mutableStateOf("") }
    var noteHint by remember { mutableStateOf(0) }
    var showNote by remember { mutableStateOf(false) }
    var openNote by remember { mutableStateOf<Note?>(null) }

    // A note reference the reader tapped, held as the note to show and the place it lives, so
    // "read the whole note" has somewhere to go. And an address the book points at, held until the
    // reader says whether they want to leave for it.
    var footnote by remember { mutableStateOf<Pair<String, ReferenceTarget.InBook>?>(null) }
    var footnoteCut by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<String?>(null) }
    val returnTo by vm.returnTo.collectAsStateWithLifecycle()

    // Back undoes the jump before it closes the book — the same order the bar above the page
    // offers, and what every reader does with a footnote. Enabled only while there is somewhere to
    // go back to, so back means what it always meant the rest of the time.
    BackHandler(enabled = returnTo != null) { vm.returnFromJump() }

    // A pull-on-demand hint for where the reader is looking, set by the visible chapter. Capture uses
    // it to disambiguate a passage that repeats in the chapter — without recomposing on every scroll px.
    val hintProvider = remember { mutableStateOf<() -> Int>({ 0 }) }

    val view = LocalView.current
    val toolbar = remember(view) { ReaderTextToolbar(view) }
    toolbar.onAddNote = { quote -> noteQuote = quote; noteBody = ""; noteHint = hintProvider.value(); showNote = true }
    toolbar.onHighlight = { quote -> vm.captureNoteForQuote(quote, "", hintProvider.value()) }
    toolbar.onLookUp = { selection -> lookup = Lookup.of(selection).takeIf { !it.isEmpty } }

    // Warmth is applied to the colours themselves rather than by laying a translucent orange sheet
    // over the page: an overlay dims everything it covers, flattening contrast exactly when a reader
    // has turned to warm colours because it is late and their eyes are tired.
    // Every colour on the page comes from here, including the ones for headings and links. They used
    // to be read straight out of the app's Material scheme, which meant a book was partly set in
    // whatever accent the launcher's wallpaper had produced — it ignored the reader's own colours and
    // never warmed with them.
    val colours = ReaderPalette.colors(
        settings,
        fallbackPage = MaterialTheme.colorScheme.background.toArgb(),
        fallbackText = MaterialTheme.colorScheme.onBackground.toArgb()
    )
    val background = Color(colours.page)
    val foreground = Color(colours.text)

    // A font the reader picks is read once and copied into the sovereign store — a book set in a
    // face whose file later moves or is deleted would otherwise change appearance for no visible
    // reason. `OpenDocument` rather than `GetContent` so several MIME types can be offered: font
    // files are typed inconsistently, and plenty arrive as octet-stream.
    val context = LocalContext.current
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        showFontPicker = false
        uri ?: return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        // The document's own name — what the reader will recognise the face by, and the only chance
        // to learn it. `lastPathSegment` is a provider's document id on most devices ("msf:1234"),
        // so it is asked for by name and only used for the extension when the provider has none.
        val pickedName = documentName(context, uri)
        val extension = (pickedName ?: uri.lastPathSegment.orEmpty())
            .substringAfterLast('.', "")
            .takeIf { it.length in 1..4 } ?: "ttf"
        if (bytes == null || bytes.isEmpty()) vm.reportImportProblem("Couldn't read that font file.")
        else vm.addReaderFont(bytes, extension, pickedName)
    }
    LaunchedEffect(showFontPicker) {
        if (showFontPicker) {
            fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream", "*/*"))
        }
    }

    // Volume keys reach an app only through a focused view, so the reader root takes focus and
    // previews key events before the system's volume handling gets them. Requesting focus is
    // harmless when the feature is off — `onVolumeKey` simply declines and the volume UI behaves
    // exactly as it always did.
    val readerFocus = remember { FocusRequester() }
    LaunchedEffect(settings.volumeKeyTurns) {
        if (settings.volumeKeyTurns) runCatching { readerFocus.requestFocus() }
    }

    Scaffold(
        modifier = Modifier
            .focusRequester(readerFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.VolumeUp -> vm.onVolumeKey(volumeUp = true)
                    Key.VolumeDown -> vm.onVolumeKey(volumeUp = false)
                    else -> false
                }
            },
        containerColor = background,
        topBar = {
            TopAppBar(
                title = { Text(book.metadata.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { vm.closeBook() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.openSearch() }) {
                        Icon(Icons.Default.Search, contentDescription = "Search this book")
                    }
                    IconButton(onClick = { vm.toggleBookmark(hintProvider.value()) }) {
                        Icon(
                            if (bookmarkHere != null) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (bookmarkHere != null) "Remove bookmark" else "Bookmark this page"
                        )
                    }
                    IconButton(onClick = { showToc = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Contents")
                    }
                    IconButton(onClick = { showFormat = true }) {
                        Text("Aa", fontWeight = FontWeight.Bold)
                    }
                    if (vm.isRoyalRoadOpen) {
                        IconButton(onClick = { vm.favoriteRoyalRoad() }) {
                            Icon(Icons.Default.Star, contentDescription = "Favourite (full backfill)")
                        }
                    }
                    // A reflowed PDF is a derived view: the rendered page is always one tap away, for
                    // the figure or table the reflow flattened. It opens on the page you're reading.
                    if (book.metadata.source == SourceType.PDF) {
                        IconButton(onClick = { vm.readPdfAsPages(hintProvider.value()) }) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = "Show the page")
                        }
                    }
                    IconButton(onClick = {
                        noteQuote = ""; noteBody = ""; noteHint = hintProvider.value(); showNote = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add note")
                    }
                }
            )
        },
        // Chapter paging lives in the Scaffold's bottomBar, pinned so a long passage can never push it
        // below the viewport (the failure that once made "Previous/Next" vanish for long chapters).
        bottomBar = {
            ReaderBottomBar(
                ordinal = ordinal,
                count = book.chapters.size,
                percent = progress?.percent,
                fraction = progress?.fraction,
                timeLeft = timeLeft,
                narration = narration,
                canReadAloud = vm.canReadAloud,
                onPlayPause = { vm.toggleAloud() },
                onPrev = { vm.goToChapter(ordinal - 1) },
                onNext = { vm.goToChapter(ordinal + 1) }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(background)) {
            // Search sits over the page rather than replacing it: you are looking for a passage in
            // order to get back to it, so the book stays underneath and a hit is one tap away.
            if (searchOpen) {
                SearchBar(
                    query = searchQuery,
                    hits = searchHits,
                    onQuery = vm::search,
                    onHit = { vm.goToHit(it) },
                    onClose = { vm.closeSearch() }
                )
            }
            if (showNote) {
                NoteComposer(
                    quote = noteQuote,
                    body = noteBody,
                    onQuote = { noteQuote = it },
                    onBody = { noteBody = it },
                    onSave = {
                        vm.captureNoteForQuote(noteQuote, noteBody, noteHint)
                        noteQuote = ""; noteBody = ""; showNote = false
                    },
                    onSaveSynthesis = {
                        vm.captureSynthesis(listOf(noteQuote), noteBody)
                        noteQuote = ""; noteBody = ""; showNote = false
                    },
                    onCancel = { showNote = false }
                )
            }

            // Somewhere to come back to, stated rather than remembered. A jump you cannot undo is
            // one people stop taking: following a note means losing the paragraph you were in, and
            // hunting for it again costs more than the note was worth. It sits over the page like
            // the search bar, for the same reason — you are still reading the sentence underneath.
            returnTo?.let { place ->
                val label = book.chapterAt(place.chapterOrdinal)?.title?.takeIf { it.isNotBlank() }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.returnFromJump() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            if (label == null) "Back to where you were" else "Back to $label",
                            Modifier.weight(1f).padding(start = 12.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // For the jump that was not a peek: you went to the chapter you meant to
                        // read, and the offer is now in the way.
                        IconButton(onClick = { vm.forgetReturn() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Stop offering to go back",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            status?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp).clickable { vm.clearStatus() },
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            // Animated page turns give the swipe/tap something to answer to: the outgoing chapter
            // slides off toward the turn direction while the next slides in. The paging + selection
            // gestures live inside each page (they need that page's ordinal), so they never fight
            // across a transition.
            val turnThreshold = with(LocalDensity.current) { 64.dp.toPx() }
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                AnimatedContent(
                    targetState = ordinal,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(260)) { w -> dir * w } + fadeIn(tween(260))) togetherWith
                            (slideOutHorizontally(tween(260)) { w -> -dir * w } + fadeOut(tween(260)))
                    },
                    label = "chapter"
                ) { ord ->
                    ChapterPage(
                        vm = vm,
                        book = book,
                        ord = ord,
                        highlights = highlights,
                        searchRanges = searchRanges,
                        settings = settings,
                        family = family,
                        colours = colours,
                        turnThreshold = turnThreshold,
                        onOpenNote = { openNote = it },
                        onReference = { reference ->
                            // Where it points is decided in :core, against this book. A reference
                            // naming something the book does not contain resolves to nothing at
                            // all rather than to the top of the file it named — so the reader is
                            // told, instead of being dropped at note 1 in answer to note 17.
                            when (val target = BookReferences.resolve(reference.href, book, ord)) {
                                is ReferenceTarget.External -> leaving = target.url
                                is ReferenceTarget.InBook ->
                                    if (reference.isNote) {
                                        val text = BookReferences.note(book, target)
                                        if (text == null) vm.followReference(target) else {
                                            footnote = text to target
                                            footnoteCut = BookReferences.noteIsCut(book, target)
                                        }
                                    } else {
                                        vm.followReference(target)
                                    }
                                null -> vm.reportBrokenReference()
                            }
                        },
                        onProvideHint = { hintProvider.value = it }
                    )
                }
            }
        }
    }

    if (showFormat) {
        DisplaySheet(
            settings = settings,
            perBook = perBook,
            fonts = fonts,
            canScopeToBook = book.key != null,
            onSettings = vm::updateSettings,
            onPerBook = vm::setPerBookSettings,
            onPickFont = { showFontPicker = true },
            onRenameFont = vm::renameReaderFont,
            onDeleteFont = vm::deleteReaderFont,
            onDismiss = { showFormat = false }
        )
    }
    if (showToc) {
        TocSheet(
            book = book,
            current = ordinal,
            bookmarkCount = bookmarks.size,
            onOpenBookmarks = { showToc = false; showBookmarks = true },
            onSelect = { vm.goToChapter(it); showToc = false },
            onSelectEntry = { vm.goToTocEntry(it); showToc = false },
            onDismiss = { showToc = false }
        )
    }
    if (showBookmarks) {
        BookmarksSheet(
            bookmarks = bookmarks,
            onGo = { vm.goToBookmark(it); showBookmarks = false },
            onDelete = { vm.deleteBookmark(it) },
            onLabel = { bookmark, label -> vm.setBookmarkLabel(bookmark, label) },
            onDismiss = { showBookmarks = false }
        )
    }

    lookup?.let { query ->
        LookupSheet(query = query, onDismiss = { lookup = null })
    }

    footnote?.let { (text, target) ->
        FootnoteSheet(
            text = text,
            cut = footnoteCut,
            onGoToNote = { footnote = null; vm.followReference(target) },
            onDismiss = { footnote = null }
        )
    }

    leaving?.let { url ->
        LeaveBookDialog(url = url, onDismiss = { leaving = null })
    }

    openNote?.let { note ->
        NoteDetailDialog(
            note = note,
            onSave = { body -> vm.editNote(note.key.toString(), body); openNote = null },
            onSaveTags = { raw -> vm.setNoteTags(note.key.toString(), raw) },
            onSaveHighlight = { color -> vm.setNoteHighlight(note.key.toString(), color) },
            onJump = { vm.jumpToNote(note); openNote = null },
            onDelete = { vm.deleteNote(note.key.toString()); openNote = null },
            onDismiss = { openNote = null }
        )
    }
}

// --- Reading themes ----------------------------------------------------------------------------

/** The reader's background/text presets. SYSTEM defers to the app's Material colours. */
// --- Format + chapters sheets ------------------------------------------------------------------

