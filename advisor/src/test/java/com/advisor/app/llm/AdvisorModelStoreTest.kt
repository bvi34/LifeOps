package com.advisor.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorModelStoreTest {

    @Test
    fun recognises_the_model_advisor_ships_against() {
        assertTrue(AdvisorModelStore.isGenerationGguf("qwen3-4b-q4_k_m.gguf"))
        assertTrue(AdvisorModelStore.isGenerationGguf("Qwen3-4B-Instruct-Q4_K_M.gguf")) // case-insensitive
        assertTrue(AdvisorModelStore.isGenerationGguf("qwen3-4b.gguf"))
    }

    /**
     * Smaller and other-family models are accepted too. Decode speed is bound by how many bytes of
     * weights are read per token, so model size is the biggest single lever on how fast an answer
     * arrives — and choosing that trade is the user's, not something to lock to one filename.
     */
    @Test
    fun accepts_a_smaller_or_different_generation_model() {
        assertTrue(AdvisorModelStore.isGenerationGguf("qwen3-1.7b-q4_k_m.gguf"))
        assertTrue(AdvisorModelStore.isGenerationGguf("qwen3-0.6b-q8_0.gguf"))
        assertTrue(AdvisorModelStore.isGenerationGguf("llama-3.2-3b-instruct-q4_k_m.gguf"))
    }

    /**
     * Both stores scan the same directory, so they have to partition it: anything the embedding store
     * claims is not a generation model, or the two would fight over one file.
     */
    @Test
    fun leaves_the_embedding_model_to_the_embedding_store() {
        for (name in listOf("advisor-embed.gguf", "bge-small-en-v1.5-q8_0.gguf",
                            "e5-base-v2.gguf", "nomic-embed-text-v1.5.gguf")) {
            assertTrue("$name should be an embedding model", EmbeddingModelStore.isEmbeddingGguf(name))
            assertFalse("$name should not be a generation model", AdvisorModelStore.isGenerationGguf(name))
        }
    }

    @Test
    fun rejects_anything_that_is_not_a_gguf() {
        assertFalse("wrong extension", AdvisorModelStore.isGenerationGguf("qwen3-4b-q4_k_m.bin"))
        assertFalse("not a gguf", AdvisorModelStore.isGenerationGguf("qwen3-4b.txt"))
        assertFalse("a partial import", AdvisorModelStore.isGenerationGguf("qwen3-4b-q4_k_m.gguf.part"))
    }

    @Test
    fun formats_bytes_for_the_card() {
        assertEquals("unknown size", AdvisorModelStore.humanBytes(-1))
        assertEquals("512 B", AdvisorModelStore.humanBytes(512))
        assertEquals("1.5 KB", AdvisorModelStore.humanBytes(1536))
        assertEquals("2.3 GB", AdvisorModelStore.humanBytes(2_500_000_000))
    }
}
