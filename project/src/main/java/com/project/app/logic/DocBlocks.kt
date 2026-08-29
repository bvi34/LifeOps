package com.project.app.logic

/**
 * The kinds of block a document is made of.
 *
 * A short, closed list on purpose. The temptation with a block editor is to keep adding types until
 * every document is a different application; what a planning repository actually needs is prose,
 * structure, lists, the two kinds of set-apart text, and a rule. [marker] is the Markdown the block
 * round-trips through, which is what makes the whole store exportable and paste-able rather than a
 * private format nobody else can read.
 */
enum class BlockType(val key: String, val label: String, val marker: String) {
    PARAGRAPH("paragraph", "Text", ""),
    HEADING1("h1", "Heading", "# "),
    HEADING2("h2", "Subheading", "## "),
    HEADING3("h3", "Small heading", "### "),
    BULLET("bullet", "Bulleted list", "- "),
    NUMBERED("numbered", "Numbered list", "1. "),
    TODO("todo", "To-do", "- [ ] "),
    QUOTE("quote", "Quote", "> "),
    CODE("code", "Code", "```"),
    TABLE("table", "Table", ""),
    DIVIDER("divider", "Divider", "---");

    val isHeading: Boolean get() = this == HEADING1 || this == HEADING2 || this == HEADING3

    /** 1, 2 or 3 for headings; 0 for everything else. */
    val headingLevel: Int
        get() = when (this) {
            HEADING1 -> 1
            HEADING2 -> 2
            HEADING3 -> 3
            else -> 0
        }

    /** Whether the block holds prose that should be counted towards a word target. */
    val isProse: Boolean get() = this != CODE && this != TABLE && this != DIVIDER

    /** Whether the block's text is more than one line, and so wants a taller field to edit in. */
    val isMultiline: Boolean get() = this == CODE || this == TABLE

    companion object {
        fun fromKey(key: String?): BlockType = entries.firstOrNull { it.key == key } ?: PARAGRAPH
    }
}

/** One block. [checked] is meaningful only for [BlockType.TODO]. */
data class DocBlock(
    val id: String,
    val type: BlockType,
    val text: String,
    val checked: Boolean = false
)

/** A heading, for a document's own table of contents. */
data class DocHeading(val level: Int, val text: String, val blockId: String)

/**
 * Documents as blocks, and the Markdown they come from and go back to.
 *
 * The store is blocks — that is what an editor needs to reorder, retype and check off a line
 * without reparsing a whole file. But the *interchange* is Markdown, in both directions:
 * [parse] turns pasted text into blocks and [render] turns blocks back into text. Keeping that
 * round trip honest is what makes this a document repository rather than a database with opinions:
 * anything you paste in comes back out, and anything you write here can leave.
 *
 * Everything here is pure and line-oriented. Emphasis inside a line is not this object's business
 * and is never rewritten into the store: the asterisks you typed stay in the text, and `MarkdownInline`
 * reads them at draw time. That split is what keeps the round trip honest — a block editor that
 * silently ate the literal `*` in a shell command would be worse than one that showed it — while
 * still letting a reader see bold as bold.
 */
object DocBlocks {

    private val NUMBERED_PREFIX = Regex("""^\s*\d+[.)]\s+""")
    private val TODO_PREFIX = Regex("""^\s*[-*]\s+\[([ xX])]\s?""")
    private val BULLET_PREFIX = Regex("""^\s*[-*+]\s+""")
    private val DIVIDER_LINE = Regex("""^\s*([-*_])\1{2,}\s*$""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Parse [text] into blocks.
     *
     * [idFor] mints an id per block — passed in rather than generated here so this stays free of
     * `UUID` and therefore deterministic under test.
     *
     * Consecutive plain lines join into one paragraph, which is the Markdown convention and the
     * reason pasting a hard-wrapped chapter gives you paragraphs rather than sixty one-line blocks.
     * A blank line ends whatever was being built.
     */
    fun parse(text: String, idFor: (Int) -> String): List<DocBlock> {
        val out = ArrayList<DocBlock>()
        val paragraph = StringBuilder()
        var index = 0

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                out += DocBlock(idFor(index++), BlockType.PARAGRAPH, paragraph.toString().trim())
            }
            paragraph.setLength(0)
        }

        fun emit(type: BlockType, body: String, checked: Boolean = false) {
            flushParagraph()
            out += DocBlock(idFor(index++), type, body.trim(), checked)
        }

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                MarkdownTables.startsAt(lines, i) -> {
                    // A table is one block holding its own source. Its rows are not paragraphs, and
                    // joining them the way prose is joined is exactly how a stat block becomes a
                    // grey wall of pipes.
                    var end = i + 2
                    while (end < lines.size && lines[end].contains('|') && lines[end].isNotBlank()) end++
                    val source = lines.subList(i, end).joinToString("\n")
                    flushParagraph()
                    val table = MarkdownTables.parse(source)
                    out += DocBlock(idFor(index++), BlockType.TABLE, table?.render() ?: source)
                    i = end - 1
                }

                MarkdownTables.isFlattened(line) -> {
                    // A table that lost its line breaks somewhere upstream — in a copy-paste, or in
                    // a document written before tables were blocks. The pipes survived, so the
                    // shape can be put back rather than pasted in as a paragraph of punctuation.
                    flushParagraph()
                    val table = MarkdownTables.recover(line)
                    out += DocBlock(
                        idFor(index++),
                        if (table == null) BlockType.PARAGRAPH else BlockType.TABLE,
                        table?.render() ?: line.trim()
                    )
                }

                line.trimStart().startsWith("```") -> {
                    // A fence runs to its closing fence, or to the end of the text if it never
                    // closes — an unterminated fence is a typo, not a reason to lose the code.
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        body.appendLine(lines[i])
                        i++
                    }
                    flushParagraph()
                    out += DocBlock(idFor(index++), BlockType.CODE, body.toString().trimEnd('\n'))
                }

                DIVIDER_LINE.matches(line) -> emit(BlockType.DIVIDER, "")
                line.startsWith("### ") -> emit(BlockType.HEADING3, line.removePrefix("### "))
                line.startsWith("## ") -> emit(BlockType.HEADING2, line.removePrefix("## "))
                line.startsWith("# ") -> emit(BlockType.HEADING1, line.removePrefix("# "))
                line.startsWith("> ") || line.trim() == ">" -> emit(BlockType.QUOTE, line.removePrefix(">"))

                TODO_PREFIX.containsMatchIn(line) -> {
                    val match = TODO_PREFIX.find(line)!!
                    val checked = !match.groupValues[1].equals(" ", ignoreCase = false)
                    emit(BlockType.TODO, line.removeRange(match.range), checked)
                }

                NUMBERED_PREFIX.containsMatchIn(line) ->
                    emit(BlockType.NUMBERED, NUMBERED_PREFIX.replace(line, ""))

                BULLET_PREFIX.containsMatchIn(line) ->
                    emit(BlockType.BULLET, BULLET_PREFIX.replace(line, ""))

                line.isBlank() -> flushParagraph()

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
            i++
        }
        flushParagraph()
        return out
    }

    /**
     * Blocks back to Markdown. Numbered lists are renumbered from 1 within each run, so a list that
     * had an item deleted out of its middle does not export as 1, 2, 4.
     */
    fun render(blocks: List<DocBlock>): String {
        val out = StringBuilder()
        val numbering = ordinals(blocks)
        blocks.forEachIndexed { index, block ->
            val ordinal = numbering[index]
            val line = when (block.type) {
                BlockType.PARAGRAPH -> block.text
                BlockType.DIVIDER -> "---"
                BlockType.CODE -> "```\n${block.text}\n```"
                // Re-rendered rather than echoed, so a hand-edited table exports lined up.
                BlockType.TABLE -> MarkdownTables.parse(block.text)?.render() ?: block.text
                BlockType.NUMBERED -> "$ordinal. ${block.text}"
                BlockType.TODO -> "- [${if (block.checked) "x" else " "}] ${block.text}"
                else -> "${block.type.marker}${block.text}"
            }
            out.append(line).append("\n\n")
        }
        return out.toString().trimEnd('\n')
    }

    /**
     * A block's text as it *reads* — no emphasis markers, no table scaffolding.
     *
     * What search should match against and what a snippet should show. Searching the stored text
     * instead means "left" misses `**left**`, and a hit inside a table shows up as a row of pipes.
     * Code is the exception and keeps every character: in code the punctuation *is* the content.
     */
    fun plainText(block: DocBlock): String = when (block.type) {
        BlockType.CODE -> block.text
        BlockType.TABLE -> MarkdownTables.parse(block.text)
            ?.cells()?.filter { it.isNotBlank() }?.joinToString(" ")
            ?: block.text
        else -> MarkdownInline.plain(block.text)
    }

    /**
     * Words of prose in these blocks.
     *
     * Code and dividers are excluded, and that is the point of counting here rather than over the
     * raw text: a document whose word count jumps by four hundred because you pasted a config file
     * is a word count nobody trusts again. Headings *are* counted — they are words you wrote.
     */
    fun wordCount(blocks: List<DocBlock>): Int =
        blocks.sumOf { block ->
            when (block.type) {
                // A table's *content* counts; its scaffolding does not. Counting the raw source
                // would have every `|` and `---` in a stat block read as a word you wrote.
                BlockType.TABLE -> MarkdownTables.parse(block.text)
                    ?.cells()?.sumOf { wordCount(it) }
                    ?: 0
                else -> if (block.type.isProse) wordCount(block.text) else 0
            }
        }

    /** Words in a single piece of text, whitespace-separated. */
    fun wordCount(text: String): Int =
        WHITESPACE.split(text.trim()).count { it.isNotBlank() }

    /**
     * The number each block shows in front of it, or 0 for the blocks that show none.
     *
     * Numbered lists restart at 1 after anything that is not one, which is the same rule [render]
     * exports by — so a list reads on screen exactly as it reads in the Markdown it produces.
     */
    fun ordinals(blocks: List<DocBlock>): List<Int> {
        var ordinal = 0
        return blocks.map { block ->
            if (block.type == BlockType.NUMBERED) ++ordinal else { ordinal = 0; 0 }
        }
    }

    /** The document's own contents list. */
    fun headings(blocks: List<DocBlock>): List<DocHeading> =
        blocks.filter { it.type.isHeading }
            .map { DocHeading(it.type.headingLevel, it.text, it.id) }

    /** Whether any block here is a table that lost its line breaks and needs putting back. */
    fun hasFlattenedTables(blocks: List<DocBlock>): Boolean =
        blocks.any { it.type != BlockType.TABLE && MarkdownTables.isFlattened(it.text) }

    /** How many of the to-dos in this document are ticked, as done-of-total. */
    fun todoProgress(blocks: List<DocBlock>): Pair<Int, Int> {
        val todos = blocks.filter { it.type == BlockType.TODO }
        return todos.count { it.checked } to todos.size
    }

    /**
     * The first line of prose, for a document list. Headings are skipped: a card that reads
     * "Chapter One" under a document already called "Chapter One" says nothing.
     */
    fun preview(blocks: List<DocBlock>, maxChars: Int = 120): String {
        val prose = blocks.firstOrNull { it.type == BlockType.PARAGRAPH && it.text.isNotBlank() }?.text
            ?: blocks.firstOrNull { it.type.isProse && it.text.isNotBlank() }?.text
        // A document that is nothing but a table still has something to say about itself: its
        // columns. Better than the blank card a prose-only preview would leave.
        val text = prose
            ?: blocks.firstOrNull { it.type == BlockType.TABLE }
                ?.let { MarkdownTables.parse(it.text)?.header?.filter(String::isNotBlank) }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(" · ")
            ?: return ""
        val plain = MarkdownInline.plain(text)
        return if (plain.length <= maxChars) plain else plain.take(maxChars).trimEnd() + "…"
    }

    /**
     * Turn one block into another type, converting its text where the two disagree about shape.
     *
     * Only tables need this, and they need it badly: "turn this into a table" is the repair for a
     * document written before tables were blocks, where a whole stat block sits flattened inside one
     * paragraph. [MarkdownTables.coerce] puts the rows back. Everything else keeps its text, because
     * a heading that becomes a paragraph is the same words either way.
     */
    fun retype(block: DocBlock, type: BlockType): DocBlock = when {
        type == block.type -> block
        type == BlockType.TABLE ->
            block.copy(type = type, text = MarkdownTables.coerce(block.text)?.render() ?: block.text)
        else -> block.copy(type = type)
    }
}
