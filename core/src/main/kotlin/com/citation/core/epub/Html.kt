package com.citation.core.epub

import com.citation.core.doc.Entities
import com.citation.core.doc.HtmlDocument

/**
 * Minimal, dependency-free HTML to flowing-text reduction.
 *
 * The reader anchors notes against a [com.citation.core.model.Chapter]'s plain `text`, and freezes
 * quoted snapshots from it, so the reduction has to be **stable and deterministic**: the same HTML
 * must always yield the same text, or an anchor captured today wouldn't resolve tomorrow. This is
 * intentionally not a full HTML parser — it strips tags, decodes the common entities, drops
 * non-content elements (`script`/`style`), and collapses whitespace to single spaces with blank
 * lines between block elements. That is exactly the "flowing text" the reader reflows and notes
 * hang off.
 *
 * The reduction itself now lives in [HtmlDocument], which performs the identical pipeline while
 * tracking where each tag landed — that is what lets the reader render headings, emphasis, verse
 * and illustrations *without* moving a character of this text. This object stays as the text-only
 * entry point its callers already use.
 */
object Html {

    /** Reduce an HTML document/fragment to canonical flowing text (paragraphs split by blank lines). */
    fun toText(html: String): String = HtmlDocument.toText(html)

    /** Best-effort title extraction: first `<h1..3>` text, else the `<title>`, else `null`. */
    fun extractHeading(html: String): String? {
        val h = Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(html)?.groupValues?.get(1)
        val raw = h ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
        return raw?.let { Entities.decode(TAG.replace(it, "")) }?.trim()?.takeIf { it.isNotBlank() }
    }

    private val TAG = Regex("(?s)<[^>]+>")
}
