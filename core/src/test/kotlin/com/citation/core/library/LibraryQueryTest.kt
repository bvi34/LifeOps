package com.citation.core.library

import com.citation.core.model.SourceType
import com.citation.core.sync.ReadingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arranging a shelf of hundreds. Every case here is one a real library hits within a week of being
 * connected to a catalog: leading articles, initials, half-read piles, a series out of order.
 */
class LibraryQueryTest {

    private fun entry(
        key: String,
        title: String,
        author: String? = null,
        series: String? = null,
        seriesIndex: Float? = null,
        subjects: List<String> = emptyList(),
        source: SourceType = SourceType.EPUB,
        reading: ReadingState = ReadingState.TO_READ,
        addedAt: Long = 0,
        lastOpenedAt: Long? = null,
        chapter: Int = 0,
        chapters: Int = 0,
        favorite: Boolean = false,
        collections: Set<String> = emptySet()
    ) = LibraryEntry(
        key = key, title = title, author = author, series = series, seriesIndex = seriesIndex,
        subjects = subjects, sourceType = source, readingState = reading, addedAt = addedAt,
        lastOpenedAt = lastOpenedAt, lastChapterOrdinal = chapter, chapterCount = chapters,
        isFavorite = favorite, collectionIds = collections
    )

    private val shelf = listOf(
        entry("B-1", "The Time Machine", "H. G. Wells", subjects = listOf("Science Fiction"), addedAt = 300, lastOpenedAt = 900, chapter = 4, chapters = 16),
        entry("B-2", "Leviathan Wakes", "James S. A. Corey", series = "The Expanse", seriesIndex = 1f, subjects = listOf("Science Fiction", "Space Opera"), addedAt = 200),
        entry("B-3", "Caliban's War", "James S. A. Corey", series = "The Expanse", seriesIndex = 2f, addedAt = 100, lastOpenedAt = 500, chapter = 1, chapters = 50, reading = ReadingState.READING),
        entry("B-4", "A Deepness in the Sky", "Vernor Vinge", subjects = listOf("Science Fiction"), addedAt = 400, reading = ReadingState.DONE, favorite = true, collections = setOf("C-1")),
        entry("B-5", "Some Manual", source = SourceType.PDF, addedAt = 500, subjects = listOf("Reference"))
    )

    @Test
    fun `titles alphabetise past their leading article`() {
        val titles = LibraryQuery.apply(shelf, sort = LibrarySort.TITLE).map { it.title }
        assertEquals(
            listOf("Caliban's War", "A Deepness in the Sky", "Leviathan Wakes", "Some Manual", "The Time Machine"),
            titles
        )
    }

    @Test
    fun `authors sort by surname`() {
        assertEquals("Wells, H. G.", LibrarySorting.surnameFirst("H. G. Wells"))
        assertEquals("Corey, James S. A.", LibrarySorting.surnameFirst("James S. A. Corey"))
        // Already surname-first, and single names, are left alone.
        assertEquals("Wells, H. G.", LibrarySorting.surnameFirst("Wells, H. G."))
        assertEquals("Homer", LibrarySorting.surnameFirst("Homer"))
        assertEquals("", LibrarySorting.surnameFirst(null))
    }

    @Test
    fun `a series reads in order, with standalone books after it`() {
        val ordered = LibraryQuery.apply(shelf, sort = LibrarySort.SERIES).map { it.title }
        assertEquals(listOf("Leviathan Wakes", "Caliban's War"), ordered.take(2))
        assertTrue(ordered.drop(2).containsAll(listOf("A Deepness in the Sky", "Some Manual", "The Time Machine")))
    }

    @Test
    fun `recently read puts the last thing you opened first and the unopened last`() {
        val ordered = LibraryQuery.apply(shelf, sort = LibrarySort.RECENT).map { it.key }
        assertEquals(listOf("B-1", "B-3"), ordered.take(2))
        // The never-opened ones follow, most recently added first.
        assertEquals(listOf("B-5", "B-4", "B-2"), ordered.drop(2))
    }

    @Test
    fun `progress is chapter granular and honest about unopened books`() {
        assertEquals(0f, shelf.first { it.key == "B-2" }.progress)          // never opened
        assertEquals(5f / 16f, shelf.first { it.key == "B-1" }.progress)    // chapter 5 of 16
        assertEquals(1f, shelf.first { it.key == "B-4" }.progress)          // finished
    }

    @Test
    fun `the reader's own measurement wins over the chapter estimate`() {
        // Chapter 5 of 16 is 31% by chapter count, but the reader measured 12% in characters —
        // front matter is short. The shelf must quote the number the page quoted.
        val measured = shelf.first { it.key == "B-1" }.copy(measuredProgress = 0.12f)
        assertEquals(0.12f, measured.progress)
        assertTrue(measured.progressIsMeasured)
    }

    @Test
    fun `a book not opened since the measurement existed falls back to chapters`() {
        val entry = shelf.first { it.key == "B-1" }
        assertEquals(5f / 16f, entry.progress)
        assertFalse(entry.progressIsMeasured)
    }

    @Test
    fun `a finished book reads finished however it was measured`() {
        val done = shelf.first { it.key == "B-4" }.copy(measuredProgress = 0.4f)
        assertEquals(1f, done.progress)
    }

    @Test
    fun `an unopened book reads zero even if a stale measurement lingers`() {
        val unopened = shelf.first { it.key == "B-2" }.copy(measuredProgress = 0.5f)
        assertEquals(0f, unopened.progress)
    }

    @Test
    fun `progress sorting surfaces the half-read pile and buries the finished`() {
        val ordered = LibraryQuery.apply(shelf, sort = LibrarySort.PROGRESS).map { it.key }
        assertEquals("B-1", ordered.first())
        assertEquals("B-4", ordered.last())
    }

    @Test
    fun `search narrows as tokens are added`() {
        fun find(q: String) = LibraryQuery.apply(shelf, LibraryFilter(query = q)).map { it.key }
        assertEquals(listOf("B-2", "B-3"), find("corey").sorted())
        assertEquals(listOf("B-2"), find("corey leviathan"))
        assertTrue(find("corey leviathan wells").isEmpty())
    }

    @Test
    fun `search reaches series and subjects, not just the title`() {
        assertEquals(2, LibraryQuery.apply(shelf, LibraryFilter(query = "expanse")).size)
        assertEquals(3, LibraryQuery.apply(shelf, LibraryFilter(query = "science fiction")).size)
    }

    @Test
    fun `filters compose`() {
        val filtered = LibraryQuery.apply(
            shelf,
            LibraryFilter(query = "corey", reading = ReadingState.READING)
        )
        assertEquals(listOf("B-3"), filtered.map { it.key })
    }

    @Test
    fun `a collection shows only what is on it`() {
        assertEquals(listOf("B-4"), LibraryQuery.apply(shelf, LibraryFilter(collectionId = "C-1")).map { it.key })
        assertTrue(LibraryQuery.apply(shelf, LibraryFilter(collectionId = "C-9")).isEmpty())
    }

    @Test
    fun `source and favourites narrow the shelf`() {
        assertEquals(listOf("B-5"), LibraryQuery.apply(shelf, LibraryFilter(source = SourceType.PDF)).map { it.key })
        assertEquals(listOf("B-4"), LibraryQuery.apply(shelf, LibraryFilter(favoritesOnly = true)).map { it.key })
    }

    @Test
    fun `an empty filter changes nothing`() {
        assertTrue(LibraryFilter().isEmpty)
        assertEquals(shelf.size, LibraryQuery.apply(shelf).size)
    }

    @Test
    fun `subject facets are counted most used first`() {
        val facets = LibraryQuery.subjectCounts(shelf)
        assertEquals(Facet("Science Fiction", 3), facets.first())
        assertTrue(facets.any { it.name == "Space Opera" && it.count == 1 })
    }

    @Test
    fun `series facets count the volumes held`() {
        assertEquals(listOf(Facet("The Expanse", 2)), LibraryQuery.seriesCounts(shelf))
    }

    @Test
    fun `wanted books can be hidden from views that cannot open them`() {
        val wanted = entry("B-9", "Not Yet Acquired").copy(
            acquisitionState = com.citation.core.sync.AcquisitionState.WANTED
        )
        val all = shelf + wanted
        assertTrue(LibraryQuery.apply(all).any { it.key == "B-9" })
        assertFalse(LibraryQuery.apply(all, LibraryFilter(includeWanted = false)).any { it.key == "B-9" })
    }

    @Test
    fun `a whole number series index reads as an integer`() {
        assertEquals("The Expanse #1", shelf.first { it.key == "B-2" }.seriesLabel)
        assertEquals("Vorkosigan #1.5", entry("x", "t", series = "Vorkosigan", seriesIndex = 1.5f).seriesLabel)
    }
}
