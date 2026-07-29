package com.citation.core.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PaginatorTest {

    /** Uniform 10px lines, 4 chars each, at char offsets 0,4,8,… — the simple grid case. */
    private fun starts(lineCount: Int, lineHeight: Float, firstCap: Float, cap: Float): List<Int> =
        Paginator.pageStarts(
            lineCount = lineCount,
            lineTop = { it * lineHeight },
            lineBottom = { (it + 1) * lineHeight },
            lineStartChar = { it * 4 },
            firstCapacityPx = firstCap,
            capacityPx = cap
        )

    @Test
    fun emptyTextIsASinglePage() {
        assertEquals(listOf(0), starts(lineCount = 0, lineHeight = 10f, firstCap = 100f, cap = 100f))
    }

    @Test
    fun everythingFitsOnOnePage() {
        // 3 lines of 10px fit in a 100px page.
        assertEquals(listOf(0), starts(lineCount = 3, lineHeight = 10f, firstCap = 100f, cap = 100f))
    }

    @Test
    fun breaksIntoPagesOfWholeLines() {
        // 10px lines, 25px pages → 2 lines per page. 5 lines → pages start at line 0, 2, 4.
        val s = starts(lineCount = 5, lineHeight = 10f, firstCap = 25f, cap = 25f)
        assertEquals(listOf(0, 8, 16), s) // line 0→char0, line 2→char8, line 4→char16
    }

    @Test
    fun firstPageHoldsFewerLinesForTheTitle() {
        // First page only 15px (1 line) for the title; later pages 25px (2 lines).
        val s = starts(lineCount = 5, lineHeight = 10f, firstCap = 15f, cap = 25f)
        assertEquals(listOf(0, 4, 12), s) // page0: line0; page1: lines1-2; page2: lines3-4
    }

    @Test
    fun aLineTallerThanThePageStillAdvances() {
        // Each line 40px but the page is only 25px — every line must get its own page, never stall.
        val s = starts(lineCount = 3, lineHeight = 40f, firstCap = 25f, cap = 25f)
        assertEquals(listOf(0, 4, 8), s)
    }
}
