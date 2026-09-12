package com.operations.backupkit.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Retention is the one part of this feature that deletes anything, so these are the tests that
 * matter most: that it only ever considers archives this app wrote, and that "keep everything" is
 * what an unconfigured setting means.
 */
class CloudBackupRetentionTest {

    private fun archive(day: Int, minute: String = "020000") = "operations-backup-202609${"%02d".format(day)}-${minute}Z.zip"

    @Test
    fun `the newest are kept and the rest are returned oldest first`() {
        val names = listOf(archive(3), archive(1), archive(5), archive(2), archive(4))
        assertEquals(listOf(archive(1), archive(2)), CloudBackupRetention.expired(names, keep = 3))
    }

    @Test
    fun `fewer archives than the keep count deletes nothing`() {
        assertEquals(emptyList<String>(), CloudBackupRetention.expired(listOf(archive(1), archive(2)), keep = 7))
    }

    @Test
    fun `keep everything is what an unconfigured setting means`() {
        val names = (1..10).map { archive(it) }
        assertEquals(emptyList<String>(), CloudBackupRetention.expired(names, CloudBackupRetention.KEEP_EVERYTHING))
        assertEquals(emptyList<String>(), CloudBackupRetention.expired(names, keep = -1))
    }

    @Test
    fun `nothing the app did not write is ever a candidate for deletion`() {
        val theirs = listOf(
            "family-photos.zip",
            "readme.txt",
            "operations-backup-20260901-0200.zip",   // the local Full Backup name — not ours to prune
            "backup-20260901-020000Z.zip",
            "nightly/operations-backup-20260901.zip"
        )
        val ours = (1..9).map { archive(it) }
        val expired = CloudBackupRetention.expired(theirs + ours, keep = 2)
        assertEquals(7, expired.size)
        assertTrue(expired.all { CloudBackupNaming.isArchiveName(it) })
        assertTrue(theirs.none { it in expired })
    }

    @Test
    fun `archives under a prefix are pruned by their file name, not their path`() {
        val names = (1..5).map { "nightly/${archive(it)}" }
        assertEquals(
            listOf("nightly/${archive(1)}", "nightly/${archive(2)}"),
            CloudBackupRetention.expired(names, keep = 3)
        )
    }

    @Test
    fun `a name carries the minute it was taken, in UTC, and sorts by it`() {
        val at = Instant.parse("2026-09-12T02:00:05Z").toEpochMilli()
        val name = CloudBackupNaming.blobName(at)
        assertEquals("operations-backup-20260912-020005Z.zip", name)
        assertTrue(CloudBackupNaming.isArchiveName(name))
        assertEquals(at, CloudBackupNaming.takenAt(name))
        assertEquals(at, CloudBackupNaming.takenAt("nightly/$name"))
        assertTrue(CloudBackupNaming.blobName(at) < CloudBackupNaming.blobName(at + 60_000))
    }

    @Test
    fun `a name that isn't ours has no stamp to read`() {
        assertFalse(CloudBackupNaming.isArchiveName("operations-backup-2026-09-12.zip"))
        assertFalse(CloudBackupNaming.isArchiveName("operations-backup-20260912-020005.zip"))
        assertEquals(null, CloudBackupNaming.takenAt("holiday.zip"))
    }

    @Test
    fun `the listing the settings screen shows is newest first and ours only`() {
        val ours = CloudBackupRetention.ours(listOf(archive(1), "holiday.zip", archive(3), archive(2)))
        assertEquals(listOf(archive(3), archive(2), archive(1)), ours)
    }

    @Test
    fun `a keep count reads as a sentence`() {
        assertEquals("Keep the newest 7", CloudBackupRetention.describe(7))
        assertEquals("Keep every archive", CloudBackupRetention.describe(CloudBackupRetention.KEEP_EVERYTHING))
    }
}
