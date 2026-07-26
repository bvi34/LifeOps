package com.citation.core.manifest

import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageInventoryTest {

    @Test
    fun recoverabilityComesFromTheSourceKind() {
        // A Royal Road serial is reclaimable (refetchable); owned files are irreplaceable.
        assertEquals(Recoverability.RECLAIMABLE, StorageInventory.recoverabilityFor(SourceType.ROYAL_ROAD))
        assertEquals(Recoverability.IRREPLACEABLE, StorageInventory.recoverabilityFor(SourceType.PDF))
        assertEquals(Recoverability.IRREPLACEABLE, StorageInventory.recoverabilityFor(SourceType.EPUB))
        assertEquals(Recoverability.IRREPLACEABLE, StorageInventory.recoverabilityFor(SourceType.OREILLY))
    }

    @Test
    fun reportSplitsReclaimableFromIrreplaceable() {
        val report = StorageInventory.report(
            listOf(
                StorageInventory.StorageItem("RR: My Serial", SourceType.ROYAL_ROAD, 5_000),
                StorageInventory.StorageItem("PDF: Research", SourceType.PDF, 2_000_000),
                StorageInventory.StorageItem("EPUB: A Novel", SourceType.EPUB, 500_000)
            )
        )
        assertEquals(2_505_000L, report.totalBytes)
        assertEquals(5_000L, report.reclaimableBytes)
        assertEquals(2_500_000L, report.irreplaceableBytes)
    }

    @Test
    fun insightWarnsOnlyPastTheSoftThresholdAndNeverEvicts() {
        val report = StorageInventory.report(
            listOf(StorageInventory.StorageItem("RR", SourceType.ROYAL_ROAD, 10_000))
        )
        assertFalse(StorageInventory.insight(report, softWarnBytes = 1_000_000).shouldWarn)
        assertTrue(StorageInventory.insight(report, softWarnBytes = 5_000).shouldWarn)
        // No threshold ⇒ never warns; and there is simply no "evict" surface to call.
        assertFalse(StorageInventory.insight(report, softWarnBytes = null).shouldWarn)
    }

    @Test
    fun reclaimableSuggestionsAreLargestFirstAndExcludeOwned() {
        val report = StorageInventory.report(
            listOf(
                StorageInventory.StorageItem("RR small", SourceType.ROYAL_ROAD, 1_000),
                StorageInventory.StorageItem("RR big", SourceType.ROYAL_ROAD, 9_000),
                StorageInventory.StorageItem("PDF", SourceType.PDF, 5_000_000)
            )
        )
        val reclaimable = StorageInventory.reclaimableLargestFirst(report)
        assertEquals(listOf("RR big", "RR small"), reclaimable.map { it.label })
        assertFalse(reclaimable.any { it.label == "PDF" }) // owned is never offered for pruning
    }

    @Test
    fun formatsBytesHumanReadably() {
        assertEquals("512 B", StorageInventory.formatBytes(512))
        assertEquals("1.0 KB", StorageInventory.formatBytes(1024))
        assertEquals("1.5 MB", StorageInventory.formatBytes(1024L * 1024 * 3 / 2))
    }
}
