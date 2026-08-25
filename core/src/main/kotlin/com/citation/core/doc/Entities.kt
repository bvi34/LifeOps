package com.citation.core.doc

/**
 * HTML entity decoding, in one place.
 *
 * It lives on its own because two callers must agree *exactly*: the structured reduction
 * ([HtmlDocument]) and the heading extractor in `epub/Html`. A second copy of this table that
 * drifted by one entity would move character offsets, and moving offsets is how anchors captured
 * last year stop resolving.
 */
internal object Entities {

    /** Decode every `&…;` reference in [s]. Unrecognised references are left verbatim. */
    fun decode(s: String): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi in (i + 1)..(i + 12)) {
                    val decoded = decodeEntity(s.substring(i + 1, semi))
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

    /**
     * How far a reference starting at [i] extends and what it decodes to, or `null` if the text at
     * [i] is not a reference we recognise. Returned as `(endExclusive, replacement)` so an
     * offset-tracking caller can map the whole span onto the replacement.
     */
    fun decodeAt(s: String, i: Int): Pair<Int, String>? {
        if (s[i] != '&') return null
        val semi = s.indexOf(';', i + 1)
        if (semi !in (i + 1)..(i + 12)) return null
        val decoded = decodeEntity(s.substring(i + 1, semi)) ?: return null
        return (semi + 1) to decoded
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
