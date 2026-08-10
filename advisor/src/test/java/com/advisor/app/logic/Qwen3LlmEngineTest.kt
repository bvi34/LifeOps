package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Qwen3LlmEngineTest {

    /** A backend under test control: captures the prompt it was given and returns a scripted reply. */
    private class FakeBackend(
        override val isReady: Boolean,
        private val reply: (String) -> String = { "" }
    ) : LlmBackend {
        var lastPrompt: String? = null
        override val detail = "fake"
        override fun generate(prompt: String, params: GenerationParams): String {
            lastPrompt = prompt
            return reply(prompt)
        }
    }

    private fun grounded(): AdvisorPrompt {
        val chunks = listOf(
            RetrievedChunk(
                KnowledgeDocument("d0", SourceApp.LIFEOPS, "task", "Mow the lawn", "Task: Mow the lawn"), 1.0
            )
        )
        return PromptAssembler.assemble("what should I do", chunks)
    }

    @Test
    fun ready_backend_answers_and_advertises_qwen3() {
        val backend = FakeBackend(isReady = true) { "<think>reasoning</think>\n\nMow the lawn.<|im_end|>" }
        val engine = Qwen3LlmEngine(backend)

        val answer = engine.generate(grounded())

        assertEquals("Mow the lawn.", answer)
        assertFalse("reports the real model, not the placeholder", engine.spec.isPlaceholder)
        assertEquals("Qwen3-4B", engine.spec.name)
        assertTrue("feeds the backend a ChatML prompt", backend.lastPrompt!!.contains("<|im_start|>"))
    }

    @Test
    fun unready_backend_falls_back_to_placeholder() {
        val backend = FakeBackend(isReady = false) { "should never be called" }
        val engine = Qwen3LlmEngine(backend)

        val answer = engine.generate(grounded())

        assertTrue("placeholder cites its grounding", answer.contains("[1]"))
        assertTrue("spec reflects the placeholder is answering", engine.spec.isPlaceholder)
        assertEquals("backend was not invoked", null, backend.lastPrompt)
    }

    @Test
    fun blank_generation_falls_back_to_placeholder() {
        val backend = FakeBackend(isReady = true) { "   " }
        val engine = Qwen3LlmEngine(backend)

        val answer = engine.generate(grounded())

        // The backend ran, but an empty reply is replaced by the grounded placeholder answer.
        assertTrue("prompt was sent", backend.lastPrompt != null)
        assertTrue("falls back to a real, cited answer", answer.contains("[1]"))
    }

    @Test
    fun throwing_backend_falls_back_instead_of_crashing() {
        val backend = FakeBackend(isReady = true) { error("native boom") }
        val engine = Qwen3LlmEngine(backend)

        val answer = engine.generate(grounded())

        assertFalse(answer.isBlank())
        assertTrue(answer.contains("Mow the lawn"))
    }
}
