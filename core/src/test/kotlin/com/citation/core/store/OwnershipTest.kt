package com.citation.core.store

import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnershipTest {

    @Test
    fun borrowedContentIsDisposableOwnedIsSovereign() {
        assertEquals(Store.DISPOSABLE, Ownership.contentStore(SourceType.ROYAL_ROAD))
        assertEquals(Store.SOVEREIGN, Ownership.contentStore(SourceType.EPUB))
        assertEquals(Store.SOVEREIGN, Ownership.contentStore(SourceType.PDF))
        assertEquals(Store.SOVEREIGN, Ownership.contentStore(SourceType.OREILLY))
    }

    @Test
    fun ownedContentIsStructurallyNonEvictable() {
        // No favourite/open flags can make an owned file evictable — it isn't in the disposable store.
        assertFalse(Ownership.isEvictable(SourceType.PDF, isFavorite = false, isOpen = false))
        assertFalse(Ownership.isEvictable(SourceType.EPUB, isFavorite = false, isOpen = false))
    }

    @Test
    fun borrowedNonFavoriteClosedIsEvictable() {
        assertTrue(Ownership.isEvictable(SourceType.ROYAL_ROAD, isFavorite = false, isOpen = false))
    }

    @Test
    fun favoriteOrOpenBorrowedIsExempt() {
        assertFalse(Ownership.isEvictable(SourceType.ROYAL_ROAD, isFavorite = true, isOpen = false))
        assertFalse(Ownership.isEvictable(SourceType.ROYAL_ROAD, isFavorite = false, isOpen = true))
    }

    @Test
    fun notesAreAlwaysSovereign() {
        assertEquals(Store.SOVEREIGN, Ownership.noteStore())
    }
}
