package com.citation.core.pdf

import com.citation.core.model.Book
import com.citation.core.model.BookMetadata
import com.citation.core.model.Chapter
import com.citation.core.model.SourceType

/**
 * The **reflow track for PDFs**: turn a PDF's extracted per-page text into the same [Book] the
 * flowing reader already renders, so a PDF can be read — and *selected from* — like any other book.
 *
 * Why this exists alongside [PdfTrack]: a rendered PDF page is a picture of glyphs. There is no text
 * to select, so a highlight on the paged track can only ever be a rectangle, and the note composer
 * has nothing to quote. Reflowing gives you real characters: selection, search, typography, and a
 * quote that [com.citation.core.anchor.FuzzyAnchor] can re-resolve.
 *
 * The reflow is **derived, never authoritative**. The imported file stays the ground truth and the
 * paged track stays available, because reflow is lossy in ways no heuristic fixes: multi-column
 * papers interleave, tables and equations flatten, and a scanned PDF has no text layer at all. So
 * this deliberately *declines* rather than guesses — [build] returns `null` when there isn't enough
 * text to be worth reading, and the caller keeps the PDF paged-only.
 *
 * ### Pages survive the reflow
 * A reflowed chapter spans several pages, but a note captured in it must still say *which page* —
 * that's the citation, and it's what lets the note jump back to the paged view. So each chapter's
 * [Chapter.sourceRef] encodes its first page plus the character offset where every page begins
 * (see [encodeRef]), and [pageOf] maps any offset in the chapter text back to a 0-based page. A
 * flowing selection therefore still yields a [com.citation.core.anchor.TextAnchor.Pdf] — the anchor
 * type the PDF track already uses — with no new anchor shape and no change to the sync contract.
 *
 * Everything here is pure text→text, so the cleanup heuristics are provable on the JVM; the Android
 * layer only supplies the extracted page strings.
 */
object PdfFlow {

    /** Marks a [Chapter.sourceRef] produced by this reflow, and prefixes its page map. */
    const val REF_PREFIX = "pdf-flow:"

    /** Target size of one reflowed chapter, in characters — a comfortable "chapter" of a page turn. */
    const val DEFAULT_TARGET_CHARS = 12_000

    // A PDF with less text than this is a scan (or a picture book): reflowing it yields a blank
    // reader, which is worse than honestly staying on the paged track.
    private const val MIN_TOTAL_CHARS = 200
    private const val MIN_CHARS_PER_PAGE = 24

    /**
     * Whether [pages] carry a usable text layer. A scanned PDF extracts to nothing (or to a few
     * stray characters from a text cover page), and that must be detected *before* building a book
     * so the UI can say "no text layer" instead of opening an empty reader.
     */
    fun hasTextLayer(pages: List<String>): Boolean {
        if (pages.isEmpty()) return false
        val total = pages.sumOf { page -> page.count { !it.isWhitespace() } }
        return total >= MIN_TOTAL_CHARS && total >= MIN_CHARS_PER_PAGE * pages.size
    }

    /**
     * Reflow [pages] (page 0 first, as extracted) into a [Book] of flowing chapters, or `null` when
     * the PDF has no usable text layer. The book is unkeyed — the caller mints and stores it exactly
     * as it would a parsed EPUB.
     */
    fun build(
        pages: List<String>,
        title: String,
        author: String? = null,
        targetChars: Int = DEFAULT_TARGET_CHARS
    ): Book? {
        if (!hasTextLayer(pages)) return null
        val running = runningLines(pages)
        val cleaned = pages.map { clean(it, running) }
        val chapters = chunk(cleaned, targetChars)
        if (chapters.none { it.text.isNotBlank() }) return null
        return Book(
            key = null,
            metadata = BookMetadata(title = title, author = author, source = SourceType.PDF),
            chapters = chapters
        )
    }

    // --- The page map: chapter offset ↔ PDF page ------------------------------------------------

    /**
     * Encode a chapter's page map into its [Chapter.sourceRef]: the 0-based [firstPage] plus the
     * character offset at which each covered page starts within the chapter text.
     *
     * Format: `pdf-flow:p=12;breaks=0,1834,3502`. Opaque to the reader, per the [Chapter.sourceRef]
     * contract — only this object reads it.
     */
    fun encodeRef(firstPage: Int, breaks: List<Int>): String =
        "$REF_PREFIX" + "p=$firstPage;breaks=" + breaks.joinToString(",")

    /** True when [sourceRef] was produced by this reflow (as opposed to an EPUB href or RR URL). */
    fun isFlowRef(sourceRef: String): Boolean = sourceRef.startsWith(REF_PREFIX)

    /** The 0-based PDF page a chapter starts on, or `null` if [sourceRef] isn't a reflow ref. */
    fun firstPage(sourceRef: String): Int? = field(sourceRef, "p")?.toIntOrNull()

    /**
     * The 0-based PDF page that character [offset] of the chapter falls on — the page a flowing
     * selection cites. Returns `null` for a non-reflow ref. Offsets before the first break clamp to
     * the chapter's first page, and anything past the last break belongs to the last page.
     */
    fun pageOf(sourceRef: String, offset: Int): Int? {
        val first = firstPage(sourceRef) ?: return null
        val breaks = field(sourceRef, "breaks")
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return first
        // The last page whose text starts at or before the offset. Equal breaks (a blank page
        // contributes no characters) resolve to the later page — the one the text actually starts on.
        val index = breaks.indexOfLast { it <= offset }.coerceAtLeast(0)
        return first + index
    }

    /** [pageOf] for a chapter of a reflowed book. */
    fun pageOf(chapter: Chapter, offset: Int): Int? = pageOf(chapter.sourceRef, offset)

    /**
     * The inverse: which chapter of [chapters] covers 0-based PDF [page]. This is what lets the
     * reader switch tracks without losing your place — leave the paged view on page 92 and the
     * flowing view opens at the chapter that holds page 92. Falls back to the first chapter for a
     * page before the reflow's range, and the last for one past it.
     */
    fun chapterForPage(chapters: List<Chapter>, page: Int): Int {
        if (chapters.isEmpty()) return 0
        val index = chapters.indexOfLast { chapter ->
            val first = firstPage(chapter.sourceRef)
            first != null && first <= page
        }
        return index.coerceIn(0, chapters.lastIndex)
    }

    private fun field(ref: String, name: String): String? {
        if (!isFlowRef(ref)) return null
        return ref.removePrefix(REF_PREFIX)
            .split(';')
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
    }

    // --- Chunking ------------------------------------------------------------------------------

    /**
     * Group cleaned pages into chapters of roughly [targetChars], never splitting a page across two
     * chapters (a page is the atom the citation is made of), and record each page's start offset.
     */
    private fun chunk(pages: List<String>, targetChars: Int): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val text = StringBuilder()
        val breaks = mutableListOf<Int>()
        var firstPage = 0

        fun flush(lastPage: Int) {
            if (breaks.isEmpty()) return
            chapters += Chapter(
                ordinal = chapters.size,
                title = titleFor(firstPage, lastPage),
                sourceRef = encodeRef(firstPage, breaks.toList()),
                text = text.toString()
            )
            text.setLength(0)
            breaks.clear()
        }

        pages.forEachIndexed { pageIndex, page ->
            if (breaks.isEmpty()) firstPage = pageIndex
            if (text.isNotEmpty() && page.isNotEmpty()) text.append("\n\n")
            breaks += text.length
            text.append(page)
            if (text.length >= targetChars) flush(pageIndex)
        }
        flush(pages.lastIndex)
        return chapters
    }

    /** Human chapter label: pages are 1-based on screen, matching what's printed on the page. */
    private fun titleFor(firstPage: Int, lastPage: Int): String =
        if (lastPage <= firstPage) "Page ${firstPage + 1}" else "Pages ${firstPage + 1}–${lastPage + 1}"

    // --- Page cleanup --------------------------------------------------------------------------

    /**
     * Reduce one extracted page to flowing paragraphs: drop the running header/footer and standalone
     * page numbers, rejoin words a line break hyphenated, and merge wrapped lines back into
     * paragraphs.
     *
     * The paragraph rule is the classic one for extracted text: a paragraph ends at a blank line, or
     * at a line noticeably shorter than the page's full measure (the last line of a paragraph doesn't
     * reach the right margin). It is a heuristic and it is wrong on tables and verse — which is
     * exactly why the paged track stays one tap away.
     */
    private fun clean(page: String, running: Set<String>): String {
        val raw = page.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trim() }
        val edges = edgeIndices(raw)
        val body = raw.filterIndexed { index, line ->
            when {
                index !in edges -> true
                normalize(line) in running -> false
                isPageNumber(line) -> false
                else -> true
            }
        }

        val measure = body.filter { it.isNotBlank() }.maxOfOrNull { it.length } ?: 0
        // A line under ~72% of the page's widest line reads as the end of a paragraph, not a wrap.
        val shortLine = measure * 0.72

        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) paragraphs += current.toString().trim()
            current.setLength(0)
        }
        for (line in body) {
            if (line.isBlank()) { flush(); continue }
            when {
                current.isEmpty() -> current.append(line)
                // "inter-\nnational" was one word before the line broke it.
                current.endsWith("-") && line.firstOrNull()?.isLowerCase() == true -> {
                    current.setLength(current.length - 1)
                    current.append(line)
                }
                else -> current.append(' ').append(line)
            }
            if (line.length < shortLine) flush()
        }
        flush()
        return paragraphs.joinToString("\n\n")
    }

    /** The first two and last two non-blank line positions — where headers, footers, and folios live. */
    private fun edgeIndices(lines: List<String>): Set<Int> {
        val filled = lines.indices.filter { lines[it].isNotBlank() }
        return (filled.take(2) + filled.takeLast(2)).toSet()
    }

    /**
     * The header/footer lines repeated across the document (a book title, a chapter name, a folio).
     * Digits are masked before comparing, so "Chapter 3 · 47" and "Chapter 3 · 48" count as the same
     * running line. Needs a few pages to be confident — a short PDF is left alone.
     *
     * A candidate must be **short** relative to its page's widest line as well as repeated and at the
     * page's edge. That third condition is what keeps the rule from eating prose: a running head is a
     * stub above the text block, while a body line at the top of a page runs the full measure.
     */
    private fun runningLines(pages: List<String>): Set<String> {
        if (pages.size < 4) return emptySet()
        val counts = mutableMapOf<String, Int>()
        for (page in pages) {
            val lines = page.replace("\r\n", "\n").split('\n').map { it.trim() }
            val measure = lines.filter { it.isNotBlank() }.maxOfOrNull { it.length } ?: 0
            val candidates = edgeIndices(lines)
                .map { lines[it] }
                .filter { it.isNotBlank() && it.length <= 90 && it.length <= measure * 0.6 }
                .map { normalize(it) }
                .toSet()
            for (candidate in candidates) counts[candidate] = (counts[candidate] ?: 0) + 1
        }
        val threshold = maxOf(3, pages.size / 2)
        return counts.filterValues { it >= threshold }.keys
    }

    private fun normalize(line: String): String =
        line.trim().lowercase().replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ")

    private val PAGE_NUMBER = Regex("^[\\[(\\-–—|·•\\s]*(?:page\\s*)?(\\d{1,4}|[ivxlcdm]{1,7})[\\])\\-–—|·•\\s]*$", RegexOption.IGNORE_CASE)

    /** A line that is only a folio: "12", "- 12 -", "Page 12", "xiv". */
    private fun isPageNumber(line: String): Boolean =
        line.isNotBlank() && PAGE_NUMBER.matches(line)
}
