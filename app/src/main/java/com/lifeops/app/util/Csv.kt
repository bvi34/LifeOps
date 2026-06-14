package com.lifeops.app.util

/**
 * Minimal RFC 4180-ish CSV helpers shared by every CSV export/import in the app, so
 * fields are quoted (not comma-mangled) consistently. Kept Android-free and unit-testable.
 */
object Csv {
    /** Quotes a field iff it contains a comma, quote, CR or LF; doubles any internal quotes. */
    fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + value.replace("\"", "\"\"") + "\""
        else value

    fun row(fields: List<String>): String = fields.joinToString(",") { field(it) }

    /** Parses one CSV line, honouring quoted fields and escaped (doubled) quotes. */
    fun parseLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ }
                        else inQuotes = false
                    } else sb.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
