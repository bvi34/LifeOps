package com.citation.app.ui.reader

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.citation.core.doc.BlockKind
import com.citation.core.doc.DocumentBlock
import com.citation.core.doc.InlineStyle
import com.citation.core.reader.ParagraphSpacing

/**
 * Turns a chapter's canonical text plus its structure into something Compose can draw — and keeps a
 * two-way map between the two, which is the whole trick.
 *
 * The rendered string is **not** the canonical text. It cannot be: an illustration needs a
 * placeholder character to sit on, a list item needs a bullet, a table needs separators between
 * cells the reduction ran together, and the blank lines between blocks are replaced by paragraph
 * layout. Every one of those is a character the canonical text does not have.
 *
 * That would be fatal if positions were shared, because a note's anchor and a saved reading
 * position are both offsets into the *canonical* text and must stay that way — they were captured
 * before any of this existed, and they have to keep meaning the same thing. So this class carries
 * an exact map in both directions: the renderer, the paginator and the selection handler all work
 * in display offsets, and everything that is stored or anchored is converted back first.
 */
class RenderedChapter(
    val display: AnnotatedString,
    /** Placeholders for [androidx.compose.ui.text.TextMeasurer], which does not read inline content. */
    val placeholders: List<AnnotatedString.Range<Placeholder>>,
    /** Inline content for `Text`, keyed by the ids embedded in [display]. */
    val inlineContent: Map<String, InlineTextContent>,
    private val displayToCanonical: IntArray,
    private val canonicalToDisplay: IntArray
) {

    val length: Int get() = display.length

    /** Where a display offset sits in the chapter's canonical text. */
    fun canonicalOf(offset: Int): Int =
        displayToCanonical[offset.coerceIn(0, displayToCanonical.lastIndex)]

    /** Where a canonical offset sits in the rendered string. */
    fun displayOf(offset: Int): Int =
        canonicalToDisplay[offset.coerceIn(0, canonicalToDisplay.lastIndex)]

    /** A canonical range as a display range, for drawing a highlight over the right words. */
    fun displayRange(range: IntRange): IntRange {
        val start = displayOf(range.first)
        val end = displayOf(range.last + 1)
        return start until maxOf(end, start)
    }

    companion object {
        /** A chapter with no structure at all: the text as it has always been rendered. */
        fun plain(text: String): RenderedChapter {
            val map = IntArray(text.length + 1) { it }
            return RenderedChapter(
                display = AnnotatedString(text),
                placeholders = emptyList(),
                inlineContent = emptyMap(),
                displayToCanonical = map,
                canonicalToDisplay = map.copyOf()
            )
        }
    }
}

/**
 * The reader's live typography, so block styling scales with the user's settings.
 *
 * Carries the setting choices as well as the sizes, because they change how a *block* is built and
 * not only how a run of characters is painted: justification and hyphenation belong to a paragraph,
 * and whether paragraphs are indented, spaced or both changes what separates one from the next.
 */
data class ReaderTypography(
    val fontSize: Float,
    val lineSpacing: Float,
    val family: FontFamily,
    val foreground: Color,
    /** Headings; equal to [foreground] unless the reader set a colour of their own. */
    val heading: Color,
    /** Links and note references — derived against the page, never the app's accent. */
    val accent: Color,
    /** Captions and quotes, set quieter than the prose. */
    val secondary: Color,
    val letterSpacing: Float = 0f,
    val justify: Boolean = false,
    val hyphenate: Boolean = true,
    val paragraphs: ParagraphSpacing = ParagraphSpacing.INDENT
) {
    /**
     * Justification is applied to running prose only.
     *
     * A justified heading, caption or table row stretches a few words across the whole column,
     * which looks like a bug rather than a setting. Verse and code are never justified either —
     * their line breaks are the author's, and stretching them destroys the thing they encode.
     */
    fun alignment(kind: BlockKind): TextAlign? = when {
        !justify -> null
        kind == BlockKind.PARAGRAPH || kind == BlockKind.BLOCKQUOTE -> TextAlign.Justify
        else -> null
    }

    /** Hyphenation goes wherever text is set as prose; it is what keeps justification honest. */
    val hyphens: Hyphens get() = if (hyphenate) Hyphens.Auto else Hyphens.None

    /**
     * Paragraph-level line breaking. Compose's `Paragraph` strategy does the whole-paragraph
     * optimisation that makes hyphenation and justification produce even lines rather than one
     * ragged one followed by a stretched one.
     */
    val lineBreak: LineBreak get() = if (justify || hyphenate) LineBreak.Paragraph else LineBreak.Simple
}

/**
 * Builds the drawable form of a chapter.
 *
 * The typographic choices here are deliberately book choices rather than web ones: paragraphs are
 * separated by a first-line indent instead of a blank line (a blank line between every paragraph is
 * what makes a long novel read like documentation), the paragraph opening a section is not indented,
 * and space is spent on headings, quotes and plates rather than everywhere.
 */
object ChapterRender {

    fun build(
        text: String,
        blocks: List<DocumentBlock>,
        typography: ReaderTypography,
        /** Resolved illustrations, by the block's source reference; missing ones degrade to a caption. */
        images: Map<String, ImageBitmap> = emptyMap(),
        /** Widest an illustration may draw, in the same sp units the text uses. */
        maxImageWidthSp: Float = 0f,
        maxImageHeightSp: Float = 0f
    ): RenderedChapter {
        if (blocks.isEmpty()) return RenderedChapter.plain(text)

        val emitter = Emitter(text.length)
        val inline = HashMap<String, InlineTextContent>()
        val placeholders = ArrayList<AnnotatedString.Range<Placeholder>>()

        val ordered = blocks.sortedWith(compareBy({ it.start }, { if (it.isMarker) 0 else 1 }))
        var cursor = 0
        var previousKind: BlockKind? = null
        var first = true

        ordered.forEach { block ->
            // Text between blocks is the reduction's blank-line separators; paragraph layout
            // replaces them, so they are mapped rather than drawn. Anything else is real content
            // no block claimed, and is set as an ordinary paragraph so it cannot be lost.
            if (block.start > cursor) {
                val gap = text.substring(cursor, block.start)
                if (gap.isNotBlank()) {
                    bodyParagraph(emitter, typography, separated = !first, body = gap, at = cursor)
                    first = false
                }
                cursor = block.start
            }

            when (block) {
                is DocumentBlock.Image -> {
                    val bitmap = images[block.src]
                    if (bitmap != null) {
                        val id = "img-${block.start}"
                        val size = imageSize(bitmap, maxImageWidthSp, maxImageHeightSp, typography.fontSize)
                        placeholderFor(emitter, id, size, block.start, inline, placeholders, bitmap, block.alt)
                    } else if (!block.alt.isNullOrBlank()) {
                        // A plate we could not load is named rather than silently dropped, so the
                        // page still says something was there.
                        emitter.paragraph(captionStyle(typography)) {
                            emitter.synthetic(block.alt!!, block.start)
                        }
                    }
                    previousKind = null
                }

                is DocumentBlock.Rule -> {
                    emitter.paragraph(
                        ParagraphStyle(textAlign = TextAlign.Center, lineHeight = (typography.fontSize * 2.2f).sp)
                    ) {
                        emitter.synthetic(SCENE_BREAK, block.start)
                    }
                    previousKind = null
                }

                is DocumentBlock.Table -> {
                    emitTable(emitter, text, block, typography)
                    cursor = block.end
                    previousKind = null
                }

                is DocumentBlock.Text -> {
                    if (block.end > block.start) {
                        emitTextBlock(emitter, text, block, typography, previousKind, first)
                        cursor = block.end
                        previousKind = block.kind
                        first = false
                    }
                }
            }
        }

        if (cursor < text.length) {
            val tail = text.substring(cursor, text.length)
            if (tail.isNotBlank()) {
                bodyParagraph(emitter, typography, separated = !first, body = tail, at = cursor)
            }
        }

        // Inline emphasis last, so it layers over whatever block styling was applied.
        ordered.filterIsInstance<DocumentBlock.Text>().forEach { block ->
            block.spans.forEach { span ->
                val start = emitter.displayOf(span.start)
                val end = emitter.displayOf(span.end)
                if (end > start) {
                    spanStyle(span.style, typography)?.let { emitter.builder.addStyle(it, start, end) }
                }
            }
        }

        return emitter.finish(placeholders, inline)
    }

    // --- Block emission ---------------------------------------------------------------------------

    private fun emitTextBlock(
        emitter: Emitter,
        text: String,
        block: DocumentBlock.Text,
        typography: ReaderTypography,
        previousKind: BlockKind?,
        first: Boolean
    ) {
        val body = text.substring(block.start, block.end)
        when (block.kind) {
            BlockKind.HEADING -> {
                emitter.paragraph(
                    ParagraphStyle(
                        textAlign = if (block.level <= 1) TextAlign.Center else TextAlign.Start,
                        lineHeight = (typography.fontSize * headingScale(block.level) * 1.25f).sp
                    )
                ) {
                    // A heading that opens the page needs no space above it.
                    if (!first) emitter.synthetic("\n", block.start)
                    val from = emitter.displayLength
                    emitter.real(body, block.start)
                    emitter.builder.addStyle(
                        SpanStyle(
                            fontSize = (typography.fontSize * headingScale(block.level)).sp,
                            fontWeight = FontWeight.SemiBold,
                            color = typography.heading
                        ),
                        from, emitter.displayLength
                    )
                }
            }

            BlockKind.BLOCKQUOTE -> {
                emitter.paragraph(
                    ParagraphStyle(
                        textIndent = TextIndent(firstLine = QUOTE_INDENT.sp, restLine = QUOTE_INDENT.sp),
                        textAlign = typography.alignment(BlockKind.BLOCKQUOTE) ?: TextAlign.Unspecified,
                        lineHeight = (typography.fontSize * typography.lineSpacing).sp,
                        hyphens = typography.hyphens,
                        lineBreak = typography.lineBreak
                    )
                ) {
                    val from = emitter.displayLength
                    emitter.real(body, block.start)
                    emitter.builder.addStyle(
                        SpanStyle(fontSize = (typography.fontSize * 0.95f).sp, color = typography.secondary),
                        from, emitter.displayLength
                    )
                }
            }

            // Verse and code both mean "the line breaks are the author's" — they are emitted
            // verbatim, with wrapped lines indented so a wrap is visibly not a new line.
            BlockKind.VERSE -> {
                emitter.paragraph(
                    ParagraphStyle(
                        textIndent = TextIndent(firstLine = QUOTE_INDENT.sp, restLine = (QUOTE_INDENT * 2).sp),
                        lineHeight = (typography.fontSize * typography.lineSpacing).sp
                    )
                ) { emitter.real(body, block.start) }
            }

            BlockKind.CODE -> {
                emitter.paragraph(
                    ParagraphStyle(lineHeight = (typography.fontSize * 1.35f).sp)
                ) {
                    val from = emitter.displayLength
                    emitter.real(body, block.start)
                    emitter.builder.addStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, fontSize = (typography.fontSize * 0.85f).sp),
                        from, emitter.displayLength
                    )
                }
            }

            BlockKind.LIST_ITEM -> {
                emitter.paragraph(
                    ParagraphStyle(
                        textIndent = TextIndent(firstLine = (QUOTE_INDENT * block.level.coerceAtLeast(1)).sp, restLine = (QUOTE_INDENT * (block.level + 1)).sp),
                        lineHeight = (typography.fontSize * typography.lineSpacing).sp
                    )
                ) {
                    val marker = if (block.ordered) "${block.itemIndex}. " else "• "
                    emitter.synthetic(marker, block.start)
                    emitter.real(body, block.start)
                }
            }

            BlockKind.CAPTION -> {
                emitter.paragraph(captionStyle(typography)) {
                    val from = emitter.displayLength
                    emitter.real(body, block.start)
                    emitter.builder.addStyle(
                        SpanStyle(fontSize = (typography.fontSize * 0.85f).sp, color = typography.secondary),
                        from, emitter.displayLength
                    )
                }
            }

            BlockKind.PARAGRAPH -> {
                // Nothing separates the first paragraph of a section from the heading above it —
                // neither an indent nor a blank line. That is the convention every printed book
                // follows, and the reason indented paragraphs don't look like a mistake.
                val separated = !first && previousKind != BlockKind.HEADING
                bodyParagraph(emitter, typography, separated, body, block.start)
            }
        }
    }

    /**
     * A table, laid out as rows of separated cells.
     *
     * The canonical text runs cells together with no separator — it always has, and changing that
     * would move every anchor in every table ever captured — so the separators are synthetic
     * characters that exist only in the rendered form. The grid comes from the block's cell ranges.
     */
    private fun emitTable(
        emitter: Emitter,
        text: String,
        table: DocumentBlock.Table,
        typography: ReaderTypography
    ) {
        table.rows.forEach { row ->
            emitter.paragraph(
                ParagraphStyle(
                    textIndent = TextIndent(restLine = QUOTE_INDENT.sp),
                    lineHeight = (typography.fontSize * typography.lineSpacing).sp
                )
            ) {
                val from = emitter.displayLength
                if (row.cells.isEmpty()) {
                    emitter.real(text.substring(row.start, row.end), row.start)
                } else {
                    row.cells.forEachIndexed { index, cell ->
                        if (index > 0) emitter.synthetic(CELL_SEPARATOR, cell.first)
                        val start = cell.first.coerceIn(0, text.length)
                        val end = (cell.last + 1).coerceIn(start, text.length)
                        emitter.real(text.substring(start, end), start)
                    }
                }
                val style = SpanStyle(
                    fontSize = (typography.fontSize * 0.9f).sp,
                    fontWeight = if (row.header) FontWeight.SemiBold else FontWeight.Normal
                )
                emitter.builder.addStyle(style, from, emitter.displayLength)
            }
        }
    }

    private fun placeholderFor(
        emitter: Emitter,
        id: String,
        size: Pair<TextUnit, TextUnit>,
        canonicalAt: Int,
        inline: MutableMap<String, InlineTextContent>,
        placeholders: MutableList<AnnotatedString.Range<Placeholder>>,
        bitmap: ImageBitmap,
        alt: String?
    ) {
        val placeholder = Placeholder(size.first, size.second, PlaceholderVerticalAlign.Center)
        emitter.paragraph(ParagraphStyle(textAlign = TextAlign.Center)) {
            val from = emitter.displayLength
            emitter.builder.appendInlineContent(id, PLACEHOLDER_CHAR)
            emitter.mapSynthetic(PLACEHOLDER_CHAR.length, canonicalAt)
            placeholders.add(AnnotatedString.Range(placeholder, from, emitter.displayLength))
        }
        inline[id] = InlineTextContent(placeholder) {
            ChapterImage(bitmap = bitmap, contentDescription = alt)
        }
    }

    /** Fit an illustration to the page without upscaling it past its own resolution. */
    private fun imageSize(
        bitmap: ImageBitmap,
        maxWidthSp: Float,
        maxHeightSp: Float,
        fontSize: Float
    ): Pair<TextUnit, TextUnit> {
        val maxWidth = if (maxWidthSp > 0) maxWidthSp else fontSize * 20
        val maxHeight = if (maxHeightSp > 0) maxHeightSp else fontSize * 20
        val ratio = if (bitmap.width > 0) bitmap.height.toFloat() / bitmap.width else 1f
        var width = maxWidth
        var height = width * ratio
        if (height > maxHeight) {
            height = maxHeight
            width = height / ratio
        }
        return width.sp to height.sp
    }

    /**
     * One paragraph of running prose, carrying whichever marks of a paragraph break the reader asked
     * for — see [paragraphStyle].
     *
     * In one place because the marks belong together and body paragraphs arrive from more than one:
     * the ones a document's structure declares, and the runs of text no block claimed, which are set
     * as prose so they cannot be lost. A blank line that reached only the first of those is how a
     * spaced page ends up with two paragraphs run together in the middle of a chapter.
     *
     * [separated] is false for the paragraph that opens a section, which carries no mark at all.
     */
    private fun bodyParagraph(
        emitter: Emitter,
        typography: ReaderTypography,
        separated: Boolean,
        body: String,
        /** Where [body] starts in the canonical text, so every character maps back to it. */
        at: Int
    ) {
        emitter.paragraph(paragraphStyle(typography, separated)) {
            if (separated && typography.paragraphs.spaces) emitter.synthetic("\n", at)
            emitter.real(body, at)
        }
    }

    // --- Styles -----------------------------------------------------------------------------------

    /**
     * Body-paragraph setting.
     *
     * The two marks a paragraph break can carry are independent, and [ParagraphSpacing] says which
     * of them this reader wants: the first line indented (the printed convention, and what makes a
     * novel read like a novel), a blank line between paragraphs (the web's, and easier on some
     * readers at large type), or both — the indent alone leaves the eye nowhere to rest at a tight
     * line spacing, and the blank line alone loses the mark that says where a paragraph begins.
     *
     * [separated] is false for the paragraph that opens a section, which carries neither mark.
     *
     * The blank line is a synthetic character, mapped like every other one the renderer inserts, so
     * it cannot move an anchor.
     */
    private fun paragraphStyle(typography: ReaderTypography, separated: Boolean) = ParagraphStyle(
        textIndent = if (separated && typography.paragraphs.indents) {
            TextIndent(firstLine = 1.3.em)
        } else {
            TextIndent.None
        },
        textAlign = typography.alignment(BlockKind.PARAGRAPH) ?: TextAlign.Unspecified,
        lineHeight = (typography.fontSize * typography.lineSpacing).sp,
        hyphens = typography.hyphens,
        lineBreak = typography.lineBreak
    )

    private fun captionStyle(typography: ReaderTypography) = ParagraphStyle(
        textAlign = TextAlign.Center,
        lineHeight = (typography.fontSize * typography.lineSpacing).sp
    )

    private fun headingScale(level: Int): Float = when (level) {
        1 -> 1.55f
        2 -> 1.32f
        3 -> 1.15f
        else -> 1.05f
    }

    private fun spanStyle(style: InlineStyle, typography: ReaderTypography): SpanStyle? = when (style) {
        InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        InlineStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = (typography.fontSize * 0.9f).sp)
        InlineStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
        InlineStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        InlineStyle.SUPERSCRIPT -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = (typography.fontSize * 0.75f).sp)
        InlineStyle.SUBSCRIPT -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = (typography.fontSize * 0.75f).sp)
        // Compose has no small-caps; the nearest honest approximation is letter-spaced small text.
        InlineStyle.SMALL_CAPS -> SpanStyle(fontSize = (typography.fontSize * 0.88f).sp, letterSpacing = 0.08.em)
        InlineStyle.LINK -> SpanStyle(color = typography.accent, textDecoration = TextDecoration.Underline)
        // A note reference is a marker, not a link to follow away from the sentence.
        InlineStyle.FOOTNOTE_REF -> SpanStyle(
            color = typography.accent,
            baselineShift = BaselineShift.Superscript,
            fontSize = (typography.fontSize * 0.75f).sp
        )
    }

    private const val PLACEHOLDER_CHAR = "\uFFFD"
    private const val SCENE_BREAK = "· · ·"
    private const val CELL_SEPARATOR = "   ·   "
    private const val QUOTE_INDENT = 18f

    /**
     * Accumulates the rendered string while recording, for every character, which canonical offset
     * it came from.
     *
     * Two kinds of character go in. **Real** ones are the chapter's own text and carry their true
     * canonical position, which is what makes selection and highlighting exact. **Synthetic** ones
     * — bullets, cell separators, the image placeholder, spacing — have no canonical existence, so
     * they are pinned to the offset they sit at. Only real characters seed the reverse map, so a
     * canonical position always resolves to the actual character rather than to a bullet in front
     * of it.
     */
    private class Emitter(private val canonicalLength: Int) {
        val builder = AnnotatedString.Builder()
        private val toCanonical = ArrayList<Int>()
        private val reverse = HashMap<Int, Int>()

        val displayLength: Int get() = toCanonical.size

        fun paragraph(style: ParagraphStyle, body: () -> Unit) {
            builder.pushStyle(style)
            body()
            builder.pop()
        }

        fun real(value: String, canonicalStart: Int) {
            value.indices.forEach { i ->
                reverse.putIfAbsent(canonicalStart + i, toCanonical.size)
                toCanonical.add(canonicalStart + i)
            }
            builder.append(value)
        }

        fun synthetic(value: String, canonicalAt: Int) {
            repeat(value.length) { toCanonical.add(canonicalAt) }
            builder.append(value)
        }

        /** Record a run already appended to the builder (inline content) as synthetic. */
        fun mapSynthetic(count: Int, canonicalAt: Int) {
            repeat(count) { toCanonical.add(canonicalAt) }
        }

        fun displayOf(canonical: Int): Int = reverse[canonical] ?: displayLength

        fun finish(
            placeholders: List<AnnotatedString.Range<Placeholder>>,
            inline: Map<String, InlineTextContent>
        ): RenderedChapter {
            val display = builder.toAnnotatedString()
            val d2c = IntArray(toCanonical.size + 1)
            toCanonical.forEachIndexed { i, c -> d2c[i] = c }
            d2c[toCanonical.size] = canonicalLength

            // Canonical positions with no character of their own — a skipped separator, a stretch
            // that was not drawn — resolve forward to the next position that has one, so a
            // conversion always lands somewhere real rather than at zero.
            val c2d = IntArray(canonicalLength + 1)
            var next = display.length
            for (c in canonicalLength downTo 0) {
                val at = reverse[c]
                if (at == null) {
                    c2d[c] = next
                } else {
                    c2d[c] = at
                    next = at
                }
            }

            return RenderedChapter(display, placeholders, inline, d2c, c2d)
        }
    }
}
