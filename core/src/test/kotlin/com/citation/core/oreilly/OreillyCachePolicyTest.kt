package com.citation.core.oreilly

import com.citation.core.manifest.Recoverability
import com.citation.core.store.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OreillyCachePolicyTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100L * day

    @Test
    fun warmCacheIsAlwaysDisposableAndReclaimable() {
        // The one invariant: a licensed warm cache can never be classified as owned/sovereign.
        assertEquals(Store.DISPOSABLE, OreillyCachePolicy.store())
        assertEquals(Recoverability.RECLAIMABLE, OreillyCachePolicy.recoverability())
    }

    @Test
    fun freshCacheIsNotStale() {
        assertFalse(OreillyCachePolicy.isStale(lastWarmedAt = now - 3 * day, now = now))
    }

    @Test
    fun staleExactlyAtTheRetentionBoundary() {
        assertTrue(OreillyCachePolicy.isStale(lastWarmedAt = now - 7 * day, now = now))
    }

    @Test
    fun customRetentionIsHonoured() {
        assertFalse(OreillyCachePolicy.isStale(now - 2 * day, now, retentionDays = 3))
        assertTrue(OreillyCachePolicy.isStale(now - 3 * day, now, retentionDays = 3))
    }

    @Test
    fun purgesAStaleCacheWhenTheReaderIsClosed() {
        assertTrue(OreillyCachePolicy.shouldPurge(lastWarmedAt = now - 8 * day, isReaderOpen = false, now = now))
    }

    @Test
    fun neverPurgesWhileTheReaderIsOpen() {
        assertFalse(OreillyCachePolicy.shouldPurge(lastWarmedAt = now - 30 * day, isReaderOpen = true, now = now))
    }

    @Test
    fun neverPurgesAFreshCache() {
        assertFalse(OreillyCachePolicy.shouldPurge(lastWarmedAt = now - 1 * day, isReaderOpen = false, now = now))
    }

    @Test
    fun nothingToPurgeWhenNeverWarmed() {
        assertFalse(OreillyCachePolicy.shouldPurge(lastWarmedAt = null, isReaderOpen = false, now = now))
    }
}
