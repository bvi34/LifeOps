package com.project.app.logic

/** How a column's cells sit in their box, as the delimiter row's colons ask for. */
enum class ColumnAlign { LEFT, CENTER, RIGHT }

/**
 * A pipe table, read.
 *
 * Rectangular by construction: [MarkdownTables.table] pads every row out to the widest one, because
 * a renderer that has to cope with ragged rows is a renderer that draws a hole in the middle of
 * somebody's stat block.
 */
data class MarkdownTable(
    val header: List<String>,
    val alignments: List<ColumnAlign>,
    val rows: List<List<String>>
) {
    val columns: Int get() = header.size

    fun alignmentOf(column: Int): ColumnAlign = alignments.getOrElse(column) { ColumnAlign.LEFT }

    /** Every cell in the table, header included — what a word count and a search index want. */
    fun cells(): List<String> = header + rows.flatten()

    /** Back to Markdown, tidily: one space of padding, outer pipes, alignment preserved. */
    fun render(): String = MarkdownTables.render(this)
}

/**
 * Pipe tables: reading them, repairing them, and writing them back out.
 *
 * A table is the one piece of Markdown that a line-oriented block editor cannot store as prose. Its
 * rows are not paragraphs — joining them the way hard-wrapped prose is joined turns a stat block
 * into a single grey sentence of pipes, which is exactly what a document full of tables looked like
 * before this existed. So a table is *one block* holding its own source, parsed at draw time.
 *
 * [recover] exists for the documents that were already flattened. The pipes survive that flattening
 * even though the newlines do not, and a table's shape is recoverable from them: the delimiter row
 * says how many columns there are, and the cells that follow divide evenly into rows of that width.
 * That turns "my table is a wall of text" from data loss into a menu item.
 */
object MarkdownTables {

    /** A delimiter cell: `---`, `:--`, `--:` or `:-:`. */
    private val DELIMITER_CELL = Regex("""^:?-+:?$""")

    /** The same, but long enough to be unmistakable in text that has lost its line breaks. */
    private val FLAT_DELIMITER_CELL = Regex("""^:?-{2,}:?$""")

    /** A pipe-fenced delimiter run surviving inside a single line of flattened text. */
    private val FLATTENED = Regex("""\|\s*:?-{2,}:?\s*\|""")

    /** Build a rectangular table, padding the header, the alignments and every row to the widest row. */
    fun table(header: List<String>, alignments: List<ColumnAlign>, rows: List<List<String>>): MarkdownTable {
        val columns = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
        if (columns == 0) return MarkdownTable(emptyList(), emptyList(), emptyList())
        return MarkdownTable(
            header = header.pad(columns),
            alignments = List(columns) { alignments.getOrElse(it) { ColumnAlign.LEFT } },
            rows = rows.map { it.pad(columns) }
        )
    }

    /**
     * Split one row into its cells.
     *
     * The outer pipes are optional in Markdown and are dropped when present; `\|` is a pipe in a
     * cell, not a cell boundary.
     */
    fun cellsOf(line: String): List<String> {
        val cells = splitCells(line)
        if (cells.isEmpty()) return cells
        val from = if (cells.first().isBlank() && cells.size > 1) 1 else 0
        val to = if (cells.last().isBlank() && cells.size > from + 1) cells.size - 1 else cells.size
        return cells.subList(from, to).map { it.trim() }
    }

    /** Whether [line] is a table's `|---|:--:|` rule. */
    fun isDelimiterRow(line: String): Boolean {
        if (!line.contains('-')) return false
        val cells = cellsOf(line)
        return cells.isNotEmpty() && cells.all { DELIMITER_CELL.matches(it) }
    }

    /** Whether the lines at [at] begin a table — a header line with a delimiter rule under it. */
    fun startsAt(lines: List<String>, at: Int): Boolean {
        val header = lines.getOrNull(at) ?: return false
        val rule = lines.getOrNull(at + 1) ?: return false
        return header.contains('|') && header.isNotBlank() &&
            rule.contains('|') && isDelimiterRow(rule)
    }

    /** Whether [line] is a whole table that has lost its line breaks. */
    fun isFlattened(line: String): Boolean = FLATTENED.containsMatchIn(line)

    /** Read a multi-line pipe table, or null when [text] is not one. */
    fun parse(text: String): MarkdownTable? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2 || !isDelimiterRow(lines[1])) return null
        val header = cellsOf(lines[0])
        if (header.isEmpty()) return null
        val alignments = cellsOf(lines[1]).map(::alignmentOf)
        val rows = lines.drop(2).filter { it.contains('|') }.map { cellsOf(it) }
        return table(header, alignments, rows)
    }

    /**
     * Rebuild a table from text whose line breaks are gone.
     *
     * The delimiter run gives the column count; everything before it is the header and everything
     * after divides into rows of that width. Row boundaries left a blank cell behind when the lines
     * were joined (`… b | | c …`), so one blank between rows is skipped rather than read as a cell —
     * which is what keeps a genuinely empty cell in the *middle* of a row in its right column.
     */
    fun recover(text: String): MarkdownTable? {
        val tokens = splitCells(text.replace('\n', ' ')).map { it.trim() }
        val ruleStart = tokens.indexOfFirst { FLAT_DELIMITER_CELL.matches(it) }
        if (ruleStart < 0) return null
        var ruleEnd = ruleStart
        while (ruleEnd < tokens.size && FLAT_DELIMITER_CELL.matches(tokens[ruleEnd])) ruleEnd++
        val columns = ruleEnd - ruleStart
        if (columns == 0) return null

        val head = tokens.subList(0, ruleStart).toMutableList()
        while (head.isNotEmpty() && head.first().isBlank()) head.removeAt(0)
        while (head.isNotEmpty() && head.last().isBlank()) head.removeAt(head.size - 1)

        val body = tokens.subList(ruleEnd, tokens.size)
        // The delimiter row is the *least* reliable count in a hand-written table — it is the row
        // people miscount, and one `---` short would shunt every cell one column left. So the
        // header's own width is weighed against it, and whichever divides the body into rows that
        // actually end where rows should end wins.
        val width = bestWidth(listOf(columns, head.size), body)

        val header = if (head.size > width) head.subList(head.size - width, head.size) else head
        val alignments = tokens.subList(ruleStart, ruleEnd).map(::alignmentOf)
        return table(header.toList(), alignments, rowsOf(body, width))
    }

    /**
     * Cut [body] into rows of [width], stepping over the blank each lost line break left behind.
     *
     * Skipping exactly one blank *between* rows is what keeps a genuinely empty cell in its own
     * column: an empty cell inside a row is read as the cell it is, and only the extra blank at the
     * seam is discarded.
     */
    private fun rowsOf(body: List<String>, width: Int): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var i = 0
        if (i < body.size && body[i].isBlank()) i++
        while (i < body.size) {
            val row = ArrayList<String>(width)
            while (row.size < width && i < body.size) row += body[i++]
            if (row.any { it.isNotBlank() }) rows += row
            if (i < body.size && body[i].isBlank()) i++
        }
        return rows
    }

    /** Of the [candidates], the row width whose rows most often end on a seam. */
    private fun bestWidth(candidates: List<Int>, body: List<String>): Int {
        val usable = candidates.filter { it > 0 }.distinct()
        if (usable.size == 1) return usable.first()
        return usable.maxWithOrNull(
            compareBy({ seamScore(body, it) }, { it })
        ) ?: 1
    }

    /** How much of [body] divides into rows of [width] that end where a line break used to be. */
    private fun seamScore(body: List<String>, width: Int): Float {
        var i = 0
        var rows = 0
        var onSeam = 0
        if (i < body.size && body[i].isBlank()) i++
        while (i < body.size) {
            var taken = 0
            while (taken < width && i < body.size) {
                i++
                taken++
            }
            rows++
            if (i >= body.size || body[i].isBlank()) onSeam++
            if (i < body.size && body[i].isBlank()) i++
        }
        return if (rows == 0) 0f else onSeam.toFloat() / rows
    }

    /** A table from [text] however it survives in there — properly lined up, or flattened. */
    fun coerce(text: String): MarkdownTable? = parse(text) ?: recover(text)

    /** A table back to Markdown, columns padded so the source is readable when edited by hand. */
    fun render(table: MarkdownTable): String {
        if (table.columns == 0) return ""
        val widths = (0 until table.columns).map { column ->
            val cells = listOf(table.header.getOrElse(column) { "" }) +
                table.rows.map { it.getOrElse(column) { "" } }
            maxOf(3, cells.maxOf { escapeCell(it).length })
        }

        fun row(cells: List<String>) = cells.mapIndexed { column, cell ->
            escapeCell(cell).padEnd(widths[column])
        }.joinToString(" | ", prefix = "| ", postfix = " |")

        val rule = (0 until table.columns).joinToString(" | ", prefix = "| ", postfix = " |") { column ->
            val width = widths[column]
            when (table.alignmentOf(column)) {
                ColumnAlign.LEFT -> "-".repeat(width)
                ColumnAlign.CENTER -> ":" + "-".repeat(width - 2) + ":"
                ColumnAlign.RIGHT -> "-".repeat(width - 1) + ":"
            }
        }

        return buildString {
            appendLine(row(table.header))
            appendLine(rule)
            table.rows.forEach { appendLine(row(it)) }
        }.trimEnd('\n')
    }

    /** An empty table of [columns] columns, for "add a table" in the editor. */
    fun blank(columns: Int = 3, rows: Int = 2): String = render(
        table(
            header = List(columns) { "Column ${it + 1}" },
            alignments = List(columns) { ColumnAlign.LEFT },
            rows = List(rows) { List(columns) { "" } }
        )
    )

    private fun alignmentOf(cell: String): ColumnAlign {
        val left = cell.startsWith(":")
        val right = cell.endsWith(":")
        return when {
            left && right -> ColumnAlign.CENTER
            right -> ColumnAlign.RIGHT
            else -> ColumnAlign.LEFT
        }
    }

    /** Split on unescaped pipes, keeping the blanks — callers decide what a blank means. */
    private fun splitCells(line: String): List<String> {
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length && line[i + 1] == '|' -> {
                    cell.append("\\|")
                    i += 2
                }

                c == '|' -> {
                    out += cell.toString()
                    cell.setLength(0)
                    i++
                }

                else -> {
                    cell.append(c)
                    i++
                }
            }
        }
        out += cell.toString()
        return if (out.size == 1 && out.first().isBlank()) emptyList() else out
    }

    private fun escapeCell(cell: String): String =
        cell.replace("\\|", "|").replace("|", "\\|").replace("\n", " ").trim()

    private fun List<String>.pad(size: Int): List<String> =
        if (this.size == size) this else List(size) { getOrElse(it) { "" } }
}
