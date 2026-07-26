package com.citation.core.manifest

import com.citation.core.model.SourceType

/**
 * Builds the storage picture the visibility screen shows — **and does nothing else**.
 *
 * The design rule for storage is: *visibility only, no ceilings, no auto-eviction of favourites or
 * owned files.* The most the app does on its own is **warn**; you decide what to prune. So this
 * aggregator classifies each stored item by recoverability, totals it up, and can raise an advisory
 * flag — but it never returns anything to delete. (Auto-eviction of *borrowed, non-favourite* cache
 * is a separate, explicitly-scoped job — see `rr/EvictionPolicy` — not this.)
 *
 * Recoverability is a property of the *source*, not of your intent: a Royal Road serial is
 * reclaimable because it's refetchable even if you favourited it; a research PDF is irreplaceable
 * because losing the file loses the work. That's what lets the UI honestly say "you can reclaim X,
 * but Y is irreplaceable — I won't touch it."
 */
object StorageInventory {

    /** One thing taking up space: a book's content, a cache, a pile of notes. */
    data class StorageItem(
        val label: String,
        val sourceType: SourceType,
        val bytes: Long
    )

    /** Recoverability is derived purely from the source kind (borrowed cache ⇒ reclaimable). */
    fun recoverabilityFor(sourceType: SourceType): Recoverability =
        if (sourceType.isBorrowedCache) Recoverability.RECLAIMABLE else Recoverability.IRREPLACEABLE

    /** Aggregate [items] into a [StorageReport], tagging each by recoverability. */
    fun report(items: List<StorageItem>): StorageReport =
        StorageReport(items.map { SizeEntry(it.label, it.bytes, recoverabilityFor(it.sourceType)) })

    /**
     * An advisory read on a report. [softWarnBytes] is a *soft* threshold: crossing it sets
     * [StorageInsight.shouldWarn] so the UI can nudge — it is never a hard cap and never triggers
     * deletion. The insight also surfaces how much is *reclaimable* (safe to prune) so the warning
     * can point somewhere useful without ever proposing to touch irreplaceable data.
     */
    fun insight(report: StorageReport, softWarnBytes: Long? = null): StorageInsight =
        StorageInsight(
            totalBytes = report.totalBytes,
            reclaimableBytes = report.reclaimableBytes,
            irreplaceableBytes = report.irreplaceableBytes,
            shouldWarn = softWarnBytes != null && report.totalBytes > softWarnBytes
        )

    /** Reclaimable items, largest first — what the UI offers *you* to prune (it won't prune for you). */
    fun reclaimableLargestFirst(report: StorageReport): List<SizeEntry> =
        report.entries.filter { it.recoverability == Recoverability.RECLAIMABLE }.sortedByDescending { it.bytes }

    /** Human-readable byte size (1024-based), e.g. `12.3 MB`. Pure, for display. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format("%.1f %s", value, units[unit])
    }
}

/**
 * A visibility-only read on storage. Carries totals and an advisory [shouldWarn]; deliberately holds
 * **no** action to take — pruning is always the user's call.
 */
data class StorageInsight(
    val totalBytes: Long,
    val reclaimableBytes: Long,
    val irreplaceableBytes: Long,
    val shouldWarn: Boolean
)
