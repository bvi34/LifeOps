package com.citation.core.epub

import com.citation.core.doc.HtmlDocument
import com.citation.core.model.Chapter
import com.citation.core.model.TableOfContents
import com.citation.core.model.TocEntry
import com.citation.core.xml.Xml

/**
 * Gives an **Archive of Our Own** export a front door.
 *
 * AO3's official download is a Calibre EPUB whose first spine document is not the work at all: it
 * is a `<dl class="tags">` of Rating, Archive Warning, Category, Fandoms, Relationships,
 * Characters, Additional Tags, Language and Stats. The *second* document is the real title page —
 * the `<h1>` fic title, a `by <author>` byline, the Summary and the author's Notes. Parsed
 * literally, opening a fic therefore lands you in a catalogue record rather than a story; worse,
 * `<dt>`/`<dd>` are not block elements to the canonical reduction, so the whole record flattens
 * into one unbroken run of text ("Rating: General Audiences Archive Warning: … Fandoms: …").
 *
 * So this pass reshapes what the parser recovered, without discarding any of it:
 *
 *  - the tag block is **rewritten** into one labelled paragraph per group and **moved to the end**
 *    of the book as a section plainly titled *Work Details*, so it reads as metadata and can never
 *    be mistaken for the fic;
 *  - the title page becomes the first thing you open, titled *fic title — author* so the contents
 *    and the "where am I" line both name the work the way a reader would;
 *  - the contents document is remapped to the new order, and gains an entry for the title page,
 *    which AO3's own `toc.ncx` omits entirely.
 *
 * Detection is on the markup (a `tags` definition list plus an `archiveofourown.org` reference or
 * the AO3 publisher line), not on where a book was downloaded from, so a manually-downloaded AO3
 * EPUB imported through the ordinary file picker is reshaped too.
 *
 * Like the rest of the parser this degrades rather than throws: anything it cannot recognise leaves
 * the book exactly as the spine stated it, by returning `null`.
 *
 * Rewriting a chapter's text is only safe *because* this runs during parsing, before the book has
 * a key — notes anchor against chapters as stored, and no note can exist for a book not yet
 * imported. It is not something that may be applied to an already-imported book.
 */
internal object Ao3Export {

    /** What the relocated tag block is called, both as a chapter and in the contents. */
    const val DETAILS_TITLE = "Work Details"

    /** @property chapters reading order after the move. @property toc contents remapped to it. */
    data class Reshaped(val chapters: List<Chapter>, val toc: TableOfContents)

    /**
     * Whether [documents] — the first few content documents of the spine, where AO3 always puts its
     * record page — came out of an Archive of Our Own download. [publisher] is the OPF's, used as a
     * corroborating signal so a work whose links were rewritten is still recognised.
     */
    fun isExport(publisher: String?, documents: List<String>): Boolean {
        val record = documents.firstOrNull { TAG_BLOCK.containsMatchIn(it) } ?: return false
        return isAo3(publisher) || ARCHIVE.containsMatchIn(record)
    }

    /**
     * Just the `<body>` of an AO3 content document.
     *
     * Every document in an export carries the same `<head><title>fic - author - every fandom</title>`,
     * and the canonical reduction has no notion of a document head — it strips the tags and keeps
     * the text, so that line would be printed above the first words of *every* chapter. Dropping
     * the head is done here, at parse time and for AO3 only, rather than in the reduction itself,
     * which is shared by every source and pinned character-for-character by
     * [com.citation.core.doc.HtmlReductionParityTest].
     */
    fun body(html: String): String =
        Xml.elements(html, "body").firstOrNull()?.inner?.takeIf { it.isNotBlank() } ?: html

    /**
     * Reshape [chapters] and [toc], or `null` when [isExport] is false or the record page turns out
     * not to be one. [author] is the OPF's, used for the title-page label.
     */
    fun reshape(
        chapters: List<Chapter>,
        toc: TableOfContents,
        author: String?,
        isExport: Boolean
    ): Reshaped? {
        if (!isExport) return null
        // A lone chapter is a tag block with nothing to put in front of — leave it alone.
        if (chapters.size < 2) return null
        val tagsAt = chapters.indexOfFirst { TAG_BLOCK.containsMatchIn(it.html.orEmpty()) }
        if (tagsAt < 0) return null
        val details = workDetails(chapters[tagsAt]) ?: return null

        val titlePageAt = chapters.indices
            .firstOrNull { it != tagsAt && BYLINE.containsMatchIn(chapters[it].html.orEmpty()) }
        val titlePageLabel = titlePageAt?.let { byline(chapters[it].title, author) }

        // Reading order: everything but the tag block, in spine order, then the tag block last.
        val order = chapters.indices.filter { it != tagsAt } + tagsAt
        val moved = HashMap<Int, Int>(order.size)
        order.forEachIndexed { newOrdinal, old -> moved[old] = newOrdinal }

        val reordered = order.mapIndexed { newOrdinal, old ->
            val chapter = if (old == tagsAt) details else chapters[old]
            val named =
                if (old == titlePageAt && titlePageLabel != null) chapter.copy(title = titlePageLabel)
                else chapter
            if (named.ordinal == newOrdinal) named else named.copy(ordinal = newOrdinal)
        }

        return Reshaped(reordered, contents(toc, moved, tagsAt, titlePageAt, titlePageLabel))
    }

    // --- The work details section ---------------------------------------------------------------

    /**
     * Rewrite the tag block as a readable section: `<h2>Work Details</h2>` and one paragraph per
     * group, the label emphasised and the values run together with a separator. The rewrite goes
     * back through [HtmlDocument] rather than being assembled by hand so the chapter's text, blocks
     * and anchors stay produced by the one reduction everything else in Citation is built on.
     *
     * Returns `null` when the list holds no usable label/value pairs, which means this wasn't the
     * record page after all.
     */
    private fun workDetails(chapter: Chapter): Chapter? {
        val html = chapter.html ?: return null
        val list = Xml.elements(html, "dl")
            .firstOrNull { TAG_CLASS.containsMatchIn(it.open) }
            ?: return null

        val groups = PAIR.findAll(list.inner)
            .map { Html.toText(it.groupValues[1]) to Html.toText(it.groupValues[2]) }
            .filter { (label, value) -> label.isNotBlank() && value.isNotBlank() }
            .toList()
        if (groups.isEmpty()) return null

        val body = StringBuilder("<h2>").append(escape(DETAILS_TITLE)).append("</h2>")
        groups.forEach { (label, value) ->
            body.append("<p><b>").append(escape(labelled(label))).append("</b> ")
                // AO3 puts each statistic on its own source line; the reduction keeps those line
                // breaks, so joining them is what turns "Stats" into one readable line.
                .append(escape(value.lines().joinToString(SEPARATOR) { it.trim() }))
                .append("</p>")
        }
        WORK_URL.find(html)?.value?.let {
            body.append("<p><b>Source:</b> ").append(escape(it)).append("</p>")
        }

        val rewritten = "<div>$body</div>"
        val parsed = HtmlDocument.parse(rewritten)
        return chapter.copy(
            title = DETAILS_TITLE,
            // sourceRef is kept: this is still the spine document it was parsed from.
            text = parsed.text,
            html = rewritten,
            blocks = parsed.blocks,
            anchors = parsed.anchors
        )
    }

    // --- Contents -------------------------------------------------------------------------------

    /**
     * The publisher's contents, remapped: the tag block's entry is lifted out of its old place and
     * re-added last under [DETAILS_TITLE], and the title page — which AO3's `toc.ncx` never
     * lists — is given the entry it should always have had.
     */
    private fun contents(
        toc: TableOfContents,
        moved: Map<Int, Int>,
        tagsAt: Int,
        titlePageAt: Int?,
        titlePageLabel: String?
    ): TableOfContents {
        if (toc.isEmpty) return TableOfContents.EMPTY
        val front =
            if (titlePageAt != null && titlePageLabel != null &&
                toc.flatten().none { (entry, _) -> entry.chapterOrdinal == titlePageAt }
            ) listOf(TocEntry(titlePageLabel, moved[titlePageAt]))
            else emptyList()
        val back = TocEntry(DETAILS_TITLE, moved[tagsAt])
        return TableOfContents(front + remap(toc.entries, moved, tagsAt) + back)
    }

    /** Point every entry at its new ordinal, dropping the one for [drop] but keeping its children. */
    private fun remap(entries: List<TocEntry>, moved: Map<Int, Int>, drop: Int): List<TocEntry> =
        entries.flatMap { entry ->
            val children = remap(entry.children, moved, drop)
            if (entry.chapterOrdinal == drop) children
            else listOf(
                entry.copy(
                    chapterOrdinal = entry.chapterOrdinal?.let { moved[it] },
                    children = children
                )
            )
        }

    // --- Helpers ---------------------------------------------------------------------------------

    /** "A Black Dahlia — Sally_wasHere"; just the title when the export names no author. */
    private fun byline(title: String, author: String?): String {
        val name = author?.trim().orEmpty()
        return if (name.isEmpty() || title.contains(name, ignoreCase = true)) title
        else "$title — $name"
    }

    private fun isAo3(publisher: String?): Boolean =
        publisher?.contains("Archive of Our Own", ignoreCase = true) == true

    /** AO3 writes its labels with the colon; a source that doesn't gets one. */
    private fun labelled(label: String): String =
        if (label.endsWith(":")) label else "$label:"

    /**
     * Re-escape text that has already been through the reduction, so building HTML out of it and
     * reducing it a second time yields exactly the characters AO3 wrote.
     */
    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val SEPARATOR = " · "

    private val TAG_BLOCK = Regex("(?is)<dl\\b[^>]*\\bclass\\s*=\\s*[\"'][^\"']*\\btags\\b[^\"']*[\"']")
    private val TAG_CLASS = Regex("(?is)\\bclass\\s*=\\s*[\"'][^\"']*\\btags\\b[^\"']*[\"']")
    private val BYLINE = Regex("(?is)\\bclass\\s*=\\s*[\"'][^\"']*\\bbyline\\b[^\"']*[\"']")
    private val PAIR = Regex("(?is)<dt\\b[^>]*>(.*?)</dt>\\s*<dd\\b[^>]*>(.*?)</dd>")
    private val ARCHIVE = Regex("(?i)archiveofourown\\.org")
    private val WORK_URL = Regex("(?i)https?://(?:www\\.)?archiveofourown\\.org/works/\\d+")
}
