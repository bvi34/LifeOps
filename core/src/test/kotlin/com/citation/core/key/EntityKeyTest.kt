package com.citation.core.key

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityKeyTest {

    @Test
    fun canonicalStringForm() {
        assertEquals("ER-Book-3", EntityKey("ER", "Book", 3).toString())
        assertEquals("ER-Note-88", EntityKey("ER", "Note", 88).toString())
    }

    @Test
    fun roundTripParse() {
        val key = EntityKey("ER", "Highlight", 12)
        assertEquals(key, EntityKey.parse(key.toString()))
    }

    @Test
    fun malformedKeysParseToNull() {
        assertNull(EntityKey.parse("ER-Book"))
        assertNull(EntityKey.parse("ER-Book-x"))
        assertNull(EntityKey.parse("not a key"))
    }

    @Test
    fun allocatorMintsMonotonicallyPerType() {
        val alloc = KeyAllocator("ER")
        assertEquals("ER-Book-1", alloc.next(EntityType.BOOK).toString())
        assertEquals("ER-Book-2", alloc.next(EntityType.BOOK).toString())
        // Notes count independently of books.
        assertEquals("ER-Note-1", alloc.next(EntityType.NOTE).toString())
        assertEquals("ER-Book-3", alloc.next(EntityType.BOOK).toString())
    }

    @Test
    fun differentNamespacesNeverCollide() {
        val er = KeyAllocator("ER").next(EntityType.BOOK)
        val lo = KeyAllocator("LO").next(EntityType.BOOK)
        // Same type + sequence, distinct keys — offline minting with zero coordination.
        assertEquals(er.sequence, lo.sequence)
        assertTrue(er != lo)
        assertEquals("ER-Book-1", er.toString())
        assertEquals("LO-Book-1", lo.toString())
    }

    @Test
    fun seedResumesWithoutReuse() {
        val alloc = KeyAllocator("ER", seed = mapOf(EntityType.NOTE to 87L))
        assertEquals("ER-Note-88", alloc.next(EntityType.NOTE).toString())
        assertEquals(88L, alloc.highWater(EntityType.NOTE))
    }

    @Test
    fun seedFromNeverRewinds() {
        val alloc = KeyAllocator("ER")
        alloc.next(EntityType.BOOK); alloc.next(EntityType.BOOK) // high water 2
        alloc.seedFrom(EntityType.BOOK, 1) // lower — ignored
        assertEquals("ER-Book-3", alloc.next(EntityType.BOOK).toString())
    }
}
