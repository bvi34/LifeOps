package com.citation.core.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PageTurnTest {

    @Test
    fun forwardMovesOnePageWithinTheChapter() {
        assertEquals(
            PageTurn.Move.Page(3),
            PageTurn.next(page = 2, lastPage = 9, chapter = 4, lastChapter = 20)
        )
    }

    @Test
    fun backwardMovesOnePageWithinTheChapter() {
        assertEquals(
            PageTurn.Move.Page(1),
            PageTurn.previous(page = 2, lastPage = 9, chapter = 4, lastChapter = 20)
        )
    }

    @Test
    fun forwardOffTheLastPageOpensTheNextChapterAtItsStart() {
        assertEquals(
            PageTurn.Move.Chapter(5, PageTurn.Landing.FIRST_PAGE),
            PageTurn.next(page = 9, lastPage = 9, chapter = 4, lastChapter = 20)
        )
    }

    /** The reported bug: a page back at a chapter's first page used to dump you at the *start* of
     *  the previous chapter — dozens of pages behind where a single page back should land. */
    @Test
    fun backwardOffTheFirstPageOpensThePreviousChapterAtItsEnd() {
        assertEquals(
            PageTurn.Move.Chapter(3, PageTurn.Landing.LAST_PAGE),
            PageTurn.previous(page = 0, lastPage = 9, chapter = 4, lastChapter = 20)
        )
    }

    @Test
    fun forwardAndBackAreInverseAcrossAChapterBoundary() {
        // Last page of chapter 4 → first page of chapter 5 → back into chapter 4, at its end.
        val forward = PageTurn.next(page = 9, lastPage = 9, chapter = 4, lastChapter = 20)
        assertEquals(PageTurn.Move.Chapter(5, PageTurn.Landing.FIRST_PAGE), forward)
        val back = PageTurn.previous(page = 0, lastPage = 6, chapter = 5, lastChapter = 20)
        assertEquals(PageTurn.Move.Chapter(4, PageTurn.Landing.LAST_PAGE), back)
    }

    @Test
    fun theEndsOfTheBookAreNoOps() {
        assertEquals(PageTurn.Move.Edge, PageTurn.previous(page = 0, lastPage = 4, chapter = 0, lastChapter = 20))
        assertEquals(PageTurn.Move.Edge, PageTurn.next(page = 4, lastPage = 4, chapter = 20, lastChapter = 20))
    }

    @Test
    fun aSinglePageChapterOnlyEverCrosses() {
        assertEquals(
            PageTurn.Move.Chapter(3, PageTurn.Landing.LAST_PAGE),
            PageTurn.previous(page = 0, lastPage = 0, chapter = 4, lastChapter = 20)
        )
        assertEquals(
            PageTurn.Move.Chapter(5, PageTurn.Landing.FIRST_PAGE),
            PageTurn.next(page = 0, lastPage = 0, chapter = 4, lastChapter = 20)
        )
    }

    /** Pages are re-measured when typography changes, so a stale index must not turn into a jump. */
    @Test
    fun aPageIndexBeyondTheChapterClampsInsteadOfCrossing() {
        assertEquals(
            PageTurn.Move.Page(4),
            PageTurn.previous(page = 12, lastPage = 4, chapter = 4, lastChapter = 20)
        )
    }
}
