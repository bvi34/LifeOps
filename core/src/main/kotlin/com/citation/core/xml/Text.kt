package com.citation.core.xml

import com.citation.core.doc.Entities

/**
 * Turning XML element content into readable text.
 *
 * Feeds are inconsistent in a specific, predictable way: a summary may be real XHTML, or the *same*
 * HTML escaped into a text node (`&lt;p&gt;`), or plain prose, sometimes wrapped in CDATA — often
 * varying between entries of one feed. So the reduction strips markup, decodes entities, and then
 * strips markup once more if decoding revealed a second layer of it. That is enough to render a
 * blurb without dragging a full HTML pipeline into catalog parsing.
 */
internal object Text {

    private val CDATA = Regex("(?s)<!\\[CDATA\\[(.*?)]]>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val TAG = Regex("(?s)<[^>]+>")
    private val BLOCK_BREAK = Regex("(?i)</(p|div|li|br|h[1-6])\\s*/?>")
    private val WS = Regex("[ \\t\\r\\f\\u000B]+")
    private val BLANKS = Regex("\\n{3,}")

    /** Readable text for an element's inner XML. */
    fun of(xml: String): String {
        var s = COMMENT.replace(xml, " ")
        s = CDATA.replace(s) { it.groupValues[1] }
        s = BLOCK_BREAK.replace(s, "\n")
        s = TAG.replace(s, "")
        s = Entities.decode(s)
        // A second layer only exists when the source escaped its own markup; strip it if so.
        if (s.contains('<') && TAG.containsMatchIn(s)) {
            s = TAG.replace(BLOCK_BREAK.replace(s, "\n"), "")
            s = Entities.decode(s)
        }
        s = s.lines().joinToString("\n") { WS.replace(it, " ").trim() }
        return BLANKS.replace(s, "\n\n").trim()
    }

    /** Decode entity references in an attribute value. */
    fun unescape(s: String): String = Entities.decode(s)
}
