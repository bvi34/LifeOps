package com.citation.core.doc

/**
 * The **structured view** of a chapter, layered over its canonical flowing text.
 *
 * Citation's anchoring contract is that a chapter's plain `text` is the one stable surface notes
 * hang off: a frozen snapshot captured today must resolve against the same offsets tomorrow. That
 * ruled out ever *replacing* the text reduction with a rich document tree — so structure is
 * expressed as **ranges into that unchanged text** instead. Every block names a `[start, end)` of
 * the chapter's canonical text; nothing here inserts, removes, or reorders a single character.
 *
 * The consequence is the whole point: the reader can render headings, emphasis, block quotes,
 * verse, code, lists, tables and images, and an anchor captured before any of that existed still
 * resolves to exactly the same words. Structure is *additive*.
 *
 * Blocks tile the text in order and never overlap, with one exception: [Image] and [Rule] are
 * **zero-width markers** (`start == end`) that sit *between* characters, because an image
 * contributes no text and must not shift the offsets around it.
 */
sealed class DocumentBlock {

    /** First character of this block in the chapter's canonical text. */
    abstract val start: Int

    /** One past this block's last character; equals [start] for a zero-width marker. */
    abstract val end: Int

    /** Whether this block occupies no characters (an image or a rule sitting between them). */
    val isMarker: Boolean get() = end == start

    /**
     * A run of readable text — the common case. [kind] says how to set it, [spans] carry the
     * inline emphasis/links found inside it (also as text ranges).
     */
    data class Text(
        override val start: Int,
        override val end: Int,
        val kind: BlockKind = BlockKind.PARAGRAPH,
        /** Heading level 1..6 when [kind] is [BlockKind.HEADING]; list nesting depth for a list item. */
        val level: Int = 0,
        /** For [BlockKind.LIST_ITEM]: whether the enclosing list is numbered. */
        val ordered: Boolean = false,
        /** For [BlockKind.LIST_ITEM]: 1-based position within its list, for numbering. */
        val itemIndex: Int = 0,
        val spans: List<InlineSpan> = emptyList()
    ) : DocumentBlock()

    /**
     * An illustration. Zero-width: it sits at [start] without owning any characters, so adding
     * image support to a book you had already annotated cannot move a single anchor.
     *
     * @property src the source's own reference (an EPUB-relative href), resolved to a real file by
     *   the storage layer at import; opaque here so `:core` stays framework-free.
     */
    data class Image(
        override val start: Int,
        val src: String,
        val alt: String? = null
    ) : DocumentBlock() {
        override val end: Int get() = start
    }

    /** A scene break / horizontal rule. Zero-width, for the same reason as [Image]. */
    data class Rule(override val start: Int) : DocumentBlock() {
        override val end: Int get() = start
    }

    /**
     * A table. The canonical reduction runs cells together without a separator (it always has —
     * changing that would move every anchor in every table), so the *text* is unchanged and the
     * grid is carried here as ranges: the renderer lays cells out in columns while notes still
     * anchor into the same flat characters.
     */
    data class Table(
        override val start: Int,
        override val end: Int,
        val rows: List<Row>
    ) : DocumentBlock() {

        /** One row: its own range plus the ranges of the cells inside it. */
        data class Row(
            val start: Int,
            val end: Int,
            val cells: List<IntRange>,
            val header: Boolean = false
        )
    }
}

/** How a [DocumentBlock.Text] run should be set. */
enum class BlockKind {
    /** Ordinary body text. */
    PARAGRAPH,

    /** A section/chapter heading; the `level` field carries 1..6. */
    HEADING,

    /** A quotation set off from the body. */
    BLOCKQUOTE,

    /**
     * Poetry or anything else whose line breaks are meaningful. The renderer must honour the
     * single newlines inside the run rather than reflowing them.
     */
    VERSE,

    /** Preformatted / source code: monospaced, breaks preserved, never justified. */
    CODE,

    /** An item in a bulleted or numbered list. */
    LIST_ITEM,

    /** A figure/table caption. */
    CAPTION
}

/**
 * Inline formatting inside a [DocumentBlock.Text] run, again as a range into the canonical text.
 *
 * @property href the destination for [InlineStyle.LINK] / [InlineStyle.FOOTNOTE_REF]; an
 *   EPUB-relative href (possibly a bare `#fragment`), resolved against the book's anchor index.
 */
data class InlineSpan(
    val start: Int,
    val end: Int,
    val style: InlineStyle,
    val href: String? = null
)

/** The inline treatments the reduction can recover from source markup. */
enum class InlineStyle {
    ITALIC,
    BOLD,
    CODE,
    UNDERLINE,
    STRIKETHROUGH,
    SUPERSCRIPT,
    SUBSCRIPT,
    SMALL_CAPS,

    /** An ordinary hyperlink (internal or external). */
    LINK,

    /**
     * A note reference — an EPUB `epub:type="noteref"` link, or a superscripted link into the same
     * book. Kept distinct from [LINK] so the reader can pop the note inline instead of navigating
     * away mid-sentence.
     */
    FOOTNOTE_REF
}
