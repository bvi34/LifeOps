package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GgufNameTest {

    @Test
    fun reads_the_model_advisor_ships_against() {
        val spec = GgufName.specOf("qwen3-4b-q4_k_m.gguf")
        assertEquals("Qwen3-4B", spec.name)
        assertEquals("4B", spec.parameters)
        assertEquals("Q4_K_M / GGUF", spec.quantization)
        assertFalse(spec.isPlaceholder)
    }

    @Test
    fun reads_a_fractional_parameter_count() {
        val spec = GgufName.specOf("Qwen3-1.7B-Q4_K_M.gguf")
        assertEquals("Qwen3-1.7B", spec.name)
        assertEquals("1.7B", spec.parameters)
    }

    @Test
    fun reads_other_families_and_quantizations() {
        assertEquals("0.6B", GgufName.specOf("qwen3-0.6b-q8_0.gguf").parameters)
        assertEquals("Q8_0 / GGUF", GgufName.specOf("qwen3-0.6b-q8_0.gguf").quantization)
        assertEquals("IQ4_XS / GGUF", GgufName.specOf("llama-3.2-3b-iq4_xs.gguf").quantization)
        assertEquals("3B", GgufName.specOf("llama-3.2-3b-iq4_xs.gguf").parameters)
    }

    @Test
    fun keeps_the_rest_of_a_descriptive_name() {
        assertEquals("Qwen3-4B-Instruct", GgufName.specOf("qwen3-4b-instruct-q4_k_m.gguf").name)
    }

    @Test
    fun a_path_is_reduced_to_its_file_name() {
        assertEquals("Qwen3-4B", GgufName.specOf("/sdcard/models/qwen3-4b-q4_k_m.gguf").name)
    }

    /** A filename is a weak source of truth, so an unreadable one is shown, not guessed at. */
    @Test
    fun an_unparseable_name_is_reported_as_itself_rather_than_invented() {
        val spec = GgufName.specOf("my-model.gguf")
        assertEquals("My-Model", spec.name)
        assertEquals("unknown size", spec.parameters)
        assertEquals("GGUF", spec.quantization)
    }

    @Test
    fun a_name_that_is_only_a_quantization_tag_still_shows_something() {
        val spec = GgufName.specOf("q4_k_m.gguf")
        assertEquals("q4_k_m", spec.name)
    }
}
