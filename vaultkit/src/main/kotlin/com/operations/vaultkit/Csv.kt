package com.operations.vaultkit

/**
 * A CSV reader, because a CSV is how every other password manager lets you leave.
 *
 * This is not a general-purpose spreadsheet parser and it should not become one. It exists for one
 * job — reading the file a browser or 1Password writes when somebody asks for their passwords back
 * — and the reason it is written here rather than taken from a library is the same reason the crypto
 * is: the file it reads is the household's entire password list in plaintext, and a dependency that
 * parses that is a dependency worth not having.
 *
 * What it handles is what those exporters actually emit:
 *
 *  - **Quoted cells**, with `""` for a literal quote inside one. A note with a comma in it is the
 *    common case, and a note with a *newline* in it is the case that breaks every line-by-line
 *    reader written in an afternoon — so the quote state spans lines here, deliberately.
 *  - **Any line ending.** Windows browsers write CRLF, everything else writes LF, and an old Mac
 *    export writes CR alone.
 *  - **A byte-order mark**, which Windows tooling puts in front of the first header and which turns
 *    `url` into `﻿url` for anybody comparing header names as strings.
 *  - **A delimiter that is not a comma.** A CSV written on a machine with a European locale is
 *    semicolon-separated, and a "CSV" exported from a spreadsheet is often tab-separated. The
 *    delimiter is sniffed from the header line rather than asked for, because nobody importing
 *    their passwords knows which one they have.
 *
 * Blank lines are dropped rather than returned as empty rows: a trailing newline at the end of the
 * file is universal and an empty row is never meaningful in an export.
 */
object Csv {

    /** The delimiters worth sniffing for, in the order a tie is broken. */
    private val DELIMITERS = charArrayOf(',', ';', '\t')

    /**
     * Split [text] into rows of cells, sniffing the delimiter from the first line.
     *
     * The sniff counts candidates *outside* quotes on the header line only. Counting the whole file
     * would let one note full of semicolons outvote the real delimiter, and the header line is the
     * one line in the file whose shape is known: it is short, it has no free text in it, and it has
     * exactly as many separators as the file has columns.
     */
    fun parse(text: String): List<List<String>> = parse(text, sniffDelimiter(text))

    fun parse(text: String, delimiter: Char): List<List<String>> {
        val input = text.removePrefix("﻿")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0

        fun endCell() {
            row.add(cell.toString())
            cell.setLength(0)
        }

        fun endRow() {
            endCell()
            // A row of one empty cell is a blank line; anything else is a row, including one whose
            // every cell happens to be empty in a file that has real columns.
            if (row.size > 1 || row[0].isNotEmpty()) rows.add(row)
            row = ArrayList()
        }

        while (index < input.length) {
            val c = input[index]
            when {
                quoted -> when {
                    c != '"' -> cell.append(c)
                    // `""` inside a quoted cell is one quote character, not the end of the cell.
                    index + 1 < input.length && input[index + 1] == '"' -> {
                        cell.append('"')
                        index++
                    }
                    else -> quoted = false
                }
                // A quote only opens a cell at its start. Mid-cell it is somebody's inch mark, and
                // treating it as structure would swallow the rest of the file.
                c == '"' && cell.isEmpty() -> quoted = true
                c == delimiter -> endCell()
                c == '\r' -> {
                    if (index + 1 < input.length && input[index + 1] == '\n') index++
                    endRow()
                }
                c == '\n' -> endRow()
                else -> cell.append(c)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private fun sniffDelimiter(text: String): Char {
        val header = text.removePrefix("﻿").lineSequence().firstOrNull { it.isNotBlank() }
            ?: return ','
        var quoted = false
        val counts = IntArray(DELIMITERS.size)
        for (c in header) {
            if (c == '"') {
                quoted = !quoted
                continue
            }
            if (quoted) continue
            val slot = DELIMITERS.indexOf(c)
            if (slot >= 0) counts[slot]++
        }
        val best = counts.indices.maxByOrNull { counts[it] } ?: 0
        return if (counts[best] == 0) ',' else DELIMITERS[best]
    }

    /** `Login URL` → `login url`: how a header is compared, given nobody agrees on capitals. */
    fun normaliseHeader(value: String): String =
        value.trim().trim('"').removePrefix("﻿").lowercase().replace('_', ' ').trim()
}
