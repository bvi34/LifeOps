package com.citation.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class BookLifecycleTest {

    @Test
    fun acquisitionAndReadingMoveIndependently() {
        var lc = BookLifecycle.wanted()
        assertEquals(AcquisitionState.WANTED, lc.acquisition)
        assertEquals(ReadingState.TO_READ, lc.reading)

        // Resolve the artifact without touching reading progress.
        lc = lc.acquire(AcquisitionEvent.START_RESOLVING).acquire(AcquisitionEvent.RESOLVED)
        assertEquals(AcquisitionState.ACQUIRED, lc.acquisition)
        assertEquals(ReadingState.TO_READ, lc.reading)

        // Start reading without touching acquisition.
        lc = lc.read(ReadingEvent.START_READING)
        assertEquals(AcquisitionState.ACQUIRED, lc.acquisition)
        assertEquals(ReadingState.READING, lc.reading)
    }

    @Test
    fun unavailableIsNotAReadingStatus() {
        // "Can't find it" lives on the acquisition axis; reading stays to-read, uncollapsed.
        val lc = BookLifecycle.wanted().acquire(AcquisitionEvent.MARK_UNAVAILABLE)
        assertEquals(AcquisitionState.UNAVAILABLE, lc.acquisition)
        assertEquals(ReadingState.TO_READ, lc.reading)
    }

    @Test
    fun unavailableCanLaterResolve() {
        val lc = BookLifecycle.wanted()
            .acquire(AcquisitionEvent.MARK_UNAVAILABLE)
            .acquire(AcquisitionEvent.START_RESOLVING)
            .acquire(AcquisitionEvent.RESOLVED)
        assertEquals(AcquisitionState.ACQUIRED, lc.acquisition)
    }

    @Test
    fun rereadReturnsDoneToReading() {
        val lc = BookLifecycle.owned()
            .read(ReadingEvent.START_READING)
            .read(ReadingEvent.FINISH)
        assertEquals(ReadingState.DONE, lc.reading)
        assertEquals(ReadingState.READING, lc.read(ReadingEvent.START_READING).reading)
    }

    @Test(expected = IllegalArgumentException::class)
    fun illegalReadingTransitionRejected() {
        BookLifecycle.owned().read(ReadingEvent.FINISH) // can't finish what isn't being read
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotResolveAnAlreadyAcquiredBook() {
        BookLifecycle.owned().acquire(AcquisitionEvent.RESOLVED)
    }
}
