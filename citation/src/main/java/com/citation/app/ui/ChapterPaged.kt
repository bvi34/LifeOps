package com.citation.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.app.ui.reader.RenderedChapter
import com.citation.core.note.Note
import com.citation.core.reader.PageTurn
import com.citation.core.reader.Paginator
import com.citation.core.reader.ReaderSettings
import com.citation.core.reader.VolumeKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The paged chapter body: the same text, measured into pages and turned rather than scrolled.
 */

/**
 * The **paged** reader body. The chapter text is measured against the live viewport and typography and
 * split into screen-pages ([Paginator]); you turn them one at a time with a swipe or an edge tap, and
 * only the last/first page of a chapter crosses into the next/previous chapter — a backward crossing
 * landing on the previous chapter's *last* page, so a page back is always one page ([PageTurn]).
 * Turns animate like the chapter-level ones so a within-chapter turn and a chapter turn feel the same.
 *
 * Position is persisted as the current page's start **character offset** (font-size independent), so a
 * reopened book lands on the same page. That offset shares the same stored slot as scroll mode's pixel
 * offset; since it's only read once at open, a book read consistently in one mode always resumes true.
 */
@Composable
internal fun PagedChapterBody(
    vm: ReaderViewModel,
    ord: Int,
    lastIndex: Int,
    rendered: RenderedChapter,
    annotated: androidx.compose.ui.text.AnnotatedString,
    title: String,
    ranges: List<Pair<Note, IntRange>>,
    settings: ReaderSettings,
    family: FontFamily,
    foreground: Color,
    turnThreshold: Float,
    onOpenNote: (Note) -> Unit,
    onProvideHint: (() -> Int) -> Unit
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = readerTextStyle(settings, family, foreground)
    val titleStyle = TextStyle(fontSize = (settings.fontSize + 6).sp, fontFamily = family, color = foreground)

    BoxWithConstraints(
        Modifier.fillMaxSize().padding(horizontal = settings.marginDp.dp, vertical = 20.dp)
    ) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val titleGapPx = with(density) { 12.dp.toPx() }

        // Measure the whole chapter once for the current width/typography, then break it into pages.
        // Page 0 gives up room for the chapter title. Recomputed only when text, typography, or the
        // viewport changes — a page turn is a cheap index change, not a re-measure.
        val pageStarts = remember(rendered, settings, family, widthPx, heightPx) {
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

        // Entering this chapter *backwards* (a page back off the next chapter's first page) opens it
        // at its end. Read during composition rather than from an effect so the last page is the
        // first thing drawn — landing on page 0 and then animating to the end would show the reader
        // a page they didn't ask for. Int.MAX_VALUE means "the end": the clamp below resolves it
        // once the chapter has been measured, so it survives the first frame, when there is exactly
        // one known page, and re-resolves if typography changes the page count.
        val landAtEnd = remember(ord) { vm.consumePendingEnd(ord) }

        // Page index survives font/margin changes (re-clamped below); it only resets per chapter.
        var page by rememberSaveable(ord) { mutableStateOf(if (landAtEnd) Int.MAX_VALUE else 0) }
        val safePage = page.coerceIn(0, pageStarts.lastIndex)
        var turnDir by remember { mutableStateOf(1) }
        val pageStartsState = rememberUpdatedState(pageStarts)

        // Both turns read the live page and page breaks rather than the values captured when the
        // gesture was wired up: a stale index here would read as a page back skipping the chapter.
        fun turn(move: PageTurn.Move) {
            when (move) {
                is PageTurn.Move.Page -> page = move.page
                is PageTurn.Move.Chapter -> when (move.landing) {
                    PageTurn.Landing.FIRST_PAGE -> vm.goToChapter(move.chapter)
                    PageTurn.Landing.LAST_PAGE -> vm.goToChapterEnd(move.chapter)
                }
                PageTurn.Move.Edge -> {}
            }
        }
        fun turnNext() {
            turnDir = 1
            val starts = pageStartsState.value
            turn(PageTurn.next(page.coerceIn(0, starts.lastIndex), starts.lastIndex, ord, lastIndex))
        }
        fun turnPrev() {
            turnDir = -1
            val starts = pageStartsState.value
            turn(PageTurn.previous(page.coerceIn(0, starts.lastIndex), starts.lastIndex, ord, lastIndex))
        }

        // A volume key press is handled where the page boundaries are known — the paginator's page
        // starts live in this composition, so the ViewModel records the intent and the surface that
        // can act on it picks it up.
        val volumeTurn by vm.pageTurns.collectAsStateWithLifecycle()
        LaunchedEffect(volumeTurn) {
            when (vm.consumePageTurn()) {
                VolumeKeys.Action.NEXT_PAGE -> turnNext()
                VolumeKeys.Action.PREVIOUS_PAGE -> turnPrev()
                else -> {}
            }
        }

        // Restore the saved page once the pages are known: find the page whose slice holds the saved
        // character offset. Consumed once, so a turn doesn't snap back.
        var restoreOffset by remember(ord) { mutableStateOf(-1) }
        // Both channels are canonical in this mode, so either one restores the same way. The voice's
        // is taken first: it is the more recent place by construction when both are staged.
        LaunchedEffect(ord) {
            restoreOffset = vm.consumePendingCanonical(ord).takeIf { it >= 0 } ?: vm.consumePendingScroll(ord)
        }
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
            val canonical = rendered.canonicalOf(pageStarts.getOrElse(safePage) { 0 })
            vm.onPositionChanged(ord, canonical)
            delay(400)
            vm.savePosition(ord, canonical)
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
