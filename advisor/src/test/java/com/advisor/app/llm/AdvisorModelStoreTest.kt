package com.advisor.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorModelStoreTest {

    @Test
    fun recognises_qwen3_4b_gguf_names() {
        assertTrue(AdvisorModelStore.isQwen3Gguf("qwen3-4b-q4_k_m.gguf"))
        assertTrue(AdvisorModelStore.isQwen3Gguf("Qwen3-4B-Instruct-Q4_K_M.gguf")) // case-insensitive
        assertTrue(AdvisorModelStore.isQwen3Gguf("qwen3-4b.gguf"))
    }

    @Test
    fun rejects_non_matching_names() {
        assertFalse("wrong model", AdvisorModelStore.isQwen3Gguf("llama-3-8b-q4.gguf"))
        assertFalse("wrong extension", AdvisorModelStore.isQwen3Gguf("qwen3-4b-q4_k_m.bin"))
        assertFalse("not a gguf", AdvisorModelStore.isQwen3Gguf("qwen3-4b.txt"))
        assertFalse("partial", AdvisorModelStore.isQwen3Gguf("qwen3.gguf"))
    }

    @Test
    fun formats_bytes_for_the_card() {
        assertEquals("unknown size", AdvisorModelStore.humanBytes(-1))
        assertEquals("512 B", AdvisorModelStore.humanBytes(512))
        assertEquals("1.5 KB", AdvisorModelStore.humanBytes(1536))
        assertEquals("2.3 GB", AdvisorModelStore.humanBytes(2_500_000_000))
    }
}
