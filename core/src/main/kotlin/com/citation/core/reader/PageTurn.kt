package com.citation.core.reader

/**
 * Where a page-turn gesture actually lands — the one decision behind every swipe, edge tap and volume
 * press in the reader.
 *
 * A turn is one *page*, and its inverse is one page back. That sounds too obvious to write down until
 * a chapter boundary is involved: a book is paginated per chapter, so turning back off a chapter's
 * first page means entering the chapter before it. Entering it at its *first* page — the obvious
 * implementation, and the one this replaces — throws away the whole chapter you were about to read
 * backwards into and dumps you at a place you last saw an hour ago. The turn stops being reversible:
 * forward-then-back no longer returns you to where you were. So a backward crossing lands on the
 * previous chapter's **last** page, and only an explicit chapter jump (the Previous button, the
 * contents list) opens a chapter at its beginning.
 *
 * Kept pure and framework-free so the rule is unit tested rather than eyeballed on a device: the
 * Compose layer measures where the pages are ([Paginator]) and this decides which one you get.
 */
object PageTurn {

    /** Which end of a chapter a crossing arrives at. */
    enum class Landing {
        /** Reading forwards: open the chapter at its first page. */
        FIRST_PAGE,

        /** Reading backwards: open the chapter at its last page, so the turn is one page, not a chapter. */
        LAST_PAGE
    }

    /** What a turn resolves to. */
    sealed interface Move {
        /** Stay in this chapter, on [page]. */
        data class Page(val page: Int) : Move

        /** Cross into [chapter], arriving at [landing]. */
        data class Chapter(val chapter: Int, val landing: Landing) : Move

        /** Nowhere to go: the first page of the book, or the last. The gesture is a no-op. */
        data object Edge : Move
    }

    /**
     * The next page: the following page of this chapter, else the start of the next chapter.
     *
     * @param page the current page, 0-based.
     * @param lastPage the last page index of this chapter (0 when the chapter is a single page).
     * @param chapter the current chapter ordinal.
     * @param lastChapter the last chapter ordinal in the book.
     */
    fun next(page: Int, lastPage: Int, chapter: Int, lastChapter: Int): Move = when {
        page < lastPage -> Move.Page(page + 1)
        chapter < lastChapter -> Move.Chapter(chapter + 1, Landing.FIRST_PAGE)
        else -> Move.Edge
    }

    /**
     * The previous page: the preceding page of this chapter, else the **end** of the chapter before
     * it — the exact inverse of the turn that got you here.
     */
    fun previous(page: Int, lastPage: Int, chapter: Int, lastChapter: Int): Move = when {
        page > 0 -> Move.Page((page - 1).coerceAtMost(lastPage))
        chapter > 0 -> Move.Chapter(chapter - 1, Landing.LAST_PAGE)
        else -> Move.Edge
    }
}
