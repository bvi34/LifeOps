package com.citation.core.manifest

/**
 * The one object that answers both **"is this favourite whole?"** and **"what does it cost?"**.
 *
 * A borrowed serial (Royal Road) is cached chapter-by-chapter and can arrive with gaps — a backfill
 * that hasn't reached chapter 12 yet, or a chapter that failed to fetch. The manifest records, per
 * favourite, the *expected* chapter count against what is actually *cached*, and the byte size of
 * each cached chapter. Gap detection and size accounting both read off this single record, so
 * "integrity" and "storage cost" never drift apart.
 *
 * @property expectedChapterCount how many chapters the source says exist (latest known).
 * @property chapters per-ordinal cache facts, keyed by chapter ordinal (0-based).
 * @property recoverability whether the cached bytes are cheap to refetch or irreplaceable.
 */
data class IntegrityManifest(
    val expectedChapterCount: Int,
    val chapters: Map<Int, ChapterCacheInfo>,
    val recoverability: Recoverability
) {
    /** The ordinals expected (`0 until expectedChapterCount`) that are **not** cached — the gaps. */
    val missingOrdinals: List<Int>
        get() = (0 until expectedChapterCount).filter { it !in chapters.keys }

    /** True when every expected chapter is present. A whole favourite reads cleanly end to end. */
    val isComplete: Boolean get() = missingOrdinals.isEmpty() && expectedChapterCount > 0

    /** Total bytes currently cached for this item — its footprint on disk. */
    val cachedBytes: Long get() = chapters.values.sumOf { it.byteSize }

    /** How many chapters are cached (≤ expected). */
    val cachedChapterCount: Int get() = chapters.size

    /** The size line for this item, tagged by whether reclaiming it loses anything irreplaceable. */
    fun sizeEntry(label: String): SizeEntry =
        SizeEntry(label = label, bytes = cachedBytes, recoverability = recoverability)

    /** Record/replace one chapter's cache facts, returning an updated manifest. */
    fun withChapter(ordinal: Int, byteSize: Long): IntegrityManifest =
        copy(chapters = chapters + (ordinal to ChapterCacheInfo(ordinal, byteSize)))

    /** Drop a chapter (e.g. after eviction), returning an updated manifest. */
    fun withoutChapter(ordinal: Int): IntegrityManifest =
        copy(chapters = chapters - ordinal)

    /** Raise the expected count when the source publishes new chapters. Never lowers it. */
    fun withExpectedCount(count: Int): IntegrityManifest =
        if (count > expectedChapterCount) copy(expectedChapterCount = count) else this

    companion object {
        fun empty(expectedChapterCount: Int, recoverability: Recoverability) =
            IntegrityManifest(expectedChapterCount, emptyMap(), recoverability)
    }
}

/** Per-chapter cache facts: it's present, and it costs this many bytes. */
data class ChapterCacheInfo(val ordinal: Int, val byteSize: Long)

/**
 * Whether cached bytes can be got back for free. Drives both eviction policy (only reclaimable
 * borrowed content is auto-evictable) and the storage-visibility UI's honesty about what pruning
 * actually costs.
 */
enum class Recoverability {
    /** Borrowed, refetchable content (an RR serial) — reclaiming it loses nothing but a re-download. */
    RECLAIMABLE,
    /** Owned/irreplaceable content (a research PDF, your notes) — reclaiming it is a real loss. */
    IRREPLACEABLE
}

/** One row in a storage report: a labelled footprint tagged by recoverability. */
data class SizeEntry(val label: String, val bytes: Long, val recoverability: Recoverability)

/**
 * Aggregate size accounting across many items, split by recoverability so the UI can say "you can
 * reclaim X, but Y is irreplaceable" without ever proposing to delete owned data.
 */
data class StorageReport(val entries: List<SizeEntry>) {
    val totalBytes: Long get() = entries.sumOf { it.bytes }
    val reclaimableBytes: Long
        get() = entries.filter { it.recoverability == Recoverability.RECLAIMABLE }.sumOf { it.bytes }
    val irreplaceableBytes: Long
        get() = entries.filter { it.recoverability == Recoverability.IRREPLACEABLE }.sumOf { it.bytes }
}
