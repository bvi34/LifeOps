package com.advisor.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingModelStoreTest {

    @Test
    fun recognizes_the_canonical_embedding_filename() {
        assertTrue(EmbeddingModelStore.isEmbeddingGguf(EmbeddingModelStore.DEFAULT_NAME))
        assertTrue(EmbeddingModelStore.isEmbeddingGguf("advisor-embed.gguf"))
    }

    @Test
    fun recognizes_common_embedding_model_names() {
        assertTrue(EmbeddingModelStore.isEmbeddingGguf("bge-small-en-v1.5-q8_0.gguf"))
        assertTrue(EmbeddingModelStore.isEmbeddingGguf("nomic-embed-text-v1.5.Q4_K_M.gguf"))
        assertTrue(EmbeddingModelStore.isEmbeddingGguf("all-MiniLM-L6-v2.gguf"))
        assertTrue(EmbeddingModelStore.isEmbeddingGguf("e5-small.gguf"))
    }

    @Test
    fun rejects_the_generation_model_and_non_gguf_files() {
        // The generation weights must not be mistaken for an embedding model, and vice-versa.
        assertFalse(EmbeddingModelStore.isEmbeddingGguf("qwen3-4b-q4_k_m.gguf"))
        assertFalse(EmbeddingModelStore.isEmbeddingGguf("embeddings.txt"))
        assertFalse(EmbeddingModelStore.isEmbeddingGguf("notes.md"))
    }

    @Test
    fun case_insensitive() {
        assertTrue(EmbeddingModelStore.isEmbeddingGguf("Advisor-Embed.GGUF"))
        assertTrue(EmbeddingModelStore.isEmbeddingGguf("BGE-base.GGUF"))
    }

    @Test
    fun reuses_the_shared_byte_formatter() {
        // Sanity: the embedding card renders sizes via the same helper as the generation card.
        assertEquals("1.0 KB", AdvisorModelStore.humanBytes(1024))
    }
}
