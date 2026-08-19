package com.advisor.app.data.source

import android.util.Log
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase

/**
 * How a [KnowledgeSource] learns that the data underneath it changed.
 *
 * Caching a source's documents is only safe if something can say, precisely, when the snapshot went
 * stale. A time-based cache cannot: it either serves an answer that ignores the task the user added
 * ten seconds ago, or it expires so often that it is not a cache. This is the seam that makes the
 * cache exact instead, and a fake implementation makes [CachingKnowledgeSource] testable on the JVM.
 */
interface SourceChangeFeed {

    /**
     * Call [onChanged] whenever the underlying data is written. Returns false if changes cannot be
     * observed, in which case the caller must not cache — correctness outranks the saving.
     */
    fun subscribe(onChanged: () -> Unit): Boolean
}

/**
 * A [SourceChangeFeed] over Room's own [InvalidationTracker] — the mechanism Room already uses to
 * re-run `Flow` queries, so it fires for every write to [tables] from anywhere in the process,
 * including the owning app's own screens. That is exactly the signal needed: Advisor reads other
 * apps' databases in-process and never writes to them, so every change it must react to is someone
 * else's write.
 *
 * A wrong table name would throw, so [subscribe] fails soft and returns false: the source then
 * reloads on every question, which is merely the old behaviour, rather than crashing the chat.
 */
class RoomChangeFeed(
    private val tables: Array<String>,
    private val database: () -> RoomDatabase
) : SourceChangeFeed {

    override fun subscribe(onChanged: () -> Unit): Boolean = runCatching {
        database().invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(tables) {
                override fun onInvalidated(tables: Set<String>) = onChanged()
            }
        )
        true
    }.getOrElse {
        Log.w(TAG, "Cannot observe ${tables.joinToString()} — this source will not be cached.", it)
        false
    }

    private companion object {
        const val TAG = "RoomChangeFeed"
    }
}
