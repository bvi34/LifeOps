package com.citation.core.rr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchPlanningTest {

    // --- RollingBuffer -------------------------------------------------------------------------

    @Test
    fun bufferKeepsFiveChaptersAheadWhenSizesUnknown() {
        val plan = RollingBuffer.plan(
            currentOrdinal = 2,
            cachedOrdinals = setOf(0, 1, 2, 3),
            totalChapters = 20
        )
        // Window is ordinals 3..7 (5 ahead); 3 is already cached, so fetch 4..7.
        assertEquals(listOf(4, 5, 6, 7), plan)
    }

    @Test
    fun bufferStopsAtEndOfBook() {
        val plan = RollingBuffer.plan(
            currentOrdinal = 8,
            cachedOrdinals = emptySet(),
            totalChapters = 10
        )
        assertEquals(listOf(9), plan)
    }

    @Test
    fun bufferHonoursCharBudgetWhenSizesKnown() {
        // Tiny chapters (10k each): 5 chapters is only 50k — exactly the char budget, so window = 5.
        val sizes = (0..30).associateWith { 10_000 }
        val plan = RollingBuffer.plan(
            currentOrdinal = 0,
            cachedOrdinals = emptySet(),
            totalChapters = 30,
            chapterCharSizes = sizes,
            aheadChapters = 5,
            aheadChars = 50_000
        )
        assertEquals(listOf(1, 2, 3, 4, 5), plan)
    }

    @Test
    fun bufferExtendsPastChapterCountToMeetCharBudget() {
        // Very small chapters (1k): needs many to reach 50k, so window exceeds 5 chapters.
        val sizes = (0..100).associateWith { 1_000 }
        val plan = RollingBuffer.plan(
            currentOrdinal = 0,
            cachedOrdinals = emptySet(),
            totalChapters = 100,
            chapterCharSizes = sizes,
            aheadChapters = 5,
            aheadChars = 50_000
        )
        assertEquals(50, plan.size) // 50 * 1k = 50k
    }

    // --- BackfillPlanner -----------------------------------------------------------------------

    @Test
    fun backfillReconcilesInteriorGapsBeforeTailNew() {
        // Cached 0,1,3,7 of 10; interior gaps 2,4,5,6 (below max cached 7) come before new 8,9.
        val plan = BackfillPlanner.plan(expectedCount = 10, cachedOrdinals = setOf(0, 1, 3, 7))
        assertEquals(listOf(2, 4, 5, 6, 8, 9), plan)
    }

    @Test
    fun backfillIsResumableAndReportsCompletion() {
        assertFalse(BackfillPlanner.isComplete(5, setOf(0, 1, 2)))
        assertTrue(BackfillPlanner.isComplete(5, setOf(0, 1, 2, 3, 4)))
        assertEquals(emptyList<Int>(), BackfillPlanner.plan(5, setOf(0, 1, 2, 3, 4)))
    }

    // --- EvictionPolicy ------------------------------------------------------------------------

    @Test
    fun evictsOnlyStaleNonFavouriteClosedSerials() {
        val now = 100L * 24 * 60 * 60 * 1000
        val eightDays = now - 8L * 24 * 60 * 60 * 1000
        val threeDays = now - 3L * 24 * 60 * 60 * 1000
        val candidates = listOf(
            EvictionPolicy.Candidate(1, eightDays, isFavorite = false, isOpen = false), // evict
            EvictionPolicy.Candidate(2, eightDays, isFavorite = true, isOpen = false),  // favourite: keep
            EvictionPolicy.Candidate(3, eightDays, isFavorite = false, isOpen = true),  // open: keep
            EvictionPolicy.Candidate(4, threeDays, isFavorite = false, isOpen = false)  // fresh: keep
        )
        assertEquals(listOf(1L), EvictionPolicy.evictable(candidates, now))
    }

    // --- FetchQueue ----------------------------------------------------------------------------

    @Test
    fun activeBufferAlwaysWinsAcrossLanes() {
        val q = FetchQueue()
        q.enqueue(FetchTask(FetchLane.FAVORITES_BACKFILL, fictionId = 9, ordinal = 0))
        q.enqueue(FetchTask(FetchLane.FAVORITES_NEW, fictionId = 9, ordinal = 5))
        q.enqueue(FetchTask(FetchLane.ACTIVE_BUFFER, fictionId = 1, ordinal = 3))
        q.enqueue(FetchTask(FetchLane.FINISH_CURRENT, fictionId = 1, ordinal = 4))
        assertEquals(FetchLane.ACTIVE_BUFFER, q.poll()!!.lane)
        assertEquals(FetchLane.FINISH_CURRENT, q.poll()!!.lane)
        assertEquals(FetchLane.FAVORITES_NEW, q.poll()!!.lane)
        assertEquals(FetchLane.FAVORITES_BACKFILL, q.poll()!!.lane)
    }

    @Test
    fun fifoWithinLaneAndDedupesIdenticalTasks() {
        val q = FetchQueue()
        q.enqueue(FetchTask(FetchLane.ACTIVE_BUFFER, fictionId = 1, ordinal = 3))
        q.enqueue(FetchTask(FetchLane.ACTIVE_BUFFER, fictionId = 1, ordinal = 4))
        q.enqueue(FetchTask(FetchLane.ACTIVE_BUFFER, fictionId = 1, ordinal = 3)) // duplicate — ignored
        assertEquals(2, q.size())
        assertEquals(3, q.poll()!!.ordinal)
        assertEquals(4, q.poll()!!.ordinal)
    }

    @Test
    fun removeFictionClearsItsTasks() {
        val q = FetchQueue()
        q.enqueue(FetchTask(FetchLane.FAVORITES_BACKFILL, fictionId = 9, ordinal = 0))
        q.enqueue(FetchTask(FetchLane.FAVORITES_BACKFILL, fictionId = 1, ordinal = 0))
        q.removeFiction(9)
        assertEquals(1, q.size())
        assertEquals(1L, q.poll()!!.fictionId)
    }
}
