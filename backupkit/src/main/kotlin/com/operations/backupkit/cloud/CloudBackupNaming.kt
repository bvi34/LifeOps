package com.operations.backupkit.cloud

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * What an uploaded archive is called, and how to tell one of ours from anything else in the
 * container.
 *
 * Two decisions are worth the file:
 *
 *  1. **The stamp is UTC.** A household that flies to another country, or a phone that moves itself
 *     onto summer time, would otherwise write names that no longer sort into the order they were
 *     taken in — and the order they sort in is exactly what [CloudBackupRetention] deletes by. UTC
 *     costs a reader a mental offset once; local time costs somebody the wrong archive.
 *  2. **The pattern is narrow and it is matched, not assumed.** Retention only ever considers names
 *     this object would itself have written. A container is somebody's storage account, not ours:
 *     a photo, a `readme.txt`, or an archive copied in by hand from the Backups tab must not be
 *     deletable by a pruning rule it never opted into.
 */
object CloudBackupNaming {

    /** The same stem the local "Full Backup" file uses, so the two are recognisably one thing. */
    const val PREFIX = "operations-backup-"

    const val EXTENSION = ".zip"

    /** `20260912-183005Z` — sortable, second-resolution, and unambiguous about its zone. */
    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    private val NAME = Regex("""\Qoperations-backup-\E(\d{8}-\d{6})Z\Q.zip\E""")

    /** The blob name for an archive taken at [at] (epoch millis). */
    fun blobName(at: Long): String = PREFIX + STAMP.format(Instant.ofEpochMilli(at)) + "Z" + EXTENSION

    /** The file part of a blob path — `nightly/x.zip` is stored under a prefix, and is still `x.zip`. */
    fun fileName(blobPath: String): String = blobPath.substringAfterLast('/')

    /** Whether [blobPath] is an archive this app wrote, and therefore one it may prune. */
    fun isArchiveName(blobPath: String): Boolean = NAME.matches(fileName(blobPath))

    /**
     * When the archive named [blobPath] was taken, read back out of its name, or null if the name
     * isn't one of ours. Used to say "last archive: 3 days ago" without a second round trip.
     */
    fun takenAt(blobPath: String): Long? {
        val stamp = NAME.matchEntire(fileName(blobPath))?.groupValues?.get(1) ?: return null
        return try {
            java.time.LocalDateTime
                .parse(stamp, DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
