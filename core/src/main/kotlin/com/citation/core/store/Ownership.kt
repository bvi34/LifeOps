package com.citation.core.store

import com.citation.core.model.SourceType

/**
 * The **two physical stores split by ownership**, expressed as policy so eviction *structurally*
 * cannot reach owned data.
 *
 * The Android layer backs each of these with a real directory; the invariant that matters lives
 * here as a pure decision: the eviction job is only ever pointed at the [DISPOSABLE] store, and
 * owned files, all notes/highlights, and sync state live in the [SOVEREIGN] store which the
 * auto-evictor is never handed. Getting the classification wrong is the only way to lose owned data,
 * so it is one small, testable function rather than scattered `if`s.
 */
enum class Store {
    /** Borrowed content: RR chapters, prefetch buffers. The *only* directory eviction walks. */
    DISPOSABLE,

    /** Owned files, all notes/highlights, sync state. Backed up / sync-carried; never auto-evicted. */
    SOVEREIGN
}

object Ownership {

    /**
     * Which store a piece of *content* for [source] belongs in. Only borrowed, refetchable bodies
     * (Royal Road) are disposable; owned files (EPUB/PDF), licensed read-in-place (O'Reilly keeps no
     * body), and internal placeholders are sovereign.
     */
    fun contentStore(source: SourceType): Store =
        if (source.isBorrowedCache) Store.DISPOSABLE else Store.SOVEREIGN

    /**
     * Whether a cached item for [source] may be auto-evicted at all. Notes and owned files answer
     * `false` structurally (they aren't in the disposable store); this is the guard the eviction job
     * asserts before ever touching an item, plus the favourite/open-story exemptions the caller adds.
     */
    fun isEvictable(source: SourceType, isFavorite: Boolean, isOpen: Boolean): Boolean =
        contentStore(source) == Store.DISPOSABLE && !isFavorite && !isOpen

    /** Notes and highlights are *always* sovereign — they outlive any source. */
    fun noteStore(): Store = Store.SOVEREIGN
}
