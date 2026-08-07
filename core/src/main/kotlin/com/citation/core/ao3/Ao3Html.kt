package com.citation.core.ao3

import com.citation.core.epub.Html
import com.citation.core.model.Chapter

/**
 * Extracts Archive of Our Own content into Citation's internal model — the *producer* that makes an
 * AO3 work "just another `Book`" for the format-blind reader, exactly as [com.citation.core.rr.RoyalRoadHtml]
 * does for Royal Road.
 *
 * Three extractions, all pure so they're fixture-testable with no network:
 *  - [extractWorkTitle] / [extractWorkAuthor]: the work's display metadata.
 *  - [parseWorkChapters]: the work page's chapter navigation → an ordered [Ao3ChapterRef] list (the
 *    catalog). AO3 renders a `<select id="selected_id">` whose `<option value="{chapterId}">`s are
 *    the authoritative chapter list; a single-chapter work has no such select, so it degrades to one
 *    chapter pointing at the work URL. Everything readable comes back through here, so **chapter 1
 *    renders through the reader too**, identical to chapter 2.
 *  - [extractChapter]: a chapter page → a [Chapter] of flowing text, stripped of AO3's site chrome
 *    and, crucially, of the work summary/notes (which share the `userstuff` class but are not the
 *    chapter body).
 *
 * All tolerate markup drift: a missing content div or an odd option degrades to what could be
 * recovered rather than throwing.
 */
object Ao3Html {

    /** A chapter's extracted content, before it is placed at an ordinal in a book. */
    data class ExtractedChapter(val title: String, val text: String)

    // The chapter navigation dropdown AO3 renders (once at the top, once at the bottom — we dedupe by
    // chapter id). Its options are `value="{chapterId}"` with visible text like "3. The Long Road".
    private val SELECTED_ID_BLOCK = Regex(
        "(?is)<select\\b[^>]*(?:id|name)\\s*=\\s*[\"']selected_id[\"'][^>]*>(.*?)</select>"
    )
    private val OPTION = Regex("(?is)<option\\b[^>]*value\\s*=\\s*[\"'](\\d+)[\"'][^>]*>(.*?)</option>")
    private val CHAPTER_NUMBER_PREFIX = Regex("^\\s*\\d+\\s*[.)]\\s*")

    // Title lives in the work header, `<h2 class="title heading">`. The <title> tag and og:title are
    // stuffed with "Title - Author - Fandom - … [Archive of Our Own]", so the header heading is the
    // clean source; fall back to the <title> tag's leading segment only if the header is absent.
    private val WORK_TITLE = Regex(
        "(?is)<h2\\b[^>]*class\\s*=\\s*[\"'][^\"']*\\btitle\\b[^\"']*heading[^\"']*[\"'][^>]*>(.*?)</h2>"
    )
    private val TITLE_TAG = Regex("(?is)<title\\b[^>]*>(.*?)</title>")
    // AO3 bylines: `<a rel="author" href="/users/name/pseuds/name">Name</a>` (possibly several).
    private val AUTHOR = Regex("(?is)<a\\b[^>]*rel\\s*=\\s*[\"']author[\"'][^>]*>(.*?)</a>")

    /**
     * Extract a work's display title from its page. Prefers AO3's own work-header heading
     * (`<h2 class="title heading">`) and only falls back to the `<title>` element's leading segment
     * (before the first " - ") if the header is absent — the `<title>`/og:title carry a long
     * "Title - Author - Fandom … [Archive of Our Own]" string that must not become the book title.
     */
    fun extractWorkTitle(html: String): String? {
        WORK_TITLE.find(html)?.groupValues?.get(1)
            ?.let { Html.toText(it).trim() }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return TITLE_TAG.find(html)?.groupValues?.get(1)
            ?.let { Html.toText(it) }
            ?.substringBefore(" - ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** Extract the first bylined author, or `null` if the page names none (anonymous/hidden work). */
    fun extractWorkAuthor(html: String): String? =
        AUTHOR.find(html)?.groupValues?.get(1)
            ?.let { Html.toText(it).trim() }
            ?.takeIf { it.isNotBlank() }

    /**
     * Parse the work page HTML into an ordered chapter catalog.
     *
     * Reads the `<select id="selected_id">` chapter dropdown: each `<option value="{chapterId}">` is
     * a chapter, in document (reading) order, deduped by chapter id (the top and bottom nav render
     * the same options). The visible "N. Title" text has its leading number stripped. Chapter URLs
     * are reconstructed as `/works/{workId}/chapters/{chapterId}`.
     *
     * A single-chapter work has no dropdown; it degrades to one chapter whose id is the work id
     * (stable, and distinct from any real chapter id) pointing at the work URL.
     */
    fun parseWorkChapters(
        workId: Long,
        workTitle: String,
        workAuthor: String?,
        html: String
    ): Ao3Catalog {
        val select = SELECTED_ID_BLOCK.find(html)?.groupValues?.get(1)
        val seen = LinkedHashMap<Long, Ao3ChapterRef>()
        if (select != null) {
            for (m in OPTION.findAll(select)) {
                val chapterId = m.groupValues[1].toLongOrNull() ?: continue
                if (seen.containsKey(chapterId)) continue
                val label = Html.toText(m.groupValues[2]).trim()
                val title = CHAPTER_NUMBER_PREFIX.replace(label, "")
                    .trim()
                    .ifBlank { "Chapter ${seen.size + 1}" }
                seen[chapterId] = Ao3ChapterRef(
                    chapterId = chapterId,
                    ordinal = seen.size,
                    title = title,
                    url = "/works/$workId/chapters/$chapterId"
                )
            }
        }
        val chapters = if (seen.isEmpty()) {
            // Single-chapter work: no dropdown. One chapter, keyed by the work id, read at the work URL.
            listOf(Ao3ChapterRef(chapterId = workId, ordinal = 0, title = workTitle, url = "/works/$workId"))
        } else {
            seen.values.toList()
        }
        return Ao3Catalog(workId, workTitle, workAuthor, chapters)
    }

    /**
     * Extract a single chapter page into flowing text. AO3 wraps the chapter body in a
     * `<div role="article" class="userstuff …">`; this pulls that block and reduces it via the shared
     * [Html] reducer, so AO3 text anchors the same way EPUB text does. Crucially it selects the
     * `role="article"` div, not just any `userstuff` element — the work **summary** and **notes** are
     * also `userstuff` (in `<blockquote>`s) and must not leak into the chapter.
     */
    fun extractChapter(html: String): ExtractedChapter {
        val title = chapterHeading(html) ?: "Untitled chapter"
        val body = contentDiv(html) ?: html
        return ExtractedChapter(title = title, text = Html.toText(body))
    }

    /** Assemble a [Chapter] at [ref]'s ordinal from an already-fetched chapter page. */
    fun toChapter(ref: Ao3ChapterRef, html: String): Chapter {
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
     * The chapter's own title heading (`<h3 class="title">…</h3>`), reduced to text. AO3 renders the
     * chapter title there (e.g. "Chapter 3: The Long Road"); this ignores the site's other headings.
     */
    private fun chapterHeading(html: String): String? =
        Regex("(?is)<h3\\b[^>]*class\\s*=\\s*[\"'][^\"']*\\btitle\\b[^\"']*[\"'][^>]*>(.*?)</h3>")
            .find(html)?.groupValues?.get(1)
            ?.let { Html.toText(it).trim() }
            ?.takeIf { it.isNotBlank() }

    /**
     * Return the inner HTML of the first `<div>` that is both `role="article"` and carries a
     * `userstuff` class — AO3's chapter body — balancing `<div>` nesting so a content block with
     * nested divs isn't truncated early. Falls back to `null` (caller uses the whole document) when
     * the article div is absent.
     */
    private fun contentDiv(html: String): String? {
        val divOpen = Regex("(?is)<div\\b[^>]*>")
        var openMatch: MatchResult? = divOpen.find(html)
        while (openMatch != null) {
            if (isChapterArticleTag(openMatch.value)) break
            openMatch = openMatch.next()
        }
        val open = openMatch ?: return null
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

    private val ROLE_ARTICLE = Regex("(?i)role\\s*=\\s*[\"']article[\"']")
    private fun isChapterArticleTag(openTag: String): Boolean =
        openTag.contains("userstuff", ignoreCase = true) && ROLE_ARTICLE.containsMatchIn(openTag)
}
