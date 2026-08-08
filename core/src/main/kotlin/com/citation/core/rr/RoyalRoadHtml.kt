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

    // The fiction title, taken from stable page metadata rather than "the first heading on the page".
    // The header chrome (the notifications dropdown — "You have no pending notifications" — etc.)
    // renders its own <h*> elements *before* the fiction's <h1>, so a naive first-heading grab picks
    // up site furniture. og:title and <title> are set by Royal Road to the fiction name itself.
    private val OG_TITLE_FWD = Regex(
        "(?is)<meta\\b[^>]*property\\s*=\\s*[\"']og:title[\"'][^>]*content\\s*=\\s*[\"']([^\"']*)[\"']"
    )
    private val OG_TITLE_REV = Regex(
        "(?is)<meta\\b[^>]*content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*property\\s*=\\s*[\"']og:title[\"']"
    )
    private val TITLE_TAG = Regex("(?is)<title\\b[^>]*>(.*?)</title>")
    private val SITE_SUFFIX = Regex("(?i)\\s*[|\\-–]\\s*Royal\\s*Road\\s*$")

    /**
     * Extract a fiction's display title from its page. Prefers Royal Road's own metadata — the
     * `og:title` meta tag, then the `<title>` element (with the trailing "| Royal Road" site suffix
     * stripped) — and only falls back to the first heading if neither is present. This is what keeps a
     * serial from being catalogued under the header's "You have no pending notifications" widget.
     */
    fun extractFictionTitle(html: String): String? {
        val og = OG_TITLE_FWD.find(html)?.groupValues?.get(1)
            ?: OG_TITLE_REV.find(html)?.groupValues?.get(1)
        val fromTitle = TITLE_TAG.find(html)?.groupValues?.get(1)
            ?.let { SITE_SUFFIX.replace(Html.toText(it), "") }
        val raw = og ?: fromTitle
        return raw?.let { Html.toText(it).trim() }?.takeIf { it.isNotBlank() }
            ?: Html.extractHeading(html)?.takeIf { it.isNotBlank() }
    }

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
     * document if the class is absent), removes Royal Road's anti-piracy stingers (see
     * [stripHiddenElements]/[dropStingerParagraphs]), and reduces it via the shared [Html] reducer,
     * so RR text anchors the same way EPUB text does.
     */
    fun extractChapter(html: String): ExtractedChapter {
        val title = Html.extractHeading(html) ?: "Untitled chapter"
        val body = contentDiv(html) ?: html
        val visible = stripHiddenElements(body, hiddenClasses(html))
        val text = dropStingerParagraphs(Html.toText(visible))
        return ExtractedChapter(title = title, text = text)
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

    // --- Anti-piracy stinger removal -------------------------------------------------------------
    //
    // Royal Road injects a short "this was stolen / read it on Royal Road" sentence into random
    // spots in a chapter's body as an anti-scraping tripwire. On the site each stinger sits in an
    // element hidden by CSS (a per-request random class set to `display:none`, or an inline style),
    // so a human reading in a browser never sees it — but a naive tag-strip pulls the sentence into
    // the reading text (the "Unauthorized duplication…" line that shows in-app but not in a browser).
    //
    // We remove them two ways, because neither alone is complete:
    //  1. Structurally ([stripHiddenElements]): read the page's own <style> blocks to learn which
    //     classes are hidden, then drop elements bearing them (plus anything inline-hidden). This
    //     kills the stinger at its mechanism, whatever the wording.
    //  2. By phrase ([dropStingerParagraphs]): if the hiding CSS wasn't in the fetched HTML (e.g. it
    //     lived in an external stylesheet), fall back to dropping whole paragraphs that match RR's
    //     stinger templates. Runs after (1), so it only ever sees what structural removal missed.

    private val STYLE_BLOCK = Regex("(?is)<style\\b[^>]*>(.*?)</style>")
    private val CSS_RULE = Regex("(?s)([^{}]+)\\{([^{}]*)}")
    private val HIDING_DECL = Regex(
        "(?i)(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden|" +
            "opacity\\s*:\\s*0(?![.0-9])|font-size\\s*:\\s*0(?:px|em|rem|%)?(?![.0-9]))"
    )
    private val CLASS_SELECTOR = Regex("\\.([A-Za-z_][\\w-]*)")
    private val CLASS_ATTR = Regex("(?is)\\bclass\\s*=\\s*[\"']([^\"']*)[\"']")
    private val STYLE_ATTR = Regex("(?is)\\bstyle\\s*=\\s*[\"']([^\"']*)[\"']")
    private val ELEMENT_OPEN = Regex("(?is)<([a-z][a-z0-9]*)\\b([^>]*)>")
    private val WHITESPACE = Regex("\\s+")
    private val VOID_ELEMENTS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"
    )

    /**
     * Read the page's inline `<style>` blocks and collect the class names of any rule whose
     * declaration block hides its target (`display:none`, `visibility:hidden`, a zero opacity or
     * font-size). These are the classes Royal Road attaches to a stinger to keep it invisible.
     */
    private fun hiddenClasses(html: String): Set<String> {
        val hidden = LinkedHashSet<String>()
        for (style in STYLE_BLOCK.findAll(html)) {
            for (rule in CSS_RULE.findAll(style.groupValues[1])) {
                if (!HIDING_DECL.containsMatchIn(rule.groupValues[2])) continue
                for (sel in CLASS_SELECTOR.findAll(rule.groupValues[1])) hidden += sel.groupValues[1]
            }
        }
        return hidden
    }

    /** Remove elements the page's CSS hides (by [hidden] class, or an inline hiding style) from a body fragment. */
    private fun stripHiddenElements(body: String, hidden: Set<String>): String {
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val open = ELEMENT_OPEN.find(body, i) ?: run { out.append(body, i, body.length); return out.toString() }
            out.append(body, i, open.range.first)
            val name = open.groupValues[1].lowercase()
            val attrs = open.groupValues[2]
            val selfClosing = attrs.trimEnd().endsWith("/") || name in VOID_ELEMENTS
            val end = if (!selfClosing && isHidden(attrs, hidden)) skipElement(body, open.range.last + 1, name) else -1
            if (end >= 0) {
                i = end
            } else {
                out.append(body, open.range.first, open.range.last + 1)
                i = open.range.last + 1
            }
        }
        return out.toString()
    }

    private fun isHidden(attrs: String, hidden: Set<String>): Boolean {
        val inline = STYLE_ATTR.find(attrs)?.groupValues?.get(1)
        if (inline != null && HIDING_DECL.containsMatchIn(inline)) return true
        if (hidden.isEmpty()) return false
        val classes = CLASS_ATTR.find(attrs)?.groupValues?.get(1) ?: return false
        return classes.split(WHITESPACE).any { it.isNotBlank() && it in hidden }
    }

    /**
     * Given [from] pointing just past a `<name …>` open tag, return the index just past its matching
     * close tag (balancing nested `name` elements), or -1 if the element never closes — in which
     * case the caller keeps the original tag rather than swallowing the rest of the chapter.
     */
    private fun skipElement(body: String, from: Int, name: String): Int {
        val tag = Regex("(?is)<(/?)" + Regex.escape(name) + "\\b([^>]*)>")
        var depth = 1
        var i = from
        while (i < body.length) {
            val m = tag.find(body, i) ?: return -1
            if (m.groupValues[1] == "/") {
                if (--depth == 0) return m.range.last + 1
            } else if (!m.groupValues[2].trimEnd().endsWith("/")) {
                depth++
            }
            i = m.range.last + 1
        }
        return -1
    }

    // Distinctive fragments from Royal Road's stinger pool. A paragraph matching any one is dropped.
    // Kept specific enough (theft/report/Royal-Road/Amazon signatures) that ordinary prose using the
    // word "author" or mentioning a theft in the story won't match.
    private val STINGER_SIGNALS: List<Regex> = listOf(
        "\\bunauthori[sz]ed (?:tale|story|narrative|duplication|reproduction|use|copy(?:ing)?|distribution)\\b",
        "\\btaken without (?:the author'?s? )?consent\\b",
        "\\b(?:stolen|lifted|pilfered|purloined|misappropriated|plagiari[sz]ed) from (?:royal ?road|the author|its (?:original|rightful) (?:source|home))\\b",
        "\\b(?:this|the) (?:tale|story|narrative|novel|book|content|material) (?:has been |was |is being )?(?:stolen|unlawfully (?:taken|lifted|copied)|illicitly (?:taken|lifted|copied)|misappropriated|purloined|pilfered)\\b",
        "\\bif (?:you (?:spot|find|see|encounter|come across|stumble upon|are reading)|this (?:tale|story|novel|narrative) is (?:found|available|posted))[\\w ,'’]{0,50}\\b(?:amazon|royal ?road)\\b",
        "\\breport (?:it|this|the (?:violation|incident|theft|matter)|any (?:instances|sightings)|sightings)\\b",
        "\\b(?:read|find|enjoy(?:ing)?|discover|support)[\\w ,'’]{0,40}(?:on|at|from) royal ?road\\b",
        "\\broyal ?road\\b[\\w ,'’]{0,40}(?:without permission|the author'?s? consent|genuine (?:version|copy)|for free|the (?:true|real|rightful) (?:home|source|version))",
        "\\bstolen (?:content|story|tale|novel|material|work)\\b",
        "\\bthis (?:content|tale|story|narrative|novel|book) (?:originates|comes|is|was (?:taken|copied)) from royal ?road\\b"
    ).map { Regex("(?i)$it") }

    /**
     * A stinger is always a short, self-contained interjection carrying one of RR's tell-tale
     * signatures. A long paragraph that happens to discuss theft is prose, not a tripwire, so the
     * word-count guard keeps real text safe even if a signature phrase appears inside it.
     */
    private fun isStingerParagraph(paragraph: String): Boolean {
        val p = paragraph.trim()
        if (p.isEmpty() || p.split(WHITESPACE).size > 60) return false
        return STINGER_SIGNALS.any { it.containsMatchIn(p) }
    }

    /** Drop whole paragraphs (blank-line-separated blocks, as [Html.toText] emits them) that read as stingers. */
    private fun dropStingerParagraphs(text: String): String =
        text.split("\n\n").filterNot { isStingerParagraph(it) }.joinToString("\n\n").trim()
}
