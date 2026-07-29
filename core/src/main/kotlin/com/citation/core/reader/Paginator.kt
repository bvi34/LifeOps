package com.citation.core.reader

/**
 * The pure page-breaking brain behind the reader's **page-turning** mode. Given a chapter that has
 * already been laid out into lines (the caller measures the text with the real typography and
 * viewport width), this decides where each screen-page begins so that page turns move through a
 * chapter one screenful at a time — not just at chapter boundaries.
 *
 * It's deliberately framework-independent: the Compose layer measures the text and hands us abstract
 * line metrics (tops, bottoms, and each line's first character), so the actual splitting is unit
 * tested without a device. The result is a list of **character offsets** — one per page — which the
 * reader slices the chapter text at. Character offsets (not pixels) are what get persisted, so a
 * resumed book lands on the same page regardless of font size.
 */
object Paginator {

    /**
     * The char offset at which each page starts, always beginning with `0`. A page spans from its
     * start up to the next page's start (the last page runs to the end of the text).
     *
     * Page 0 is given [firstCapacityPx] of vertical room (the first page carries the chapter title, so
     * it holds a little less); every later page gets [capacityPx]. A line is kept on the current page
     * while its bottom still fits the running budget; the first line that would overflow opens the next
     * page. A single line taller than a page never yields an empty page — it always advances by at
     * least one line, so pagination can't stall.
     *
     * @param lineCount number of laid-out lines (0 → a single empty page `[0]`).
     * @param lineBottom bottom Y of line *i* in the full layout's coordinate space.
     * @param lineStartChar the first character index of line *i*.
     * @param lineTop top Y of line *i* (used as the page's origin when a new page opens).
     */
    fun pageStarts(
        lineCount: Int,
        lineTop: (Int) -> Float,
        lineBottom: (Int) -> Float,
        lineStartChar: (Int) -> Int,
        firstCapacityPx: Float,
        capacityPx: Float
    ): List<Int> {
        if (lineCount <= 0) return listOf(0)
        val first = firstCapacityPx.coerceAtLeast(1f)
        val rest = capacityPx.coerceAtLeast(1f)

        val starts = ArrayList<Int>()
        starts.add(lineStartChar(0))
        var pageOriginY = lineTop(0)
        var capacity = first
        var pageStartLine = 0

        for (line in 0 until lineCount) {
            val overflows = lineBottom(line) - pageOriginY > capacity
            // Only break *before* a line that overflows, and never on the page's own first line
            // (that would strand an empty page and never make progress).
            if (overflows && line > pageStartLine) {
                starts.add(lineStartChar(line))
                pageOriginY = lineTop(line)
                capacity = rest
                pageStartLine = line
            }
        }
        return starts
    }
}
