package com.citation.core.epub

/**
 * Minimal, dependency-free HTML → flowing-text reduction.
 *
 * The reader anchors notes against a [com.citation.core.model.Chapter]'s plain `text`, and freezes
 * quoted snapshots from it, so the reduction has to be **stable and deterministic**: the same HTML
 * must always yield the same text, or an anchor captured today wouldn't resolve tomorrow. This is
 * intentionally not a full HTML parser — it strips tags, decodes the common entities, drops
 * non-content elements (`script`/`style`), and collapses whitespace to single spaces with blank
 * lines between block elements. That is exactly the "flowing text" the reader reflows and notes
 * hang off.
 */
object Html {

    private val SCRIPT_STYLE = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>")
    private val BLOCK_BREAK = Regex("(?i)</(p|div|section|article|h[1-6]|li|blockquote|tr)>")
    private val LINE_BREAK = Regex("(?i)<br\\s*/?>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val MULTI_BLANK = Regex("\\n{3,}")
    private val INLINE_WS = Regex("[ \\t\\x0B\\f\\r]+")

    /** Reduce an HTML document/fragment to canonical flowing text (paragraphs split by blank lines). */
    fun toText(html: String): String {
        var s = html
        s = SCRIPT_STYLE.replace(s, " ")
        s = LINE_BREAK.replace(s, "\n")
        s = BLOCK_BREAK.replace(s, "\n\n")
        s = TAG.replace(s, "")
        s = decodeEntities(s)
        // Normalise whitespace: collapse inline runs, trim each line, cap blank-line runs.
        s = s.lines().joinToString("\n") { INLINE_WS.replace(it, " ").trim() }
        s = MULTI_BLANK.replace(s, "\n\n")
        return s.trim()
    }

    /** Best-effort title extraction: first `<h1..3>` text, else the `<title>`, else `null`. */
    fun extractHeading(html: String): String? {
        val h = Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(html)?.groupValues?.get(1)
        val raw = h ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
        return raw?.let { decodeEntities(TAG.replace(it, "")).trim() }?.takeIf { it.isNotBlank() }
    }

    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi in (i + 1)..(i + 12)) {
                    val entity = s.substring(i + 1, semi)
                    val decoded = decodeEntity(entity)
                    if (decoded != null) {
                        sb.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun decodeEntity(entity: String): String? = when {
        entity.startsWith("#x") || entity.startsWith("#X") ->
            entity.drop(2).toIntOrNull(16)?.let { codePoint(it) }
        entity.startsWith("#") ->
            entity.drop(1).toIntOrNull()?.let { codePoint(it) }
        else -> NAMED[entity]
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else null

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–", "middot" to "·",
        "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“", "copy" to "©", "trade" to "™"
    )
}
