package com.citation.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.citation.app.ui.reader.ChapterRender
import com.citation.app.ui.reader.ReaderTypography
import com.citation.app.ui.reader.RenderedChapter
import com.citation.app.ui.reader.rememberChapterImages
import com.citation.core.reader.Paginator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.core.anchor.FuzzyAnchor
import com.citation.core.anchor.TextAnchor
import com.citation.core.model.Book
import com.citation.core.model.TocEntry
import com.citation.core.model.SourceType
import com.citation.core.note.Note
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

    // Typography + theme, remembered across config changes so the reader stays how you set it.
    var fontSize by rememberSaveable { mutableStateOf(18f) }
    var serif by rememberSaveable { mutableStateOf(true) }
    var lineSpacing by rememberSaveable { mutableStateOf(1.6f) }
    var marginDp by rememberSaveable { mutableStateOf(20f) }
    // Paged (turn a screen at a time, within a chapter) vs scroll (one continuous column). Paged by
    // default — that's the "page turning" reading mode; scroll stays a tap away in Display.
    var paged by rememberSaveable { mutableStateOf(true) }
    var themeOrdinal by rememberSaveable { mutableStateOf(0) }
    val theme = ReaderTheme.entries[themeOrdinal.coerceIn(0, ReaderTheme.entries.lastIndex)]

    var showFormat by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }

    // Note composer + the note opened by tapping a highlight.
    var noteQuote by remember { mutableStateOf("") }
    var noteBody by remember { mutableStateOf("") }
    var noteHint by remember { mutableStateOf(0) }
    var showNote by remember { mutableStateOf(false) }
    var openNote by remember { mutableStateOf<Note?>(null) }

    // A pull-on-demand hint for where the reader is looking, set by the visible chapter. Capture uses
    // it to disambiguate a passage that repeats in the chapter — without recomposing on every scroll px.
    val hintProvider = remember { mutableStateOf<() -> Int>({ 0 }) }

    val view = LocalView.current
    val toolbar = remember(view) { ReaderTextToolbar(view) }
    toolbar.onAddNote = { quote -> noteQuote = quote; noteBody = ""; noteHint = hintProvider.value(); showNote = true }
    toolbar.onHighlight = { quote -> vm.captureNoteForQuote(quote, "", hintProvider.value()) }

    val background = theme.background()
    val foreground = theme.foreground()

    Scaffold(
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
                    IconButton(onClick = { showToc = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Chapters")
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
                onPrev = { vm.goToChapter(ordinal - 1) },
                onNext = { vm.goToChapter(ordinal + 1) }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(background)) {
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
                        fontSize = fontSize,
                        family = if (serif) FontFamily.Serif else FontFamily.SansSerif,
                        lineSpacing = lineSpacing,
                        marginDp = marginDp,
                        foreground = foreground,
                        turnThreshold = turnThreshold,
                        paged = paged,
                        onOpenNote = { openNote = it },
                        onProvideHint = { hintProvider.value = it }
                    )
                }
            }
        }
    }

    if (showFormat) {
        FormatSheet(
            fontSize = fontSize, onFontSize = { fontSize = it },
            serif = serif, onSerif = { serif = it },
            lineSpacing = lineSpacing, onLineSpacing = { lineSpacing = it },
            marginDp = marginDp, onMargin = { marginDp = it },
            paged = paged, onPaged = { paged = it },
            themeOrdinal = themeOrdinal, onTheme = { themeOrdinal = it },
            onDismiss = { showFormat = false }
        )
    }
    if (showToc) {
        TocSheet(
            book = book,
            current = ordinal,
            onSelect = { vm.goToChapter(it); showToc = false },
            onSelectEntry = { vm.goToTocEntry(it); showToc = false },
            onDismiss = { showToc = false }
        )
    }
    openNote?.let { note ->
        NoteDetailDialog(
            note = note,
            onSave = { body -> vm.editNote(note.key.toString(), body); openNote = null },
            onSaveTags = { raw -> vm.setNoteTags(note.key.toString(), raw) },
            onJump = { vm.jumpToNote(note); openNote = null },
            onDelete = { vm.deleteNote(note.key.toString()); openNote = null },
            onDismiss = { openNote = null }
        )
    }
}

/**
 * One chapter's flowing text, with your highlights drawn back into it. Two reading modes share this
 * entry point: **paged** (the default — the chapter is split into screen-pages you turn one at a time,
 * so page turns work *within* a chapter, not only at its boundaries) and **scroll** (one continuous
 * column). Both resolve the same highlights and feed the same capture/hint machinery; only the body
 * differs, so a note lit up in one mode lights up in the other.
 */
@Composable
private fun ChapterPage(
    vm: ReaderViewModel,
    book: Book,
    ord: Int,
    highlights: List<Note>,
    fontSize: Float,
    family: FontFamily,
    lineSpacing: Float,
    marginDp: Float,
    foreground: Color,
    turnThreshold: Float,
    paged: Boolean,
    onOpenNote: (Note) -> Unit,
    onProvideHint: (() -> Int) -> Unit
) {
    val chapter = book.chapterAt(ord)
    val text = chapter?.text ?: "(chapter unavailable)"
    val title = chapter?.title ?: ""
    val lastIndex = book.chapters.lastIndex
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val bookKey = book.key?.toString()

    // Size illustrations to the text column. sp rather than dp because the placeholder the renderer
    // reserves is measured in text units, so a plate scales with the reader's font setting like
    // everything else on the page.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val columnWidthDp = (configuration.screenWidthDp - marginDp * 2).coerceAtLeast(80f)
    val imageWidthPx = with(density) { columnWidthDp.dp.toPx() }.toInt()
    val imageWidthSp = columnWidthDp / density.fontScale
    val imageHeightSp = (configuration.screenHeightDp * 0.55f) / density.fontScale

    val blocks = chapter?.blocks.orEmpty()
    val images = rememberChapterImages(blocks, imageWidthPx) { src ->
        bookKey?.let { vm.bookAsset(it, src) }
    }

    // The drawable form of the chapter, and the map back to the canonical offsets everything is
    // stored and anchored against. Rebuilt only when the text, its structure, the typography or the
    // loaded plates change — never on a page turn.
    val rendered = remember(text, blocks, fontSize, lineSpacing, family, foreground, images, imageWidthSp) {
        ChapterRender.build(
            text = text,
            blocks = blocks,
            typography = ReaderTypography(
                fontSize = fontSize,
                lineSpacing = lineSpacing,
                family = family,
                foreground = foreground,
                accent = accent,
                secondary = secondary
            ),
            images = images,
            maxImageWidthSp = imageWidthSp,
            maxImageHeightSp = imageHeightSp
        )
    }

    // Resolve each passage-note's anchor into a live range in *this* chapter (quote + fuzzy match, so a
    // re-fetched or edited chapter still lights up the right words). A deleted passage simply doesn't.
    // Anchors are canonical offsets; the ranges are converted once into the rendered string's
    // coordinates, which is what the highlight and the tap target both need.
    val ranges = remember(rendered, highlights, ord) {
        highlights.mapNotNull { note ->
            val anchor = note.references.firstOrNull()?.anchor as? TextAnchor.Flowing ?: return@mapNotNull null
            if (anchor.chapterOrdinal != ord) return@mapNotNull null
            FuzzyAnchor.resolve(anchor, text).matchedRange
                ?.let { rendered.displayRange(it) }
                ?.takeIf { !it.isEmpty() }
                ?.let { note to it }
        }
    }
    val annotated = remember(rendered, ranges, highlightColor) {
        if (ranges.isEmpty()) {
            rendered.display
        } else {
            buildAnnotatedString {
                append(rendered.display)
                ranges.forEach { (_, range) ->
                    val start = range.first.coerceIn(0, length)
                    val end = (range.last + 1).coerceIn(start, length)
                    if (end > start) addStyle(SpanStyle(background = highlightColor), start, end)
                }
            }
        }
    }

    if (paged) {
        PagedChapterBody(
            vm, ord, lastIndex, rendered, annotated, title, ranges,
            fontSize, family, lineSpacing, marginDp, foreground, turnThreshold, onOpenNote, onProvideHint
        )
    } else {
        ScrollChapterBody(
            vm, ord, lastIndex, title, rendered, annotated, ranges,
            fontSize, family, lineSpacing, marginDp, foreground, turnThreshold, onOpenNote, onProvideHint
        )
    }
}

/**
 * The scrolling reader body: one continuous column, a horizontal swipe (or edge tap) turning to the
 * next/previous *chapter*. Scroll position is restored on the first paint of a reopened book and saved
 * as you read.
 */
@Composable
private fun ScrollChapterBody(
    vm: ReaderViewModel,
    ord: Int,
    lastIndex: Int,
    title: String,
    rendered: RenderedChapter,
    annotated: androidx.compose.ui.text.AnnotatedString,
    ranges: List<Pair<Note, IntRange>>,
    fontSize: Float,
    family: FontFamily,
    lineSpacing: Float,
    marginDp: Float,
    foreground: Color,
    turnThreshold: Float,
    onOpenNote: (Note) -> Unit,
    onProvideHint: (() -> Int) -> Unit
) {
    val scroll = rememberScrollState()
    var layout by remember(ord) { mutableStateOf<TextLayoutResult?>(null) }

    // Restore the saved scroll once (after the content is measured so maxValue is known), then persist
    // scroll as you read, debounced so a flick doesn't hammer the DB.
    LaunchedEffect(ord) {
        val restore = vm.consumePendingScroll(ord)
        if (restore > 0) {
            withTimeoutOrNull(2000) { snapshotFlow { scroll.maxValue }.first { it > 0 } }
            if (scroll.maxValue > 0) scroll.scrollTo(restore.coerceAtMost(scroll.maxValue))
        }
        snapshotFlow { scroll.value }.collectLatest { v ->
            delay(400)
            vm.savePosition(ord, v)
        }
    }
    // Publish a viewport-hint provider for capture disambiguation (reads current scroll/layout lazily).
    LaunchedEffect(ord) {
        onProvideHint {
            val l = layout
            // The disambiguator anchors against canonical text, so the visible line's display
            // offset is converted before it leaves here.
            if (l != null) {
                rendered.canonicalOf(l.getLineStart(l.getLineForVerticalPosition(scroll.value.toFloat())))
            } else {
                0
            }
        }
    }

    SelectionContainer(
        Modifier
            .fillMaxSize()
            .pointerInput(ord, lastIndex) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragCancel = { total = 0f },
                    onDragEnd = {
                        when {
                            total <= -turnThreshold && ord < lastIndex -> vm.goToChapter(ord + 1)
                            total >= turnThreshold && ord > 0 -> vm.goToChapter(ord - 1)
                        }
                    }
                ) { change, dragAmount ->
                    total += dragAmount
                    change.consume()
                }
            }
    ) {
        Column(Modifier.verticalScroll(scroll).padding(horizontal = marginDp.dp, vertical = 20.dp)) {
            Text(
                text = title,
                fontSize = (fontSize + 6).sp,
                fontFamily = family,
                color = foreground
            )
            Text(
                text = annotated,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * lineSpacing).sp,
                fontFamily = family,
                color = foreground,
                inlineContent = rendered.inlineContent,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .pointerInput(ord, ranges, lastIndex) {
                        detectTapGestures { pos ->
                            val l = layout ?: return@detectTapGestures
                            val offset = l.getOffsetForPosition(pos)
                            val hit = ranges.firstOrNull { offset in it.second }
                            if (hit != null) {
                                onOpenNote(hit.first)
                            } else {
                                // Edge tap-zones page too, for readers who never swipe.
                                val w = size.width.toFloat()
                                when {
                                    pos.x < w * 0.22f && ord > 0 -> vm.goToChapter(ord - 1)
                                    pos.x > w * 0.78f && ord < lastIndex -> vm.goToChapter(ord + 1)
                                }
                            }
                        }
                    }
            )
        }
    }
}

/**
 * The **paged** reader body. The chapter text is measured against the live viewport and typography and
 * split into screen-pages ([Paginator]); you turn them one at a time with a swipe or an edge tap, and
 * only the last/first page of a chapter crosses into the next/previous chapter. Turns animate like the
 * chapter-level ones so a within-chapter turn and a chapter turn feel the same.
 *
 * Position is persisted as the current page's start **character offset** (font-size independent), so a
 * reopened book lands on the same page. That offset shares the same stored slot as scroll mode's pixel
 * offset; since it's only read once at open, a book read consistently in one mode always resumes true.
 */
@Composable
private fun PagedChapterBody(
    vm: ReaderViewModel,
    ord: Int,
    lastIndex: Int,
    rendered: RenderedChapter,
    annotated: androidx.compose.ui.text.AnnotatedString,
    title: String,
    ranges: List<Pair<Note, IntRange>>,
    fontSize: Float,
    family: FontFamily,
    lineSpacing: Float,
    marginDp: Float,
    foreground: Color,
    turnThreshold: Float,
    onOpenNote: (Note) -> Unit,
    onProvideHint: (() -> Int) -> Unit
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = TextStyle(
        fontSize = fontSize.sp,
        lineHeight = (fontSize * lineSpacing).sp,
        fontFamily = family,
        color = foreground
    )
    val titleStyle = TextStyle(fontSize = (fontSize + 6).sp, fontFamily = family, color = foreground)

    BoxWithConstraints(
        Modifier.fillMaxSize().padding(horizontal = marginDp.dp, vertical = 20.dp)
    ) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val titleGapPx = with(density) { 12.dp.toPx() }

        // Measure the whole chapter once for the current width/typography, then break it into pages.
        // Page 0 gives up room for the chapter title. Recomputed only when text, typography, or the
        // viewport changes — a page turn is a cheap index change, not a re-measure.
        val pageStarts = remember(rendered, fontSize, lineSpacing, family, widthPx, heightPx) {
            if (widthPx <= 0 || heightPx <= 0) {
                listOf(0)
            } else {
                // Measured with the placeholders the renderer reserved, so a page that holds a
                // plate accounts for its height instead of overflowing by exactly that much.
                val layout = measurer.measure(
                    rendered.display,
                    style = textStyle,
                    constraints = Constraints(maxWidth = widthPx),
                    placeholders = rendered.placeholders
                )
                val titleBlock = if (title.isBlank()) 0f else {
                    measurer.measure(
                        androidx.compose.ui.text.AnnotatedString(title),
                        style = titleStyle,
                        constraints = Constraints(maxWidth = widthPx)
                    ).size.height.toFloat() + titleGapPx
                }
                Paginator.pageStarts(
                    lineCount = layout.lineCount,
                    lineTop = { layout.getLineTop(it) },
                    lineBottom = { layout.getLineBottom(it) },
                    lineStartChar = { layout.getLineStart(it) },
                    firstCapacityPx = heightPx - titleBlock,
                    capacityPx = heightPx.toFloat()
                )
            }
        }

        // Page index survives font/margin changes (re-clamped below); it only resets per chapter.
        var page by rememberSaveable(ord) { mutableStateOf(0) }
        val safePage = page.coerceIn(0, pageStarts.lastIndex)
        var turnDir by remember { mutableStateOf(1) }
        val pageStartsState = rememberUpdatedState(pageStarts)

        fun turnNext() {
            turnDir = 1
            if (safePage < pageStarts.lastIndex) page = safePage + 1
            else if (ord < lastIndex) vm.goToChapter(ord + 1)
        }
        fun turnPrev() {
            turnDir = -1
            if (safePage > 0) page = safePage - 1
            else if (ord > 0) vm.goToChapter(ord - 1)
        }

        // Restore the saved page once the pages are known: find the page whose slice holds the saved
        // character offset. Consumed once, so a turn doesn't snap back.
        var restoreOffset by remember(ord) { mutableStateOf(-1) }
        LaunchedEffect(ord) { restoreOffset = vm.consumePendingScroll(ord) }
        LaunchedEffect(pageStarts, restoreOffset) {
            if (restoreOffset > 0 && pageStarts.size > 1) {
                // The stored offset is canonical, so it is translated into the rendered string's
                // coordinates before looking for the page that holds it. That is what lets a
                // position saved before this book had any structure still land on the right page.
                val target = rendered.displayOf(restoreOffset)
                page = pageStarts.indexOfLast { it <= target }.coerceAtLeast(0)
                restoreOffset = 0
            }
        }
        // Persist the page's start as a *canonical* offset — font-size independent, and independent
        // of whether the chapter was rendered with structure at all.
        LaunchedEffect(safePage, pageStarts) {
            delay(400)
            vm.savePosition(ord, rendered.canonicalOf(pageStarts.getOrElse(safePage) { 0 }))
        }
        // Capture disambiguation hint = where the current page starts, in canonical text.
        LaunchedEffect(ord) {
            onProvideHint {
                val starts = pageStartsState.value
                rendered.canonicalOf(starts.getOrElse(page.coerceIn(0, starts.lastIndex)) { 0 })
            }
        }

        AnimatedContent(
            targetState = safePage,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val dir = turnDir
                (slideInHorizontally(tween(220)) { w -> dir * w } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(220)) { w -> -dir * w } + fadeOut(tween(220)))
            },
            label = "page"
        ) { p ->
            val start = pageStarts.getOrElse(p) { 0 }
            val end = pageStarts.getOrElse(p + 1) { rendered.length }
            val slice = annotated.subSequence(start.coerceIn(0, annotated.length), end.coerceIn(start, annotated.length))
            var layout by remember(p, pageStarts) { mutableStateOf<TextLayoutResult?>(null) }

            SelectionContainer(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(p, lastIndex, pageStarts.size) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragCancel = { total = 0f },
                            onDragEnd = {
                                when {
                                    total <= -turnThreshold -> turnNext()
                                    total >= turnThreshold -> turnPrev()
                                }
                            }
                        ) { change, dragAmount ->
                            total += dragAmount
                            change.consume()
                        }
                    }
            ) {
                Column(Modifier.fillMaxSize()) {
                    if (p == 0 && title.isNotBlank()) {
                        Text(text = title, style = titleStyle)
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        text = slice,
                        style = textStyle,
                        inlineContent = rendered.inlineContent,
                        onTextLayout = { layout = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(p, ranges, start) {
                                detectTapGestures { pos ->
                                    val l = layout ?: return@detectTapGestures
                                    val local = l.getOffsetForPosition(pos)
                                    val global = start + local
                                    val hit = ranges.firstOrNull { global in it.second }
                                    if (hit != null) {
                                        onOpenNote(hit.first)
                                    } else {
                                        val w = size.width.toFloat()
                                        when {
                                            pos.x < w * 0.30f -> turnPrev()
                                            pos.x > w * 0.70f -> turnNext()
                                        }
                                    }
                                }
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(ordinal: Int, count: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { if (count > 0) (ordinal + 1f) / count else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = onPrev, enabled = ordinal > 0) { Text("Previous") }
                Text("Chapter ${ordinal + 1} / $count", Modifier.align(Alignment.CenterVertically))
                OutlinedButton(onClick = onNext, enabled = ordinal < count - 1) { Text("Next") }
            }
        }
    }
}

// --- Reading themes ----------------------------------------------------------------------------

/** The reader's background/text presets. SYSTEM defers to the app's Material colours. */
enum class ReaderTheme(val label: String) { SYSTEM("System"), PAPER("Paper"), SEPIA("Sepia"), NIGHT("Night") }

@Composable
private fun ReaderTheme.background(): Color = when (this) {
    ReaderTheme.SYSTEM -> MaterialTheme.colorScheme.background
    ReaderTheme.PAPER -> Color(0xFFFBF7EF)
    ReaderTheme.SEPIA -> Color(0xFFF4ECD8)
    ReaderTheme.NIGHT -> Color(0xFF121212)
}

@Composable
private fun ReaderTheme.foreground(): Color = when (this) {
    ReaderTheme.SYSTEM -> MaterialTheme.colorScheme.onBackground
    ReaderTheme.PAPER -> Color(0xFF2B2B2B)
    ReaderTheme.SEPIA -> Color(0xFF5B4636)
    ReaderTheme.NIGHT -> Color(0xFFD7D7D2)
}

// --- Format + chapters sheets ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatSheet(
    fontSize: Float, onFontSize: (Float) -> Unit,
    serif: Boolean, onSerif: (Boolean) -> Unit,
    lineSpacing: Float, onLineSpacing: (Float) -> Unit,
    marginDp: Float, onMargin: (Float) -> Unit,
    paged: Boolean, onPaged: (Boolean) -> Unit,
    themeOrdinal: Int, onTheme: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Display", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)

            LabeledSlider("Text size", fontSize, 12f..30f) { onFontSize(it) }
            LabeledSlider("Line spacing", lineSpacing, 1.2f..2.2f) { onLineSpacing(it) }
            LabeledSlider("Margins", marginDp, 8f..48f) { onMargin(it) }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Reading", Modifier.weight(1f))
                Choice("Paged", paged) { onPaged(true) }
                Spacer(Modifier.width(8.dp))
                Choice("Scroll", !paged) { onPaged(false) }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Typeface", Modifier.weight(1f))
                Choice("Serif", serif) { onSerif(true) }
                Spacer(Modifier.width(8.dp))
                Choice("Sans", !serif) { onSerif(false) }
            }

            Text("Theme", Modifier.padding(top = 16.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.secondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEachIndexed { i, t ->
                    Choice(t.label, i == themeOrdinal, Modifier.weight(1f)) { onTheme(i) }
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, maxLines = 1) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocSheet(
    book: Book,
    current: Int,
    onSelect: (Int) -> Unit,
    onSelectEntry: (TocEntry) -> Unit,
    onDismiss: () -> Unit
) {
    // The publisher's own contents when the book has one, otherwise the spine — which is all the
    // reader ever used to show. For anything longer than a novel the difference is the difference
    // between "Part II › Chapter 7 › Consistent Hashing" and a hundred numbered files.
    val entries = remember(book) { book.toc.flatten() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                if (entries.isEmpty()) "Chapters" else "Contents",
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                if (entries.isEmpty()) {
                    itemsIndexed(book.chapters) { i, ch ->
                        TocRow(
                            label = "${i + 1}.  ${ch.title.ifBlank { "Chapter ${i + 1}" }}",
                            depth = 0,
                            selected = i == current,
                            enabled = true,
                            onClick = { onSelect(i) }
                        )
                    }
                } else {
                    itemsIndexed(entries) { _, (entry, depth) ->
                        TocRow(
                            label = entry.title,
                            depth = depth,
                            selected = entry.chapterOrdinal == current,
                            // An entry whose target isn't in the spine is still shown — it is part
                            // of the book's shape — but there is nowhere to send you.
                            enabled = entry.chapterOrdinal != null,
                            onClick = { onSelectEntry(entry) }
                        )
                    }
                }
            }
            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun TocRow(
    label: String,
    depth: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = (20 + depth * 16).dp,
                end = 20.dp,
                top = if (depth == 0) 12.dp else 8.dp,
                bottom = if (depth == 0) 12.dp else 8.dp
            ),
        color = when {
            selected -> MaterialTheme.colorScheme.primary
            !enabled -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurface
        },
        fontWeight = if (selected || depth == 0) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = if (depth == 0) 16.sp else 15.sp,
        fontFamily = FontFamily.Serif
    )
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
            label = { Text("Passage to cite (select text to fill, or type it)") },
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
