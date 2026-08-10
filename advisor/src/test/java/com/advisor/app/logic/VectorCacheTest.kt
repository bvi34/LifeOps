package com.advisor.app.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorCacheTest {

    private fun doc(id: String, title: String, body: String) =
        KnowledgeDocument(id, SourceApp.LIFEOPS, "test", title, body)

    @Test
    fun stores_and_returns_a_vector() {
        val cache = VectorCache.inMemory()
        val key = VectorKey("d1", 42, "m")
        cache.put(key, floatArrayOf(1f, 2f))
        assertArrayEquals(floatArrayOf(1f, 2f), cache.get(key), 0f)
    }

    @Test
    fun miss_returns_null() {
        assertNull(VectorCache.inMemory().get(VectorKey("nope", 0, "m")))
    }

    @Test
    fun content_hash_changes_when_title_or_body_changes() {
        val base = VectorCache.contentHash(doc("d", "Title", "Body"))
        assertNotEquals(base, VectorCache.contentHash(doc("d", "Title!", "Body")))
        assertNotEquals(base, VectorCache.contentHash(doc("d", "Title", "Body!")))
        // Same content ⇒ same hash (id is not part of it; the key carries id separately).
        assertEquals(base, VectorCache.contentHash(doc("other", "Title", "Body")))
    }

    @Test
    fun evicts_least_recently_used_past_capacity() {
        val cache = VectorCache.inMemory(maxEntries = 2)
        val a = VectorKey("a", 0, "m")
        val b = VectorKey("b", 0, "m")
        val c = VectorKey("c", 0, "m")
        cache.put(a, floatArrayOf(1f))
        cache.put(b, floatArrayOf(2f))
        cache.get(a)                    // touch a so b is now the eldest
        cache.put(c, floatArrayOf(3f))  // evicts b
        assertArrayEquals(floatArrayOf(1f), cache.get(a), 0f)
        assertNull(cache.get(b))
        assertArrayEquals(floatArrayOf(3f), cache.get(c), 0f)
    }
}
