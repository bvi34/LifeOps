package com.citation.core.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityManifestTest {

    @Test
    fun detectsGapsAndCompleteness() {
        var m = IntegrityManifest.empty(expectedChapterCount = 5, Recoverability.RECLAIMABLE)
        m = m.withChapter(0, 100).withChapter(1, 120).withChapter(3, 90)
        assertFalse(m.isComplete)
        assertEquals(listOf(2, 4), m.missingOrdinals)
        assertEquals(3, m.cachedChapterCount)

        m = m.withChapter(2, 80).withChapter(4, 70)
        assertTrue(m.isComplete)
        assertEquals(emptyList<Int>(), m.missingOrdinals)
    }

    @Test
    fun sizeAccountingSumsCachedBytes() {
        val m = IntegrityManifest.empty(3, Recoverability.RECLAIMABLE)
            .withChapter(0, 100).withChapter(1, 250)
        assertEquals(350L, m.cachedBytes)
    }

    @Test
    fun expectedCountOnlyGrows() {
        val m = IntegrityManifest.empty(5, Recoverability.RECLAIMABLE)
        assertEquals(10, m.withExpectedCount(10).expectedChapterCount)
        assertEquals(5, m.withExpectedCount(3).expectedChapterCount) // never shrinks
    }

    @Test
    fun evictionRemovesChapterFromManifest() {
        val m = IntegrityManifest.empty(2, Recoverability.RECLAIMABLE)
            .withChapter(0, 100).withChapter(1, 100)
        val after = m.withoutChapter(1)
        assertEquals(100L, after.cachedBytes)
        assertEquals(listOf(1), after.missingOrdinals)
    }

    @Test
    fun storageReportSplitsByRecoverability() {
        val serial = IntegrityManifest.empty(1, Recoverability.RECLAIMABLE)
            .withChapter(0, 500).sizeEntry("RR: My Serial")
        val pdf = IntegrityManifest.empty(1, Recoverability.IRREPLACEABLE)
            .withChapter(0, 2000).sizeEntry("PDF: Research Paper")
        val report = StorageReport(listOf(serial, pdf))
        assertEquals(2500L, report.totalBytes)
        assertEquals(500L, report.reclaimableBytes)
        assertEquals(2000L, report.irreplaceableBytes)
    }
}
