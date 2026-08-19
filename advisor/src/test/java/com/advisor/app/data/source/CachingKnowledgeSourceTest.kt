package com.advisor.app.data.source

import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachingKnowledgeSourceTest {

    /** A source that counts how often it was actually read, and can vary what it returns. */
    private class CountingSource(var title: String = "first") : KnowledgeSource {
        override val source = SourceApp.LIFEOPS
        var loads = 0
        var evictions = 0
        override suspend fun load(): List<KnowledgeDocument> {
            loads++
            return listOf(KnowledgeDocument("id", source, "task", title, "body"))
        }
        override fun evict() { evictions++ }
    }

    /** A change feed under the test's control, standing in for Room's invalidation tracker. */
    private class FakeFeed(private val observable: Boolean = true) : SourceChangeFeed {
        private var listener: (() -> Unit)? = null
        var subscribed = false
        override fun subscribe(onChanged: () -> Unit): Boolean {
            subscribed = true
            listener = onChanged
            return observable
        }
        fun signalWrite() = listener?.invoke()
    }

    @Test
    fun repeated_questions_read_the_source_once() = runTest {
        val delegate = CountingSource()
        val cached = CachingKnowledgeSource(delegate, FakeFeed())

        repeat(5) { cached.load() }

        assertEquals("only the first question should hit the database", 1, delegate.loads)
    }

    @Test
    fun a_write_makes_the_very_next_question_see_it() = runTest {
        val delegate = CountingSource(title = "before")
        val feed = FakeFeed()
        val cached = CachingKnowledgeSource(delegate, feed)

        assertEquals("before", cached.load().single().title)

        // The user adds a task in LifeOps; Room invalidates the table.
        delegate.title = "after"
        feed.signalWrite()

        assertEquals("a cached snapshot must never outlive a write", "after", cached.load().single().title)
        assertEquals(2, delegate.loads)
        // And it settles back into caching rather than reloading forever.
        cached.load()
        assertEquals(2, delegate.loads)
    }

    @Test
    fun a_source_whose_changes_cannot_be_observed_is_never_cached() = runTest {
        val delegate = CountingSource()
        val cached = CachingKnowledgeSource(delegate, FakeFeed(observable = false))

        repeat(3) { cached.load() }

        assertEquals("liveness outranks the saving when there is no change signal", 3, delegate.loads)
    }

    @Test
    fun eviction_drops_the_snapshot_so_revoked_rows_do_not_linger() = runTest {
        val delegate = CountingSource()
        val cached = CachingKnowledgeSource(delegate, FakeFeed())
        cached.load()

        cached.evict()
        cached.load()

        assertEquals("evicting must force a rebuild, not serve the old rows", 2, delegate.loads)
        assertEquals("eviction reaches the wrapped source too", 1, delegate.evictions)
    }

    @Test
    fun subscription_happens_on_first_load_not_at_construction() = runTest {
        val feed = FakeFeed()
        val cached = CachingKnowledgeSource(CountingSource(), feed)

        assertFalse("Room's addObserver touches the database; not on the wiring path", feed.subscribed)
        cached.load()
        assertTrue(feed.subscribed)
    }
}
