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
        var warmedUp = false
        /** A backend reports the loaded file here, and the engine's spec is read from it. */
        override var detail = "qwen3-4b-q4_k_m.gguf"

        override fun warmUp() {
            warmedUp = true
        }
        override fun generate(prompt: String, params: GenerationParams): String {
            lastPrompt = prompt
            return reply(prompt)
        }

        /** Streams the scripted reply one character at a time — the worst case for partial cleaning. */
        override fun generate(
            prompt: String,
            params: GenerationParams,
            onToken: (String) -> Unit
        ): String {
            lastPrompt = prompt
            val text = reply(prompt)
            for (ch in text) onToken(ch.toString())
            return text
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
        assertEquals("4B", engine.spec.parameters)
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

    @Test
    fun streaming_reports_the_answer_as_it_forms_and_still_returns_it_whole() {
        val backend = FakeBackend(isReady = true) { "Mow the lawn.<|im_end|>" }
        val seen = mutableListOf<String>()
        val answer = Qwen3LlmEngine(backend).generate(grounded()) { seen += it }

        assertEquals("Mow the lawn.", answer)
        // Cumulative, so the last thing reported is the finished answer and the UI never has to
        // reassemble anything.
        assertEquals(answer, seen.last())
        assertTrue(seen.size > 1)
        // Every report is a prefix of the answer: text is only ever added or retracted as a whole,
        // never rewritten into something the user did not see arrive.
        assertTrue(seen.all { answer.startsWith(it) })
        // The control token is never shown, not even as the "<|" that begins it.
        assertTrue(seen.none { it.contains("<|") })
    }

    @Test
    fun streaming_falls_back_to_the_placeholder_when_no_model_is_loaded() {
        val backend = FakeBackend(isReady = false)
        val seen = mutableListOf<String>()
        val answer = Qwen3LlmEngine(backend).generate(grounded()) { seen += it }

        assertTrue(answer.isNotBlank())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun a_reply_that_is_only_reasoning_streams_nothing_and_falls_back() {
        // cleanOutput reduces this to blank, so the engine falls back — and nothing partial should
        // have been shown for an answer that turns out not to exist.
        val backend = FakeBackend(isReady = true) { "<think>hmm</think>" }
        val seen = mutableListOf<String>()
        val answer = Qwen3LlmEngine(backend).generate(grounded()) { seen += it }

        assertEquals(PlaceholderLlmEngine().generate(grounded()), answer)
        assertTrue(seen.all { it.isEmpty() })
    }

    @Test
    fun warm_up_reaches_the_backend_so_the_first_question_does_not_pay_to_load() {
        val backend = FakeBackend(isReady = true)
        Qwen3LlmEngine(backend).warmUp()
        assertTrue(backend.warmedUp)
    }

    /**
     * The model card exists to say what is answering, so a different installed model has to be
     * reported as itself — the size especially, since it is what decides how fast an answer arrives.
     */
    @Test
    fun the_spec_names_whichever_model_is_actually_loaded() {
        val backend = FakeBackend(isReady = true)
        backend.detail = "qwen3-1.7b-q4_k_m.gguf"
        val spec = Qwen3LlmEngine(backend).spec

        assertEquals("Qwen3-1.7B", spec.name)
        assertEquals("1.7B", spec.parameters)
        assertFalse(spec.isPlaceholder)
    }

    @Test
    fun with_no_model_loaded_the_spec_is_the_placeholders() {
        val spec = Qwen3LlmEngine(FakeBackend(isReady = false)).spec
        assertTrue("should not claim a real model", spec.isPlaceholder)
    }
}

class Qwen3LlmEngineBudgetTest {

    private class Backend(
        override var isLoaded: Boolean = true,
        override val modelBytes: Long = 2_500_000_000L,
        private val reply: String = "Mow the lawn.<|im_end|>"
    ) : LlmBackend {
        override val isReady = true
        override val detail = "qwen3-4b-q4_k_m.gguf"
        var lastParams: GenerationParams? = null
        var calls = 0
        var warmedUp = false
        var interruption: String? = null

        override fun warmUp() { warmedUp = true }
        override fun generate(prompt: String, params: GenerationParams): String {
            calls++
            lastParams = params
            return reply
        }
        override fun takeInterruption(): String? = interruption.also { interruption = null }
    }

    private val gib = 1024L * 1024 * 1024
    private val cool = DeviceState(totalMemBytes = 8 * gib, availMemBytes = 4 * gib)

    private fun prompt() = PromptAssembler.assemble(
        "what should I do",
        listOf(RetrievedChunk(KnowledgeDocument("d0", SourceApp.LIFEOPS, "task", "Mow the lawn", "Task: Mow the lawn"), 1.0))
    )

    @Test
    fun without_a_probe_nothing_is_limited() {
        val backend = Backend()
        val engine = Qwen3LlmEngine(backend)
        assertEquals(InferenceBudget.UNCONSTRAINED, engine.admit())
        engine.generate(prompt())
        assertEquals(GenerationParams().maxTokens, backend.lastParams!!.maxTokens)
        assertEquals(null, engine.describeDevice())
    }

    @Test
    fun a_reduced_turn_hands_the_backend_the_shorter_limit() {
        val backend = Backend()
        val engine = Qwen3LlmEngine(backend, device = { cool.copy(thermal = ThermalLevel.MODERATE) })
        engine.admit()
        assertEquals("Mow the lawn.", engine.generate(prompt()))
        assertEquals(256, backend.lastParams!!.maxTokens)
    }

    @Test
    fun a_paused_turn_answers_from_the_records_and_says_why() {
        val backend = Backend()
        val engine = Qwen3LlmEngine(backend, device = { cool.copy(thermal = ThermalLevel.CRITICAL) })
        engine.admit()

        val answer = engine.generate(prompt())

        assertEquals("the model never ran", 0, backend.calls)
        assertTrue(answer.contains("[1]"))
        assertTrue(answer.contains("The model is paused — phone is critically hot"))
        assertTrue("the card reports the placeholder while paused", engine.spec.isPlaceholder)
        assertTrue(engine.status.contains("Paused — phone is critically hot"))
    }

    @Test
    fun the_budget_is_settled_per_turn_not_per_read() {
        var state = cool.copy(thermal = ThermalLevel.SEVERE)
        val engine = Qwen3LlmEngine(Backend(), device = { state })
        engine.admit()
        state = cool
        assertTrue("still the paused turn until the next admit", engine.spec.isPlaceholder)
        engine.admit()
        assertFalse(engine.spec.isPlaceholder)
    }

    @Test
    fun warm_up_is_skipped_when_the_phone_cannot_afford_the_bet() {
        val backend = Backend(isLoaded = false)
        val engine = Qwen3LlmEngine(backend, device = { cool.copy(powerSave = true) })
        engine.warmUp()
        assertFalse(backend.warmedUp)

        val relaxed = Backend(isLoaded = false)
        Qwen3LlmEngine(relaxed, device = { cool }).warmUp()
        assertTrue(relaxed.warmedUp)
    }

    @Test
    fun an_answer_stopped_early_keeps_its_text_and_says_so() {
        val backend = Backend().apply { interruption = "the phone is very hot" }
        val engine = Qwen3LlmEngine(backend, device = { cool })
        engine.admit()
        assertEquals("Mow the lawn.\n\n(Stopped early — the phone is very hot.)", engine.generate(prompt()))
        assertEquals("cleared by reading", null, backend.takeInterruption())
    }

    @Test
    fun a_probe_that_throws_is_treated_as_unmeasured() {
        val backend = Backend()
        val engine = Qwen3LlmEngine(backend, device = { error("binder died") })
        assertEquals(InferenceBudget.UNCONSTRAINED, engine.admit())
        assertEquals("Mow the lawn.", engine.generate(prompt()))
    }
}
