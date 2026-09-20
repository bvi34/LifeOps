package com.citation.app.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.app.ui.reader.RenderedChapter
import com.citation.app.ui.reader.RenderedReference
import com.citation.core.note.Note
import com.citation.core.reader.ReaderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The scrolling chapter body — the default, which keeps your place by character offset rather
 * than by page.
 */

/**
 * The scrolling reader body: one continuous column, a horizontal swipe (or edge tap) turning to the
 * next/previous *chapter*. Turning back enters the previous chapter at its **end**, so reading
 * backwards is continuous rather than skipping over the chapter you just turned into. Scroll position
 * is restored on the first paint of a reopened book and saved as you read.
 */
@Composable
internal fun ScrollChapterBody(
    vm: ReaderViewModel,
    ord: Int,
    lastIndex: Int,
    title: String,
    rendered: RenderedChapter,
    annotated: androidx.compose.ui.text.AnnotatedString,
    ranges: List<Pair<Note, IntRange>>,
    settings: ReaderSettings,
    family: FontFamily,
    foreground: Color,
    turnThreshold: Float,
    onOpenNote: (Note) -> Unit,
    /** A link or note reference the reader tapped; where it goes is decided above. */
    onReference: (RenderedReference) -> Unit,
    onProvideHint: (() -> Int) -> Unit
) {
    val scroll = rememberScrollState()
    var layout by remember(ord) { mutableStateOf<TextLayoutResult?>(null) }

    // Restore the saved scroll once (after the content is measured so maxValue is known), then persist
    // scroll as you read, debounced so a flick doesn't hammer the DB.
    // Re-run on a jump as well as on a chapter change: a note two paragraphs down the page you are
    // already on is the commonest reference there is, and the ordinal does not change for it.
    val jump by vm.jumps.collectAsStateWithLifecycle()
    LaunchedEffect(ord, jump) {
        // A place the *voice* reached is a canonical character offset, so it is resolved through the
        // layout — find the line holding that character and scroll to its top — rather than being
        // handed to scrollTo as though it were a pixel count, which is what the reading position on
        // the older channel below actually is in this mode.
        val canonical = vm.consumePendingCanonical(ord)
        val restore = vm.consumePendingScroll(ord)
        // Turned back into from the chapter after this one: continue reading backwards from its end
        // rather than being thrown to its first line, which is nowhere near where the turn came from.
        val landAtEnd = vm.consumePendingEnd(ord)
        if (canonical > 0) {
            withTimeoutOrNull(2000) { snapshotFlow { layout to scroll.maxValue }.first { it.first != null && it.second > 0 } }
            layout?.let { l ->
                val display = rendered.displayOf(canonical).coerceIn(0, l.layoutInput.text.length)
                val top = l.getLineTop(l.getLineForOffset(display)).toInt()
                scroll.scrollTo(top.coerceIn(0, scroll.maxValue))
            }
        } else if (restore > 0 || landAtEnd) {
            withTimeoutOrNull(2000) { snapshotFlow { scroll.maxValue }.first { it > 0 } }
            if (scroll.maxValue > 0) {
                scroll.scrollTo(if (landAtEnd) scroll.maxValue else restore.coerceAtMost(scroll.maxValue))
            }
        }
        snapshotFlow { scroll.value }.collectLatest { v ->
            delay(400)
            vm.savePosition(ord, v)
            // Progress and pace are measured in canonical characters, so both reading modes report
            // the same thing — the numbers must not jump when you switch between them.
            layout?.let { l ->
                vm.onPositionChanged(
                    ord,
                    rendered.canonicalOf(l.getLineStart(l.getLineForVerticalPosition(v.toFloat())))
                )
            }
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
                            total >= turnThreshold && ord > 0 -> vm.goToChapterEnd(ord - 1)
                        }
                    }
                ) { change, dragAmount ->
                    total += dragAmount
                    change.consume()
                }
            }
    ) {
        Column(Modifier.verticalScroll(scroll).padding(horizontal = settings.marginDp.dp, vertical = 20.dp)) {
            Text(
                text = title,
                fontSize = (settings.fontSize + 6).sp,
                fontFamily = family,
                color = foreground
            )
            Text(
                text = annotated,
                style = readerTextStyle(settings, family, foreground),
                inlineContent = rendered.inlineContent,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .pointerInput(ord, ranges, lastIndex) {
                        detectTapGestures { pos ->
                            val l = layout ?: return@detectTapGestures
                            val offset = l.getOffsetForPosition(pos)
                            // A reference first — see the same decision in the paged body.
                            val reference = rendered.referenceAt(offset)
                            val hit = ranges.firstOrNull { offset in it.second }
                            when {
                                reference != null -> onReference(reference)
                                hit != null -> onOpenNote(hit.first)
                                else -> {
                                    // Edge tap-zones page too, for readers who never swipe.
                                    val w = size.width.toFloat()
                                    when {
                                        pos.x < w * 0.22f && ord > 0 -> vm.goToChapterEnd(ord - 1)
                                        pos.x > w * 0.78f && ord < lastIndex -> vm.goToChapter(ord + 1)
                                    }
                                }
                            }
                        }
                    }
            )
        }
    }
}
