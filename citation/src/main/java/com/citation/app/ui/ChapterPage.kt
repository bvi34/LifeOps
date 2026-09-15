package com.citation.app.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.citation.app.data.bookAsset
import com.citation.app.ui.reader.ChapterRender
import com.citation.app.ui.reader.ReaderTypography
import com.citation.app.ui.reader.rememberChapterImages
import com.citation.core.anchor.FuzzyAnchor
import com.citation.core.anchor.TextAnchor
import com.citation.core.model.Book
import com.citation.core.note.HighlightColor
import com.citation.core.note.Note
import com.citation.core.reader.ReaderColors
import com.citation.core.reader.ReaderPalette
import com.citation.core.reader.ReaderSettings
import kotlinx.coroutines.flow.first

/**
 * One chapter, and the choice of how to lay it out: scrolled or paged.
 *
 * The text style and the highlight shading live here because both bodies draw from them, and two
 * chapters that disagree about line height are two chapters that disagree about where a note is.
 */

/**
 * One chapter's flowing text, with your highlights drawn back into it. Two reading modes share this
 * entry point: **paged** (the default — the chapter is split into screen-pages you turn one at a time,
 * so page turns work *within* a chapter, not only at its boundaries) and **scroll** (one continuous
 * column). Both resolve the same highlights and feed the same capture/hint machinery; only the body
 * differs, so a note lit up in one mode lights up in the other.
 */
@Composable
internal fun ChapterPage(
    vm: ReaderViewModel,
    book: Book,
    ord: Int,
    highlights: List<Note>,
    /** Canonical ranges of the live search's matches in this chapter, lit while a search is open. */
    searchRanges: List<IntRange>,
    settings: ReaderSettings,
    family: FontFamily,
    /** Every colour the page is drawn in — the page itself included, since a mark is derived against it. */
    colours: ReaderColors,
    turnThreshold: Float,
    onOpenNote: (Note) -> Unit,
    onProvideHint: (() -> Int) -> Unit
) {
    val foreground = Color(colours.text)
    val background = Color(colours.page)
    val chapter = book.chapterAt(ord)
    val text = chapter?.text ?: "(chapter unavailable)"
    val title = chapter?.title ?: ""
    val lastIndex = book.chapters.lastIndex
    // A highlight is not drawn on a fixed page, so it cannot be a fixed colour: a translucent tint
    // that reads as a highlighter on white goes muddy on a night page and near-invisible on one the
    // reader tinted themselves. Each colour is resolved against the page it will actually sit on and
    // against the text that sits on top of it — see ReaderPalette.highlight.
    val shadeOf = remember(background, foreground) {
        { color: HighlightColor ->
            Color(ReaderPalette.highlight(color.tint, background.toArgb(), foreground.toArgb()))
        }
    }
    // A different colour from a highlight on purpose: a search match is transient and not yours.
    val searchColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.40f)
    val bookKey = book.key?.toString()

    // Size illustrations to the text column. sp rather than dp because the placeholder the renderer
    // reserves is measured in text units, so a plate scales with the reader's font setting like
    // everything else on the page.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val columnWidthDp = (configuration.screenWidthDp - settings.marginDp * 2).coerceAtLeast(80f)
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
    val rendered = remember(text, blocks, settings, family, colours, images, imageWidthSp) {
        ChapterRender.build(
            text = text,
            blocks = blocks,
            typography = ReaderTypography(
                fontSize = settings.fontSize,
                lineSpacing = settings.lineSpacing,
                family = family,
                foreground = foreground,
                heading = Color(colours.heading),
                accent = Color(colours.link),
                secondary = Color(colours.secondary),
                letterSpacing = settings.letterSpacing,
                justify = settings.justify,
                hyphenate = settings.hyphenate,
                paragraphs = settings.paragraphs
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
    // Search matches are canonical ranges like an anchor's, so they convert the same way.
    val searchDisplayRanges = remember(rendered, searchRanges) {
        searchRanges.map { rendered.displayRange(it) }.filter { !it.isEmpty() }
    }
    val annotated = remember(rendered, ranges, searchDisplayRanges, shadeOf, searchColor) {
        if (ranges.isEmpty() && searchDisplayRanges.isEmpty()) {
            rendered.display
        } else {
            buildAnnotatedString {
                append(rendered.display)
                ranges.forEach { (note, range) -> shade(range, shadeOf(note.highlightColor)) }
                searchDisplayRanges.forEach { range -> shade(range, searchColor) }
            }
        }
    }

    if (settings.paged) {
        PagedChapterBody(
            vm, ord, lastIndex, rendered, annotated, title, ranges,
            settings, family, foreground, turnThreshold, onOpenNote, onProvideHint
        )
    } else {
        ScrollChapterBody(
            vm, ord, lastIndex, title, rendered, annotated, ranges,
            settings, family, foreground, turnThreshold, onOpenNote, onProvideHint
        )
    }
}

/**
 * The reader's text style.
 *
 * Shared by both reading modes deliberately: the paged mode *measures* with this style to decide
 * where pages break, and then draws with it. If measuring and drawing could disagree about
 * hyphenation or justification, pages would break in places the drawn text does not — which reads
 * as text mysteriously clipped at the bottom of a page.
 */
internal fun readerTextStyle(
    settings: ReaderSettings,
    family: FontFamily,
    foreground: Color
): TextStyle = TextStyle(
    fontSize = settings.fontSize.sp,
    lineHeight = (settings.fontSize * settings.lineSpacing).sp,
    fontFamily = family,
    color = foreground,
    letterSpacing = settings.letterSpacing.em,
    textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Unspecified,
    hyphens = if (settings.hyphenate) Hyphens.Auto else Hyphens.None,
    lineBreak = if (settings.justify || settings.hyphenate) LineBreak.Paragraph else LineBreak.Simple
)

/** Shade a display range, clipped to the string being built. */
internal fun androidx.compose.ui.text.AnnotatedString.Builder.shade(range: IntRange, color: Color) {
    val start = range.first.coerceIn(0, length)
    val end = (range.last + 1).coerceIn(start, length)
    if (end > start) addStyle(SpanStyle(background = color), start, end)
}
