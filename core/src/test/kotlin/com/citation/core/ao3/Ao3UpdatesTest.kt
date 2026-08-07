package com.citation.core.ao3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ao3UpdatesTest {

    private fun ref(id: Long, ordinal: Int) =
        Ao3ChapterRef(chapterId = id, ordinal = ordinal, title = "Ch $ordinal", url = "/works/1/chapters/$id")

    private fun catalog(vararg ids: Long) =
        Ao3Catalog(workId = 1, title = "W", author = "A", chapters = ids.mapIndexed { i, id -> ref(id, i) })

    @Test
    fun returnsOnlyGenuinelyNewChaptersOldestFirst() {
        val current = catalog(100, 101, 102, 103)
        val known = setOf(100L, 101L)
        val new = Ao3Updates.detectNewChapters(current, known)
        assertEquals(listOf(102L, 103L), new.map { it.chapterId })
        // Ascending reading order, so the body puller fetches them in order.
        assertEquals(listOf(2, 3), new.map { it.ordinal })
    }

    @Test
    fun nothingNewWhenCatalogUnchanged() {
        val current = catalog(100, 101)
        assertTrue(Ao3Updates.detectNewChapters(current, setOf(100L, 101L)).isEmpty())
    }

    @Test
    fun allNewWhenNothingKnownYet() {
        val current = catalog(100, 101, 102)
        assertEquals(3, Ao3Updates.detectNewChapters(current, emptySet()).size)
    }
}
