package com.citation.app.data.ao3

import com.citation.app.data.db.Ao3ChapterMetaEntity
import com.citation.app.data.db.Ao3Dao
import com.citation.app.data.db.Ao3WorkEntity
import com.citation.app.data.store.FileStores
import com.citation.core.ao3.Ao3Catalog
import com.citation.core.ao3.Ao3ChapterRef
import com.citation.core.ao3.Ao3Html
import com.citation.core.ao3.Ao3Updates
import com.citation.core.model.Book
import com.citation.core.model.BookMetadata
import com.citation.core.model.Chapter
import com.citation.core.model.SourceType
import com.citation.core.rr.BackfillPlanner
import com.citation.core.rr.EvictionPolicy
import com.citation.core.rr.FetchLane
import com.citation.core.rr.FetchQueue
import com.citation.core.rr.FetchTask
import com.citation.core.rr.RateBudget
import com.citation.core.rr.RollingBuffer

/**
 * The Archive of Our Own read loop, wired — the AO3 twin of
 * [com.citation.app.data.rr.RoyalRoadCoordinator]. It pairs the pure `:core` planners/queue/budgets
 * (shared, generic, id-keyed — they live in `com.citation.core.rr` but reason only about work ids and
 * ordinals) with the networked [Ao3Client], the disposable file store, and catalog persistence.
 *
 * Two AO3-specific twists sit on top of the shared RR machinery:
 *  - **Borrowed-cache namespace.** Chapter bodies are stored under an `ao3-{workId}` key so an AO3
 *    work and a Royal Road fiction that share a numeric id never write over each other in the one
 *    disposable directory.
 *  - **No feed.** AO3 has no per-work syndication feed, so [pollFavorites] re-reads each favourite's
 *    catalog and diffs it via [Ao3Updates.detectNewChapters] instead of polling RSS. The catalog
 *    fetch is heavier than an RSS poll, so favourites are polled on the *scrape* budget, not a loose
 *    feed budget.
 *
 * The queue and rate budgets are held in-memory; process death loses only the transient queue, which
 * the next [refillBuffer]/[markFavorite] call rebuilds from the cached set (resumable + self-healing).
 */
class Ao3Coordinator(
    private val dao: Ao3Dao,
    private val client: Ao3Client,
    private val files: FileStores,
    private val queue: FetchQueue = FetchQueue(),
    // AO3 has no cheap feed, so the "detector" is a catalog scrape — poll favourites on the scrape
    // budget rather than a loose feed budget.
    private val scrapeBudget: RateBudget = RateBudget(maxPerHour = 6, maxPerDay = 60)
) {

    /** The disposable-cache namespace for a work, distinct from Royal Road's raw fiction-id keys. */
    private fun cacheKey(workId: Long): String = "ao3-$workId"

    /**
     * Open a work: ensure its catalog is known, guarantee **chapter 1 comes through the reader**
     * (fetched + cached like any other), enqueue the rolling buffer, then drain what the budget
     * allows. Returns the readable [Book] assembled from whatever is cached so far.
     */
    suspend fun openWork(workId: Long): Book {
        val catalog = ensureCatalog(workId)
        dao.setProgress(workId, ordinal = 0, now = System.currentTimeMillis())
        val firstChapterCached = dao.cachedOrdinals(workId).contains(0)
        if (!firstChapterCached && catalog.chapters.isNotEmpty()) {
            fetchAndCache(workId, ordinal = 0, forceBudgetBypass = true)
        }
        refillBuffer(workId, currentOrdinal = 0)
        drainQueue()
        return loadBook(workId)
    }

    /** Advance the read position and slide the prefetch window forward. */
    suspend fun advance(workId: Long, newOrdinal: Int) {
        dao.setProgress(workId, newOrdinal, System.currentTimeMillis())
        // The chapter you're turning *to* must be readable now, not merely queued behind the budget.
        if (newOrdinal !in dao.cachedOrdinals(workId)) {
            runCatching { fetchAndCache(workId, newOrdinal, forceBudgetBypass = true) }
        }
        refillBuffer(workId, newOrdinal)
        drainQueue()
    }

    /** Enqueue buffer prefetch to keep ~5 chapters / ~50k chars ahead of [currentOrdinal]. */
    suspend fun refillBuffer(workId: Long, currentOrdinal: Int) {
        val cached = dao.cachedOrdinals(workId).toSet()
        val work = dao.work(workId) ?: return
        val ahead = RollingBuffer.plan(
            currentOrdinal = currentOrdinal,
            cachedOrdinals = cached,
            totalChapters = work.expectedCount
        )
        ahead.forEach { queue.enqueue(FetchTask(FetchLane.ACTIVE_BUFFER, workId, it)) }
    }

    /**
     * Favourite (or un-favourite) a work. Favouriting kicks off the **full backfill** (slow lane,
     * gaps-first) so the work is held whole and indefinitely; un-favouriting drops its queued
     * backfill (its cache becomes eligible for the 7-day eviction again).
     */
    suspend fun markFavorite(workId: Long, favorite: Boolean) {
        dao.setFavorite(workId, favorite)
        if (favorite) {
            val catalog = ensureCatalog(workId)
            val cached = dao.cachedOrdinals(workId).toSet()
            BackfillPlanner.plan(catalog.expectedCount, cached).forEach {
                queue.enqueue(FetchTask(FetchLane.FAVORITES_BACKFILL, workId, it))
            }
        } else {
            queue.removeFiction(workId)
        }
    }

    /**
     * Forget a work entirely: un-favourite it, drop its queued fetches, delete every cached chapter
     * body, and remove its catalog rows. Undoes everything the AO3 loop borrowed for the work; the
     * sovereign book + notes are the repository's concern.
     */
    suspend fun forget(workId: Long) {
        queue.removeFiction(workId)
        dao.setFavorite(workId, false)
        files.deleteBorrowedFiction(cacheKey(workId))
        dao.deleteChapters(workId)
        dao.deleteWork(workId)
    }

    /**
     * Poll every favourite by re-reading its catalog and enqueuing only genuinely-new chapters (fast
     * FAVORITES_NEW lane). AO3 has no feed, so the catalog scrape *is* the detector; it runs on the
     * scrape budget so a burst of favourites can't blow past the rate ceiling.
     */
    suspend fun pollFavorites() {
        val favorites = dao.allWorks().filter { it.isFavorite }
        for (work in favorites) {
            if (!scrapeBudget.tryAcquire()) break
            val catalog = runCatching { client.fetchCatalog(work.workId) }.getOrNull() ?: continue
            val known = dao.chapters(work.workId).map { it.chapterId }.toSet()
            val newOnes = Ao3Updates.detectNewChapters(catalog, known)
            if (newOnes.isNotEmpty()) {
                appendNewChapters(work.workId, newOnes)
                val cached = dao.cachedOrdinals(work.workId).toSet()
                dao.chapters(work.workId)
                    .filter { it.ordinal !in cached && it.chapterId in newOnes.map { e -> e.chapterId } }
                    .forEach { queue.enqueue(FetchTask(FetchLane.FAVORITES_NEW, work.workId, it.ordinal, it.chapterId)) }
            }
        }
        drainQueue()
    }

    /** Reclaim borrowed cache for stale, non-favourite, closed works (7-day rule from `:core`). */
    suspend fun runEviction(openWorkId: Long? = null, now: Long = System.currentTimeMillis()) {
        val candidates = dao.allWorks().map {
            EvictionPolicy.Candidate(
                fictionId = it.workId,
                lastReadAt = it.lastReadAt,
                isFavorite = it.isFavorite,
                isOpen = it.workId == openWorkId
            )
        }
        EvictionPolicy.evictable(candidates, now).forEach { workId ->
            dao.cachedOrdinals(workId).forEach { ordinal ->
                files.deleteBorrowedChapter(cacheKey(workId), ordinal)
                dao.setCached(workId, ordinal, false)
            }
            queue.removeFiction(workId)
        }
    }

    /** Pull queued bodies while the scrape budget permits, highest-priority lane first. */
    suspend fun drainQueue() {
        while (true) {
            val task = queue.peek() ?: break
            if (!scrapeBudget.tryAcquire()) break
            queue.poll()
            runCatching { fetchAndCache(task.fictionId, task.ordinal, forceBudgetBypass = true) }
        }
    }

    /**
     * Assemble a readable [Book] from the AO3 catalog (format-blind, like any other source). Carries
     * the **whole known spine** (not just cached chapters) so the reader sees the real length and
     * "Next" stays live to the end; an uncached chapter renders [PENDING_CHAPTER] until a later
     * [loadBook] picks up its fetched text.
     */
    suspend fun loadBook(workId: Long): Book {
        val work = dao.work(workId)
        val byOrdinal = dao.chapters(workId).associateBy { it.ordinal }
        val lastOrdinal = byOrdinal.keys.maxOrNull() ?: -1
        val chapters = (0..lastOrdinal).map { ordinal ->
            val meta = byOrdinal[ordinal]
            val text = meta?.let { files.readBorrowedChapter(cacheKey(workId), ordinal) }
            Chapter(
                ordinal = ordinal,
                title = meta?.title ?: "Chapter ${ordinal + 1}",
                sourceRef = meta?.url.orEmpty(),
                text = text ?: PENDING_CHAPTER
            )
        }
        return Book(
            key = null,
            metadata = BookMetadata(
                title = work?.title ?: "AO3 #$workId",
                author = work?.author,
                source = SourceType.AO3
            ),
            chapters = chapters
        )
    }

    // --- internals -----------------------------------------------------------------------------

    private suspend fun ensureCatalog(workId: Long): Ao3Catalog {
        val existing = dao.chapters(workId)
        if (existing.isNotEmpty()) {
            val work = dao.work(workId)
            return Ao3Catalog(
                workId,
                work?.title ?: "",
                work?.author,
                existing.map { Ao3ChapterRef(it.chapterId, it.ordinal, it.title, it.url) }
            )
        }
        val catalog = client.fetchCatalog(workId)
        dao.upsertWork(Ao3WorkEntity(workId, catalog.title, catalog.author, expectedCount = catalog.expectedCount))
        dao.upsertChapters(catalog.chapters.map {
            Ao3ChapterMetaEntity(workId, it.ordinal, it.chapterId, it.title, it.url)
        })
        return catalog
    }

    private suspend fun appendNewChapters(workId: Long, newOnes: List<Ao3ChapterRef>) {
        var ordinal = dao.chapters(workId).size
        val rows = newOnes.map { ref ->
            Ao3ChapterMetaEntity(workId, ordinal++, ref.chapterId, ref.title, ref.url)
        }
        dao.upsertChapters(rows)
        dao.setExpectedCount(workId, ordinal)
    }

    private suspend fun fetchAndCache(workId: Long, ordinal: Int, forceBudgetBypass: Boolean) {
        if (!forceBudgetBypass && !scrapeBudget.tryAcquire()) return
        val meta = dao.chapters(workId).firstOrNull { it.ordinal == ordinal } ?: return
        val html = client.fetchChapterHtml(meta.url)
        val chapter = Ao3Html.toChapter(
            Ao3ChapterRef(meta.chapterId, meta.ordinal, meta.title, meta.url),
            html
        )
        files.writeBorrowedChapter(cacheKey(workId), ordinal, chapter.text)
        dao.setCached(workId, ordinal, true)
    }

    companion object {
        /** Placeholder body for a spine chapter whose text hasn't been fetched into cache yet. */
        const val PENDING_CHAPTER = "Fetching this chapter…"
    }
}
