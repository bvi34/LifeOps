package com.advisor.app.data.source

import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds a [delegate] source's documents between questions, rebuilding only when the data actually
 * changed.
 *
 * Without this every question re-reads whole tables from every granted app and rebuilds a string per
 * row — work proportional to everything the user has ever recorded, repeated on each turn, even when
 * nothing has been written since the last one. That is invisible on a small database and grows with
 * use, which is the worst shape for a cost to have.
 *
 * The snapshot is dropped by [feed] the moment any of the underlying tables is written, so a question
 * asked immediately after adding a task still sees it. If changes cannot be observed the cache
 * disables itself and every [load] goes to the delegate — the interface's "always live" contract is
 * the thing being preserved here, not the saving.
 *
 * The dirty flag is set from Room's invalidation thread and read from the question coroutine, so it
 * is atomic; the rebuild itself is behind a [Mutex] so two questions in flight rebuild once, not
 * twice.
 */
class CachingKnowledgeSource(
    private val delegate: KnowledgeSource,
    private val feed: SourceChangeFeed
) : KnowledgeSource {

    override val source: SourceApp get() = delegate.source

    private val mutex = Mutex()
    private val dirty = AtomicBoolean(true)
    private val subscribed = AtomicBoolean(false)

    @Volatile private var cacheable = true
    @Volatile private var snapshot: List<KnowledgeDocument>? = null

    override suspend fun load(): List<KnowledgeDocument> {
        // Subscribe on first use rather than at construction: Room's addObserver touches the database,
        // so it belongs on the first suspending call, not on the app's main-thread wiring.
        if (subscribed.compareAndSet(false, true)) {
            cacheable = feed.subscribe { dirty.set(true) }
        }
        if (!cacheable) return delegate.load()

        snapshot?.takeIf { !dirty.get() }?.let { return it }

        return mutex.withLock {
            snapshot?.takeIf { !dirty.get() }?.let { return@withLock it }
            // Clear *before* reading. A write that lands mid-read then leaves the flag set and the next
            // question rebuilds; clearing afterwards would swallow it and serve a stale snapshot.
            dirty.set(false)
            val fresh = delegate.load()
            snapshot = fresh
            fresh
        }
    }

    /**
     * Drop the snapshot. Called when the source's permission is revoked, so a withdrawn app's rows
     * are not still sitting in Advisor's memory afterwards.
     */
    override fun evict() {
        snapshot = null
        dirty.set(true)
        delegate.evict()
    }
}
