package com.operations.backupkit.cloud

/**
 * Which archives in the container are no longer worth keeping.
 *
 * A backup that runs every night and never deletes anything is a bill that grows every night, so
 * the household says how many to keep and this says which ones that leaves. It is the one part of
 * the feature that *destroys* data, which is why it is pure arithmetic with tests rather than a
 * loop inside the worker:
 *
 *  - only names [CloudBackupNaming] would itself have written are ever candidates, so nothing else
 *    in the container can be deleted by this app, whatever it is called or however it got there;
 *  - the newest [keep] are kept, ordered by the stamp in the name (UTC, so sorting is chronology);
 *  - `keep <= 0` means keep everything, and is what the setting reads as when retention is off.
 *
 * The last point is the important default: a household that has not thought about retention should
 * end up paying for storage, not missing a backup they wanted.
 */
object CloudBackupRetention {

    /** A week of nightly archives — enough to notice a bad restore before the good copy rolls off. */
    const val DEFAULT_KEEP = 7

    /** What [keep] means "don't delete anything". */
    const val KEEP_EVERYTHING = 0

    /** The choices the settings screen offers, newest-first counts plus "keep everything". */
    val KEEP_CHOICES = listOf(3, 7, 14, 30, KEEP_EVERYTHING)

    /**
     * The blobs that should be deleted, given everything currently in the container (or in its
     * prefix) and how many archives to keep. Input order is irrelevant; the answer is ordered
     * oldest first, which is also the order it is safest to delete in.
     */
    fun expired(blobPaths: List<String>, keep: Int): List<String> {
        if (keep <= KEEP_EVERYTHING) return emptyList()
        return blobPaths
            .filter { CloudBackupNaming.isArchiveName(it) }
            .sortedByDescending { CloudBackupNaming.fileName(it) }
            .drop(keep)
            .reversed()
    }

    /** The archives this app recognises, newest first — what the settings screen lists. */
    fun ours(blobPaths: List<String>): List<String> =
        blobPaths.filter { CloudBackupNaming.isArchiveName(it) }
            .sortedByDescending { CloudBackupNaming.fileName(it) }

    /** How a keep-count reads in the UI. */
    fun describe(keep: Int): String =
        if (keep <= KEEP_EVERYTHING) "Keep every archive" else "Keep the newest $keep"
}
