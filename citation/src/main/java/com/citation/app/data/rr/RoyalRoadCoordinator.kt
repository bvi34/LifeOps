package com.citation.app.data.rr

import com.citation.app.data.db.RoyalRoadDao
import com.citation.app.data.db.RrChapterMetaEntity
import com.citation.app.data.db.RrFictionEntity
import com.citation.app.data.setFavorite
import com.citation.app.data.store.FileStores
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
import com.citation.core.rr.RoyalRoadFeed
import com.citation.core.rr.RoyalRoadHtml

/**
 * The Royal Road read loop, wired: it pairs the pure `:core` planners/queue/budgets with the
 * networked [RoyalRoadClient], the disposable file store, and catalog persistence. All the *policy*
 * (what to prefetch, what to backfill, what's stale, how fast to scrape, which lane wins) lives in
 * `:core` and is unit-tested; this class only carries data between those decisions and the outside
 * world.
 *
 * The queue and rate budgets are held in-memory. That's intentional and safe: the backfill and
 * buffer plans are recomputed from the cached set on demand, so a process death loses only the
 * transient queue, which the next [refillBuffer]/[markFavorite] call rebuilds — the design's
 * "resumable + self-healing" property.
 */
class RoyalRoadCoordinator(
    private val dao: RoyalRoadDao,
    private val client: RoyalRoadClient,
    private val files: FileStores,
    private val queue: FetchQueue = FetchQueue(),
    // Feeds run loose (blessed, tiny); body scrapes are throttled ~5–6/hr with a daily backstop.
    private val feedBudget: RateBudget = RateBudget(maxPerHour = 60, maxPerDay = 500),
    private val scrapeBudget: RateBudget = RateBudget(maxPerHour = 6, maxPerDay = 60)
) {

    /**
     * Open a story: ensure its catalog is known, guarantee **chapter 1 comes through the reader**
     * (fetched + cached like any other), enqueue the rolling buffer, then drain what the budget
     * allows. Returns the readable [Book] assembled from whatever is cached so far.
     */
    suspend fun openStory(fictionId: Long): Book {
        val catalog = ensureCatalog(fictionId)
        dao.setProgress(fictionId, ordinal = 0, now = System.currentTimeMillis())
        // Chapter 1 renders through our reader too, not the live page — fetch it into cache first.
        val firstChapterCached = dao.cachedOrdinals(fictionId).contains(0)
        if (!firstChapterCached && catalog.chapters.isNotEmpty()) {
            fetchAndCache(fictionId, ordinal = 0, forceBudgetBypass = true)
        }
        refillBuffer(fictionId, currentOrdinal = 0)
        drainQueue()
        return loadBook(fictionId)
    }

    /** Advance the read position and slide the prefetch window forward. */
    suspend fun advance(fictionId: Long, newOrdinal: Int) {
        dao.setProgress(fictionId, newOrdinal, System.currentTimeMillis())
        // The chapter you're turning *to* is the active reading position — it must be readable now,
        // not merely queued behind the scrape budget. Force it in like chapter 1 if the buffer hasn't
        // reached it yet (a fast jump, an exhausted budget). The look-ahead below still respects the
        // budget. Without this the reader would land on a chapter whose body never arrives.
        if (newOrdinal !in dao.cachedOrdinals(fictionId)) {
            runCatching { fetchAndCache(fictionId, newOrdinal, forceBudgetBypass = true) }
        }
        refillBuffer(fictionId, newOrdinal)
        drainQueue()
    }

    /** Enqueue buffer prefetch to keep ~5 chapters / ~50k chars ahead of [currentOrdinal]. */
    suspend fun refillBuffer(fictionId: Long, currentOrdinal: Int) {
        val cached = dao.cachedOrdinals(fictionId).toSet()
        val fiction = dao.fiction(fictionId) ?: return
        val ahead = RollingBuffer.plan(
            currentOrdinal = currentOrdinal,
            cachedOrdinals = cached,
            totalChapters = fiction.expectedCount
        )
        ahead.forEach { queue.enqueue(FetchTask(FetchLane.ACTIVE_BUFFER, fictionId, it)) }
    }

    /**
     * Favourite (or un-favourite) a fiction. Favouriting kicks off the **full backfill** (slow lane,
     * gaps-first) so the serial is held whole and indefinitely; un-favouriting drops its queued
     * backfill (its cache becomes eligible for the 7-day eviction again).
     */
    suspend fun markFavorite(fictionId: Long, favorite: Boolean) {
        dao.setFavorite(fictionId, favorite)
        if (favorite) {
            val catalog = ensureCatalog(fictionId)
            val cached = dao.cachedOrdinals(fictionId).toSet()
            BackfillPlanner.plan(catalog.expectedCount, cached).forEach {
                queue.enqueue(FetchTask(FetchLane.FAVORITES_BACKFILL, fictionId, it))
            }
        } else {
            queue.removeFiction(fictionId)
        }
    }

    /**
     * Forget a serial entirely: un-favourite it, drop its queued fetches, delete every cached chapter
     * body, and remove its catalog rows (fiction + chapter meta). This is the "uncache / remove"
     * action behind the library's delete — it undoes everything the RR loop borrowed for the serial.
     * The sovereign [com.citation.app.data.db.BookEntity] and any notes are the repository's concern;
     * this only reclaims the disposable, refetchable side.
     */
    suspend fun forget(fictionId: Long) {
        queue.removeFiction(fictionId)
        dao.setFavorite(fictionId, false)
        files.deleteBorrowedFiction(fictionId.toString())
        dao.deleteChapters(fictionId)
        dao.deleteFiction(fictionId)
    }

    /**
     * Poll every favourite's feed (loose budget) and enqueue only genuinely new chapters (fast
     * FAVORITES_NEW lane). The feed is the detector; bodies are pulled later by [drainQueue].
     */
    suspend fun pollFavorites() {
        val favorites = dao.allFictions().filter { it.isFavorite }
        for (fiction in favorites) {
            if (!feedBudget.tryAcquire()) break
            val feed = runCatching { client.fetchFeed(fiction.fictionId) }.getOrNull() ?: continue
            val known = dao.chapters(fiction.fictionId).map { it.chapterId }.toSet()
            val newOnes = RoyalRoadFeed.detectNewChapters(feed, known)
            if (newOnes.isNotEmpty()) {
                appendNewChapters(fiction, newOnes)
                val cached = dao.cachedOrdinals(fiction.fictionId).toSet()
                dao.chapters(fiction.fictionId)
                    .filter { it.ordinal !in cached && it.chapterId in newOnes.mapNotNull { e -> e.chapterId } }
                    .forEach { queue.enqueue(FetchTask(FetchLane.FAVORITES_NEW, fiction.fictionId, it.ordinal, it.chapterId)) }
            }
        }
        drainQueue()
    }

    /** Reclaim borrowed cache for stale, non-favourite, closed serials (7-day rule from `:core`). */
    suspend fun runEviction(openFictionId: Long? = null, now: Long = System.currentTimeMillis()) {
        val candidates = dao.allFictions().map {
            EvictionPolicy.Candidate(
                fictionId = it.fictionId,
                lastReadAt = it.lastReadAt,
                isFavorite = it.isFavorite,
                isOpen = it.fictionId == openFictionId
            )
        }
        EvictionPolicy.evictable(candidates, now).forEach { fictionId ->
            dao.cachedOrdinals(fictionId).forEach { ordinal ->
                files.deleteBorrowedChapter(fictionId.toString(), ordinal)
                dao.setCached(fictionId, ordinal, false)
            }
            queue.removeFiction(fictionId)
        }
    }

    /**
     * Pull queued bodies while the scrape budget permits, highest-priority lane first. Stops as soon
     * as the budget is exhausted — the active buffer, being top priority, is served before any
     * backfill ever is.
     */
    suspend fun drainQueue() {
        while (true) {
            val task = queue.peek() ?: break
            if (!scrapeBudget.tryAcquire()) break
            queue.poll()
            runCatching { fetchAndCache(task.fictionId, task.ordinal, forceBudgetBypass = true) }
        }
    }

    /**
     * Assemble a readable [Book] from the RR catalog (format-blind, like any other source).
     *
     * The book carries the **whole known spine**, not just the chapters whose bodies are cached: the
     * reader must see the serial's real length and keep "Next" live all the way to the end, and the
     * body of the chapter you turn to is fetched on demand by [advance]. A chapter whose body isn't
     * cached yet renders [PENDING_CHAPTER] until a subsequent [loadBook] picks up its fetched text.
     *
     * Building the list positionally (index == ordinal) also keeps [Book.chapterAt] — which indexes
     * by position — correctly aligned; the old cached-only filter silently shifted every chapter
     * after the first gap onto the wrong ordinal.
     */
    suspend fun loadBook(fictionId: Long): Book {
        val fiction = dao.fiction(fictionId)
        val byOrdinal = dao.chapters(fictionId).associateBy { it.ordinal }
        val lastOrdinal = byOrdinal.keys.maxOrNull() ?: -1
        val chapters = (0..lastOrdinal).map { ordinal ->
            val meta = byOrdinal[ordinal]
            val text = meta?.let { files.readBorrowedChapter(fictionId.toString(), ordinal) }
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
                title = fiction?.title ?: "Royal Road #$fictionId",
                author = null,
                source = SourceType.ROYAL_ROAD
            ),
            chapters = chapters
        )
    }

    // --- internals -----------------------------------------------------------------------------

    private suspend fun ensureCatalog(fictionId: Long): com.citation.core.rr.FictionCatalog {
        val existing = dao.chapters(fictionId)
        if (existing.isNotEmpty()) {
            val fiction = dao.fiction(fictionId)
            return com.citation.core.rr.FictionCatalog(
                fictionId,
                fiction?.title ?: "",
                existing.map { com.citation.core.rr.RrChapterRef(it.chapterId, it.ordinal, it.title, it.url) }
            )
        }
        val catalog = client.fetchCatalog(fictionId)
        dao.upsertFiction(RrFictionEntity(fictionId, catalog.title, expectedCount = catalog.expectedCount))
        dao.upsertChapters(catalog.chapters.map {
            RrChapterMetaEntity(fictionId, it.ordinal, it.chapterId, it.title, it.url)
        })
        return catalog
    }

    private suspend fun appendNewChapters(fiction: RrFictionEntity, newOnes: List<RoyalRoadFeed.FeedEntry>) {
        var ordinal = dao.chapters(fiction.fictionId).size
        val rows = newOnes.mapNotNull { e ->
            val id = e.chapterId ?: return@mapNotNull null
            RrChapterMetaEntity(fiction.fictionId, ordinal++, id, e.title, e.link)
        }
        dao.upsertChapters(rows)
        dao.setExpectedCount(fiction.fictionId, ordinal)
    }

    private suspend fun fetchAndCache(fictionId: Long, ordinal: Int, forceBudgetBypass: Boolean) {
        if (!forceBudgetBypass && !scrapeBudget.tryAcquire()) return
        val meta = dao.chapters(fictionId).firstOrNull { it.ordinal == ordinal } ?: return
        val html = client.fetchChapterHtml(meta.url)
        val chapter = RoyalRoadHtml.toChapter(
            com.citation.core.rr.RrChapterRef(meta.chapterId, meta.ordinal, meta.title, meta.url),
            html
        )
        files.writeBorrowedChapter(fictionId.toString(), ordinal, chapter.text)
        dao.setCached(fictionId, ordinal, true)
    }

    companion object {
        /** Placeholder body for a spine chapter whose text hasn't been fetched into cache yet. */
        const val PENDING_CHAPTER = "Fetching this chapter…"
    }
}
