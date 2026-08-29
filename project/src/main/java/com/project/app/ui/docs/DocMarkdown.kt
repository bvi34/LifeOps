package com.project.app.ui.docs

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.app.logic.ColumnAlign
import com.project.app.logic.MarkdownInline
import com.project.app.logic.MarkdownSpan
import com.project.app.logic.MarkdownTable
import com.project.app.logic.MarkdownTables

/** The annotation tag a link's target is stashed under, so a tap can find it again. */
private const val LINK_TAG = "url"

/**
 * A line of Markdown, drawn as what it says rather than as what it is spelled.
 *
 * `**bold**` reads as bold, `` `code` `` gets the monospaced tint, `~~struck~~` is struck through
 * and `[a link](…)` opens. The parse is a *reading* of the stored text, not a rewrite of it — the
 * asterisks are still in the document, still in the export, and still there when you switch back to
 * editing. Skipped entirely for text with no markup in it, which is most lines in most documents.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = Int.MAX_VALUE
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeColor = MaterialTheme.colorScheme.tertiary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)

    val annotated = remember(text, linkColor, codeColor, codeBackground) {
        annotate(text, linkColor, codeColor, codeBackground)
    }

    if (annotated == null) {
        Text(text, modifier = modifier, style = style, color = color, maxLines = maxLines)
        return
    }

    val uris = LocalUriHandler.current
    var layout by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }
    val hasLinks = remember(annotated) {
        annotated.getStringAnnotations(LINK_TAG, 0, annotated.length).isNotEmpty()
    }

    // Taps are resolved through the layout rather than with a link annotation type, so this reads
    // the same on every Compose version the suite is built against.
    val tappable = if (!hasLinks) modifier else modifier.pointerInput(annotated) {
        detectTapGestures { position ->
            val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
            annotated.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()?.let { link ->
                runCatching { uris.openUri(link.item) }
            }
        }
    }

    Text(
        text = annotated,
        modifier = tappable,
        style = style,
        color = color,
        maxLines = maxLines,
        onTextLayout = { layout = it }
    )
}

/** The styled reading of [text], or null when there is no markup and plain text will do. */
private fun annotate(
    text: String,
    linkColor: Color,
    codeColor: Color,
    codeBackground: Color
): AnnotatedString? {
    if (!MarkdownInline.hasMarkup(text)) return null
    val spans = MarkdownInline.spans(text)
    if (spans.all(MarkdownSpan::isPlain)) return null

    return buildAnnotatedString {
        spans.forEach { span ->
            val start = length
            append(span.text)
            addStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    color = when {
                        span.link != null -> linkColor
                        span.code -> codeColor
                        else -> Color.Unspecified
                    },
                    background = if (span.code) codeBackground else Color.Unspecified,
                    textDecoration = when {
                        span.strike && span.link != null ->
                            TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))
                        span.strike -> TextDecoration.LineThrough
                        span.link != null -> TextDecoration.Underline
                        else -> null
                    }
                ),
                start,
                length
            )
            span.link?.let { addStringAnnotation(LINK_TAG, it, start, length) }
        }
    }
}

/**
 * A table, drawn as a table.
 *
 * Two ways to look at one, because a phone cannot show a seven-column stat block honestly:
 *
 * - **Grid** is the table itself, scrolled sideways, column widths sized to their content. Right
 *   for comparing a column down the page, which is what a table is *for*.
 * - **Cards** turns each row into a labelled list — every value under the column it belongs to.
 *   Right for reading one row, and the only readable option when the table is wide.
 *
 * Which one you get is [cards], owned by the caller so the choice can be remembered rather than
 * re-made on every document. Anything that will not parse as a table falls back to its source in
 * monospace: showing the text you typed is always better than showing nothing.
 */
@Composable
fun MarkdownTableView(
    source: String,
    modifier: Modifier = Modifier,
    cards: Boolean = false
) {
    val table = remember(source) { MarkdownTables.coerce(source) }
    if (table == null || table.columns == 0) {
        CodeSurface(source, modifier)
        return
    }
    if (cards) TableAsCards(table, modifier) else TableAsGrid(table, modifier)
}

/** The table as a grid: header band, ruled rows, banded backgrounds, scrolled sideways. */
@Composable
private fun TableAsGrid(table: MarkdownTable, modifier: Modifier = Modifier) {
    val widths = remember(table) { columnWidths(table) }
    val outline = MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .horizontalScroll(rememberScrollState())
    ) {
        Row(
            Modifier
                .height(IntrinsicSize.Min)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            table.header.forEachIndexed { column, cell ->
                if (column > 0) VerticalDivider(color = outline)
                TableCell(
                    text = cell,
                    align = table.alignmentOf(column),
                    width = widths[column],
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = outline)

        table.rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = outline.copy(alpha = 0.5f))
            Row(
                Modifier
                    .height(IntrinsicSize.Min)
                    // Banding, faintly. Reading across eight columns on a phone is the thing that
                    // goes wrong with tables, and a striped row is the cheapest fix for it.
                    .background(
                        if (index % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        else Color.Transparent
                    )
            ) {
                row.forEachIndexed { column, cell ->
                    if (column > 0) VerticalDivider(color = outline.copy(alpha = 0.5f))
                    TableCell(
                        text = cell,
                        align = table.alignmentOf(column),
                        width = widths[column],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    align: ColumnAlign,
    width: androidx.compose.ui.unit.Dp,
    style: TextStyle,
    color: Color
) {
    Box(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = when (align) {
            ColumnAlign.LEFT -> Alignment.CenterStart
            ColumnAlign.CENTER -> Alignment.Center
            ColumnAlign.RIGHT -> Alignment.CenterEnd
        }
    ) {
        MarkdownText(
            text = text,
            style = style.copy(
                textAlign = when (align) {
                    ColumnAlign.LEFT -> TextAlign.Start
                    ColumnAlign.CENTER -> TextAlign.Center
                    ColumnAlign.RIGHT -> TextAlign.End
                }
            ),
            color = color
        )
    }
}

/** The table as one card per row: every value under the column it came from. */
@Composable
private fun TableAsCards(table: MarkdownTable, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        table.rows.forEach { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // The first column names the row — it is the one people read as the heading, and
                // repeating its own column label above it would be noise.
                row.firstOrNull()?.takeIf { it.isNotBlank() }?.let { lead ->
                    MarkdownText(
                        text = lead,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                row.drop(1).forEachIndexed { index, cell ->
                    if (cell.isBlank()) return@forEachIndexed
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            text = table.header.getOrElse(index + 1) { "" },
                            modifier = Modifier.width(110.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        MarkdownText(
                            text = cell,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * How wide each column is drawn.
 *
 * Sized from the longest cell in the column, clamped at both ends: narrow enough that a column of
 * "Low"/"High" does not eat the screen, wide enough that a heading is not one word per line, and
 * capped so one essay-length cell cannot push the rest of the table off the far side of the scroll.
 */
private fun columnWidths(table: MarkdownTable) = (0 until table.columns).map { column ->
    val longest = (listOf(table.header.getOrElse(column) { "" }) +
        table.rows.map { it.getOrElse(column) { "" } })
        .maxOf { MarkdownInline.plain(it).length }
    (longest.coerceIn(5, 22) * 8.5f).dp
}

/** Code and unparseable tables: the source, monospaced, on its own ground, scrolled sideways. */
@Composable
fun CodeSurface(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A quotation: the vertical rule Markdown's `>` is drawing, rather than a literal `|`. */
@Composable
fun QuoteSurface(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        )
        MarkdownText(
            text = text,
            modifier = Modifier.padding(vertical = 2.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
