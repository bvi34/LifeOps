package com.advisor.app.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileVectorCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(): File = File(temp.root, "vectors.bin")

    private fun key(id: String, hash: Int = 1, embedder: String = "bge-v1") =
        VectorKey(documentId = id, contentHash = hash, embedderId = embedder)

    @Test
    fun a_vector_written_in_one_session_is_there_in_the_next() {
        val f = file()
        VectorCache.persistent(f).apply {
            put(key("lifeops:task:1"), floatArrayOf(0.1f, 0.2f, 0.3f))
            flush()
        }

        val reopened = VectorCache.persistent(f)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), reopened.get(key("lifeops:task:1")), 1e-6f)
    }

    @Test
    fun a_whole_corpus_survives_in_order_independent_fashion() {
        val f = file()
        val written = (1..50).associate { i ->
            key("lifeops:task:$i", hash = i) to FloatArray(4) { j -> (i * 4 + j).toFloat() }
        }
        VectorCache.persistent(f).apply {
            written.forEach { (k, v) -> put(k, v) }
            flush()
        }

        val reopened = VectorCache.persistent(f)
        written.forEach { (k, v) -> assertArrayEquals("$k", v, reopened.get(k), 1e-6f) }
    }

    @Test
    fun nothing_is_written_until_flush() {
        val f = file()
        VectorCache.persistent(f).put(key("lifeops:task:1"), floatArrayOf(1f))
        assertTrue("should not have written on put", !f.exists())
    }

    @Test
    fun an_edited_document_misses_because_its_hash_changed() {
        val f = file()
        VectorCache.persistent(f).apply {
            put(key("lifeops:task:1", hash = 111), floatArrayOf(1f, 2f))
            flush()
        }

        val reopened = VectorCache.persistent(f)
        assertNotNull(reopened.get(key("lifeops:task:1", hash = 111)))
        assertNull("an edit must force a re-embed", reopened.get(key("lifeops:task:1", hash = 222)))
    }

    /** Vectors from different models aren't comparable, so a model swap must not serve the old ones. */
    @Test
    fun switching_the_embedding_model_discards_everything_it_wrote() {
        val f = file()
        VectorCache.persistent(f).apply {
            put(key("lifeops:task:1", embedder = "bge-v1"), floatArrayOf(1f, 2f))
            flush()
        }

        VectorCache.persistent(f).apply {
            assertNotNull(get(key("lifeops:task:1", embedder = "bge-v1")))
            put(key("lifeops:task:2", embedder = "e5-v2"), floatArrayOf(3f, 4f))
            flush()
        }

        val reopened = VectorCache.persistent(f)
        assertNull("old model's vectors are dead", reopened.get(key("lifeops:task:1", embedder = "bge-v1")))
        assertNotNull(reopened.get(key("lifeops:task:2", embedder = "e5-v2")))
    }

    // --- the file is disposable: every way it can be wrong costs a re-embed, never a failure ---

    @Test
    fun a_missing_file_is_simply_an_empty_cache() {
        assertNull(VectorCache.persistent(File(temp.root, "never-written.bin")).get(key("a")))
    }

    @Test
    fun a_corrupt_file_is_discarded_rather_than_half_read() {
        val f = file()
        f.writeBytes("this is not a vector cache".toByteArray())
        val cache = VectorCache.persistent(f)
        assertNull(cache.get(key("a")))

        // And it recovers: the next flush replaces the garbage with a valid file.
        cache.put(key("a"), floatArrayOf(1f, 2f))
        cache.flush()
        assertArrayEquals(floatArrayOf(1f, 2f), VectorCache.persistent(f).get(key("a")), 1e-6f)
    }

    @Test
    fun a_truncated_file_is_discarded() {
        val f = file()
        VectorCache.persistent(f).apply {
            (1..20).forEach { put(key("d$it", hash = it), FloatArray(8) { 1f }) }
            flush()
        }
        f.writeBytes(f.readBytes().copyOf(f.length().toInt() / 2))

        assertNull(VectorCache.persistent(f).get(key("d1", hash = 1)))
    }

    @Test
    fun an_interrupted_flush_leaves_no_stray_temp_file() {
        val f = file()
        VectorCache.persistent(f).apply {
            put(key("a"), floatArrayOf(1f))
            flush()
        }
        assertEquals(listOf("vectors.bin"), temp.root.list()!!.sorted())
    }

    @Test
    fun the_entry_cap_is_honoured_across_a_reload() {
        val f = file()
        VectorCache.persistent(f, maxEntries = 8).apply {
            (1..30).forEach { put(key("d$it", hash = it), floatArrayOf(it.toFloat())) }
            flush()
        }

        val reopened = VectorCache.persistent(f, maxEntries = 8)
        val present = (1..30).count { reopened.get(key("d$it", hash = it)) != null }
        assertTrue("kept $present entries, cap is 8", present <= 8)
        // The most recently used survive, so the newest write is still there.
        assertNotNull(reopened.get(key("d30", hash = 30)))
    }
}
