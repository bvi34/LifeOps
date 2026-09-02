package com.citation.core.doc

/**
 * Reads structure off an already-reduced document: given the finished canonical text and where
 * every tag landed in it, work out which stretches are headings, quotes, verse, list items, code,
 * tables — and where the illustrations, rules and inline emphasis go.
 *
 * The segmentation trick is that the reduction has *already* done the hard part. Every block-level
 * close emits a blank line, so a run of text between blank lines is a block; a single newline
 * (which only `<br>` produces) stays inside a run, which is exactly what verse needs. So the text
 * is split on blank lines, and each run is classified by the element stack that was open where it
 * started. Nothing here re-reads the source markup or moves a character.
 */
internal object Structure {

    class Built(
        val blocks: List<DocumentBlock>,
        val anchors: Map<String, Int>,
        val heading: String?
    )

    /** An element still open at some point in the walk, with where its content began. */
    private class Open(
        val name: String,
        val raw: String,
        val start: Int,
        val ordered: Boolean = false,
        val itemIndex: Int = 0
    )

    private class Cell(val start: Int, val end: Int, val header: Boolean)

    fun build(text: String, tags: List<HtmlDocument.Tag>, at: IntArray): Built {
        val runs = splitRuns(text, blockBoundaries(tags, at))

        val stack = ArrayList<Open>()
        val listStack = ArrayList<IntArray>()   // one mutable counter per open ol/ul
        val orderedStack = ArrayList<Boolean>()
        val spans = ArrayList<InlineSpan>()
        val markers = ArrayList<DocumentBlock>()
        val cells = ArrayList<Cell>()
        val anchors = LinkedHashMap<String, Int>()

        fun openTag(tag: HtmlDocument.Tag, off: Int) {
            attr(tag.raw, "id")?.let { anchors.putIfAbsent(it, off) }
            when (tag.name) {
                "img", "image" -> {
                    val src = attr(tag.raw, "src") ?: attr(tag.raw, "href")
                    if (!src.isNullOrBlank()) markers.add(DocumentBlock.Image(off, src, attr(tag.raw, "alt")))
                    return
                }
                "hr" -> { markers.add(DocumentBlock.Rule(off)); return }
                "br", "meta", "link", "col", "input", "source", "area", "base", "wbr" -> return
                "ol", "ul" -> {
                    listStack.add(intArrayOf(0))
                    orderedStack.add(tag.name == "ol")
                }
            }
            if (tag.raw.endsWith("/>")) return
            var index = 0
            if (tag.name == "li" && listStack.isNotEmpty()) {
                val counter = listStack.last()
                counter[0]++
                index = counter[0]
            }
            stack.add(
                Open(
                    name = tag.name,
                    raw = tag.raw,
                    start = off,
                    ordered = if (tag.name == "li") orderedStack.lastOrNull() ?: false else false,
                    itemIndex = index
                )
            )
        }

        fun closeTag(tag: HtmlDocument.Tag, off: Int) {
            if (tag.name == "ol" || tag.name == "ul") {
                listStack.removeLastOrNull()
                orderedStack.removeLastOrNull()
            }
            val idx = stack.indexOfLast { it.name == tag.name }
            if (idx < 0) return
            // Anything above the match was left unclosed by the source; drop it rather than throw.
            while (stack.size > idx) {
                val open = stack.removeAt(stack.size - 1)
                if (open.name == "td" || open.name == "th") {
                    cells.add(Cell(open.start, off, open.name == "th"))
                }
                inlineStyle(open, stack)?.let { style ->
                    if (off > open.start) {
                        spans.add(
                            InlineSpan(
                                start = open.start,
                                end = off,
                                style = style,
                                href = attr(open.raw, "href")?.let { Entities.decode(it) }
                            )
                        )
                    }
                }
            }
        }

        // Walk runs and tags together: tags are in document order and their offsets never decrease,
        // so a single pass classifies each run by the stack open where it starts.
        var ti = 0
        val classified = ArrayList<Pair<IntArray, List<Open>>>()
        for (run in runs) {
            while (ti < tags.size && at[ti] <= run[0]) {
                val tag = tags[ti]
                if (tag.closing) closeTag(tag, at[ti]) else openTag(tag, at[ti])
                ti++
            }
            classified.add(run to ArrayList(stack))
        }
        while (ti < tags.size) {
            val tag = tags[ti]
            if (tag.closing) closeTag(tag, at[ti]) else openTag(tag, at[ti])
            ti++
        }

        val blocks = assemble(text, classified, cells, markers, spans)
        val heading = blocks.asSequence()
            .filterIsInstance<DocumentBlock.Text>()
            .firstOrNull { it.kind == BlockKind.HEADING && it.level in 1..3 }
            ?.let { text.substring(it.start, it.end).trim() }
            ?.takeIf { it.isNotBlank() }

        return Built(blocks, anchors, heading)
    }

    // --- Segmentation ---------------------------------------------------------------------------

    /**
     * Where a text run can begin: at a blank line, or wherever a block-level element opens or
     * closes without one.
     *
     * The blank lines alone are nearly enough — the reduction emits one at every block close — but
     * not quite. `<title>t</title></head><body><h1>Heading</h1>` reduces to `tHeading` with no
     * break between them, because `</title>` is not a block close; without this the heading would
     * be swallowed into a run classified by whatever was open at the run's start, and every EPUB
     * chapter in the world starts exactly that way. Splitting on block boundaries as well means a
     * run always begins where its own element does, so classifying by the stack at the start is
     * right by construction.
     */
    private val BLOCK_LEVEL = setOf(
        "p", "div", "section", "article", "aside", "blockquote", "pre", "figure", "figcaption",
        "caption", "table", "tr", "td", "th", "ol", "ul", "li", "dl", "dt", "dd", "header",
        "footer", "main", "nav", "body", "h1", "h2", "h3", "h4", "h5", "h6"
    )

    private fun blockBoundaries(tags: List<HtmlDocument.Tag>, at: IntArray): IntArray {
        val out = sortedSetOf<Int>()
        tags.forEachIndexed { i, tag -> if (tag.name in BLOCK_LEVEL) out.add(at[i]) }
        return out.toIntArray()
    }

    /**
     * Text runs between blank lines, further split at [boundaries]. Empty runs are dropped, so a
     * boundary that falls on a break costs nothing.
     */
    private fun splitRuns(text: String, boundaries: IntArray): List<IntArray> {
        val runs = ArrayList<IntArray>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n' && i + 1 < text.length && text[i + 1] == '\n') {
                if (i > start) runs.add(intArrayOf(start, i))
                i += 2
                start = i
            } else {
                i++
            }
        }
        if (start < text.length) runs.add(intArrayOf(start, text.length))

        if (boundaries.isEmpty()) return runs
        val split = ArrayList<IntArray>(runs.size)
        runs.forEach { run ->
            var from = run[0]
            boundaries.forEach { b ->
                if (b > from && b < run[1]) {
                    split.add(intArrayOf(from, b))
                    from = b
                }
            }
            if (from < run[1]) split.add(intArrayOf(from, run[1]))
        }
        return split
    }

    // --- Assembly -------------------------------------------------------------------------------

    private fun assemble(
        text: String,
        classified: List<Pair<IntArray, List<Open>>>,
        cells: List<Cell>,
        markers: List<DocumentBlock>,
        spans: List<InlineSpan>
    ): List<DocumentBlock> {
        val out = ArrayList<DocumentBlock>()
        var i = 0
        while (i < classified.size) {
            val (run, stack) = classified[i]
            if (stack.any { it.name == "table" }) {
                // Consecutive in-table runs are the rows of one table.
                var j = i
                while (j < classified.size && classified[j].second.any { it.name == "table" }) j++
                out.add(tableOf(classified.subList(i, j), cells))
                i = j
                continue
            }
            out.add(textBlockOf(run, stack))
            i++
        }

        val merged = ArrayList<DocumentBlock>(out.size + markers.size)
        merged.addAll(out)
        merged.addAll(markers)
        // Markers sit *between* characters, so at an equal offset they come first — an illustration
        // opening a section renders above the words it introduces.
        merged.sortWith(compareBy({ it.start }, { if (it.isMarker) 0 else 1 }))

        return attachSpans(merged, spans)
    }

    /**
     * Build one table out of the runs inside it. Cell boundaries split runs (every `<td>` is a
     * block-level open), so runs are regrouped by their enclosing `<tr>` — a row is a row, however
     * many cells it holds.
     */
    private fun tableOf(runs: List<Pair<IntArray, List<Open>>>, cells: List<Cell>): DocumentBlock.Table {
        val start = runs.first().first[0]
        val end = runs.last().first[1]

        val grouped = ArrayList<Pair<Open?, MutableList<IntArray>>>()
        runs.forEach { (run, stack) ->
            val row = stack.lastOrNull { it.name == "tr" }
            val open = grouped.lastOrNull()
            if (open != null && open.first === row) open.second.add(run)
            else grouped.add(row to mutableListOf(run))
        }

        val built = grouped.map { (_, group) ->
            val rowStart = group.first()[0]
            val rowEnd = group.last()[1]
            val inRow = cells.filter { it.start >= rowStart && it.end <= rowEnd }.sortedBy { it.start }
            DocumentBlock.Table.Row(
                start = rowStart,
                end = rowEnd,
                cells = inRow.map { it.start until it.end },
                header = inRow.isNotEmpty() && inRow.all { it.header }
            )
        }
        return DocumentBlock.Table(start, end, built)
    }

    private fun textBlockOf(run: IntArray, stack: List<Open>): DocumentBlock.Text {
        val heading = stack.lastOrNull { it.name.length == 2 && it.name[0] == 'h' && it.name[1] in '1'..'6' }
        val li = stack.lastOrNull { it.name == "li" }
        return when {
            stack.any { it.name == "pre" } ->
                DocumentBlock.Text(run[0], run[1], BlockKind.CODE)
            heading != null ->
                DocumentBlock.Text(run[0], run[1], BlockKind.HEADING, level = heading.name[1] - '0')
            li != null ->
                DocumentBlock.Text(
                    run[0], run[1], BlockKind.LIST_ITEM,
                    level = stack.count { it.name == "li" },
                    ordered = li.ordered,
                    itemIndex = li.itemIndex
                )
            stack.any { it.name == "figcaption" || it.name == "caption" } ->
                DocumentBlock.Text(run[0], run[1], BlockKind.CAPTION)
            stack.any { isVerse(it) } ->
                DocumentBlock.Text(run[0], run[1], BlockKind.VERSE)
            stack.any { it.name == "blockquote" } ->
                DocumentBlock.Text(run[0], run[1], BlockKind.BLOCKQUOTE)
            else ->
                DocumentBlock.Text(run[0], run[1], BlockKind.PARAGRAPH)
        }
    }

    /**
     * Clip each inline span onto the text block it falls in. Blocks and spans are both in document
     * order, so one forward pass covers it; the carry list keeps a span that runs across a block
     * boundary attached to both sides rather than losing it.
     */
    private fun attachSpans(blocks: List<DocumentBlock>, spans: List<InlineSpan>): List<DocumentBlock> {
        if (spans.isEmpty()) return blocks
        val sorted = spans.sortedBy { it.start }
        var idx = 0
        val active = ArrayList<InlineSpan>()
        return blocks.map { block ->
            if (block !is DocumentBlock.Text) return@map block
            while (idx < sorted.size && sorted[idx].start < block.end) {
                active.add(sorted[idx])
                idx++
            }
            active.removeAll { it.end <= block.start }
            val mine = active.mapNotNull { span ->
                val s = maxOf(span.start, block.start)
                val e = minOf(span.end, block.end)
                if (e > s) span.copy(start = s, end = e) else null
            }
            if (mine.isEmpty()) block else block.copy(spans = mine)
        }
    }

    // --- Element interpretation -----------------------------------------------------------------

    private fun inlineStyle(open: Open, ancestors: List<Open>): InlineStyle? = when (open.name) {
        "em", "i", "cite", "var", "dfn" -> InlineStyle.ITALIC
        "strong", "b" -> InlineStyle.BOLD
        "code", "kbd", "samp", "tt" -> InlineStyle.CODE
        "u", "ins" -> InlineStyle.UNDERLINE
        "s", "strike", "del" -> InlineStyle.STRIKETHROUGH
        "sup" -> InlineStyle.SUPERSCRIPT
        "sub" -> InlineStyle.SUBSCRIPT
        // An anchor with no destination is a link *target* — `<a id="page17">`, which converted
        // books scatter through their prose and sometimes leave wrapped around whole paragraphs.
        // Painting those as links is how a chapter comes out entirely underlined and in the link
        // colour, so an `a` earns link styling only by having somewhere to go.
        "a" -> when {
            attr(open.raw, "href").isNullOrBlank() -> null
            isNoteRef(open, ancestors) -> InlineStyle.FOOTNOTE_REF
            else -> InlineStyle.LINK
        }
        "span" -> if (smallCaps(open.raw)) InlineStyle.SMALL_CAPS else null
        else -> null
    }

    /**
     * A link is a note reference when the source says so (`epub:type="noteref"`, a `footnote`/
     * `noteref` class) or when it is a same-document link sitting inside a superscript — the
     * typographic convention every unlabelled footnote in the wild uses.
     */
    private fun isNoteRef(open: Open, ancestors: List<Open>): Boolean {
        val href = attr(open.raw, "href").orEmpty()
        val type = attr(open.raw, "epub:type").orEmpty().lowercase()
        val cls = attr(open.raw, "class").orEmpty().lowercase()
        if (type.contains("noteref")) return true
        if (cls.contains("noteref") || cls.contains("footnote") || cls.contains("endnote")) return true
        return href.startsWith("#") && ancestors.any { it.name == "sup" }
    }

    private fun smallCaps(raw: String): Boolean {
        val cls = attr(raw, "class").orEmpty().lowercase()
        val style = attr(raw, "style").orEmpty().lowercase()
        return cls.contains("smallcaps") || cls.contains("small-caps") ||
            style.replace(" ", "").contains("font-variant:small-caps")
    }

    private val VERSE_HINTS = listOf("verse", "poem", "stanza", "poetry", "linegroup", "line-group")

    private fun isVerse(open: Open): Boolean {
        val cls = attr(open.raw, "class").orEmpty().lowercase()
        val type = attr(open.raw, "epub:type").orEmpty().lowercase()
        return VERSE_HINTS.any { cls.contains(it) || type.contains(it) }
    }

    // --- Attributes -----------------------------------------------------------------------------

    /**
     * Attribute patterns, built once. The name must be preceded by whitespace, a namespace colon or
     * a quote, so `href` does not also match `data-href` while `xlink:href` still does.
     */
    private val ATTR: Map<String, Regex> =
        listOf("id", "src", "href", "alt", "class", "style", "epub:type", "colspan").associateWith {
            Regex("(?i)[\\s:\"']" + Regex.escape(it) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        }

    /** One attribute out of a raw tag, or `null` when absent or empty. */
    fun attr(raw: String, name: String): String? {
        val m = (ATTR[name] ?: return null).find(raw) ?: return null
        val value = m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
        return value.takeIf { it.isNotEmpty() }
    }
}
