package com.citation.core.rr

import com.citation.core.epub.Html
import com.citation.core.model.Chapter

/**
 * Extracts Royal Road content into Citation's internal model — the *producer* that makes an RR
 * serial "just another `Book`" for the format-blind reader.
 *
 * Two extractions, both pure so they're fixture-testable with no network:
 *  - [parseFictionChapters]: the fiction page's chapter table → an ordered [RrChapterRef] list (the
 *    catalog). This is the only thing the skim WebView is for; everything readable comes back
 *    through here, so **chapter 1 renders through the reader too**, identical to chapter 2.
 *  - [extractChapter]: a chapter page → a [Chapter] of flowing text, stripped of RR's site chrome.
 *
 * Both tolerate markup drift: a missing content div or an odd row degrades to what could be
 * recovered rather than throwing.
 */
object RoyalRoadHtml {

    /** A chapter's extracted content, before it is placed at an ordinal in a book. */
    data class ExtractedChapter(val title: String, val text: String)

    private val CHAPTER_LINK = Regex(
        "(?is)<a\\b[^>]*href\\s*=\\s*[\"']([^\"']*?/chapter/(\\d+)/[^\"']*)[\"'][^>]*>(.*?)</a>"
    )

    /**
     * Parse the fiction page HTML into an ordered chapter catalog. Reads anchors that point at
     * `/chapter/{id}/…` (Royal Road's chapter-table rows), de-duplicating by chapter id and keeping
     * first-seen order as the reading order. Non-chapter links (comments, author profile) are
     * ignored because they don't match the `/chapter/{id}/` shape.
     */
    fun parseFictionChapters(fictionId: Long, fictionTitle: String, html: String): FictionCatalog {
        val seen = LinkedHashMap<Long, RrChapterRef>()
        for (m in CHAPTER_LINK.findAll(html)) {
            val href = m.groupValues[1].trim()
            val chapterId = m.groupValues[2].toLongOrNull() ?: continue
            if (seen.containsKey(chapterId)) continue
            val title = Html.toText(m.groupValues[3]).trim().ifBlank { "Chapter ${seen.size + 1}" }
            seen[chapterId] = RrChapterRef(
                chapterId = chapterId,
                ordinal = seen.size,
                title = title,
                url = href
            )
        }
        return FictionCatalog(fictionId, fictionTitle, seen.values.toList())
    }

    /**
     * Extract a single chapter page into flowing text. Royal Road wraps chapter body in a
     * `chapter-inner`/`chapter-content` div; this pulls that block (falling back to the whole
     * document if the class is absent) and reduces it via the shared [Html] reducer, so RR text
     * anchors the same way EPUB text does.
     */
    fun extractChapter(html: String): ExtractedChapter {
        val title = Html.extractHeading(html) ?: "Untitled chapter"
        val body = contentDiv(html) ?: html
        return ExtractedChapter(title = title, text = Html.toText(body))
    }

    /** Assemble a [Chapter] at [ref]'s ordinal from an already-fetched chapter page. */
    fun toChapter(ref: RrChapterRef, html: String): Chapter {
        val extracted = extractChapter(html)
        return Chapter(
            ordinal = ref.ordinal,
            title = extracted.title.ifBlank { ref.title },
            sourceRef = ref.url,
            text = extracted.text,
            html = null
        )
    }

    /**
     * Return the inner HTML of the first element carrying a `chapter-content` (or `chapter-inner`)
     * class, balancing `<div>` nesting so a content block with nested divs isn't truncated early.
     */
    private fun contentDiv(html: String): String? {
        val marker = Regex("(?is)<div\\b[^>]*class\\s*=\\s*[\"'][^\"']*chapter-(?:content|inner)[^\"']*[\"'][^>]*>")
        val open = marker.find(html) ?: return null
        val start = open.range.last + 1
        // Walk div open/close tags from `start`, tracking depth (we're already inside one div).
        val tag = Regex("(?is)<(/?)div\\b[^>]*>")
        var depth = 1
        var idx = start
        while (idx < html.length) {
            val m = tag.find(html, idx) ?: break
            if (m.groupValues[1] == "/") {
                depth--
                if (depth == 0) return html.substring(start, m.range.first)
            } else {
                depth++
            }
            idx = m.range.last + 1
        }
        return html.substring(start) // unbalanced — take the rest, degrade don't crash
    }
}
