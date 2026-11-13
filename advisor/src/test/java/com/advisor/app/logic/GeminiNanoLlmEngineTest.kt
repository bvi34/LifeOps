package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNanoLlmEngineTest {

    /** A Nano under test control: scripted availability and reply, and a record of what it was asked. */
    private class FakeNano(
        var status: NanoAvailability = NanoAvailability.AVAILABLE,
        private val reply: (String) -> NanoResult = { NanoResult("Mow the lawn [1].") }
    ) : NanoBackend {
        override var availability = NanoAvailability.UNKNOWN
        override val modelName: String? = "gemini-nano-test"
        override val tokenLimit: Int? = 4000
        var lastPrompt: String? = null
        var lastParams: NanoParams? = null
        var warmedUp = false

        override fun refresh(): NanoAvailability = status.also { availability = it }

        override fun generate(prompt: String, params: NanoParams, onText: ((String) -> Unit)?): NanoResult {
            lastPrompt = prompt
            lastParams = params
            val result = reply(prompt)
            onText?.let { emit -> result.text.chunked(3).forEach(emit) }
            return result
        }

        override fun warmUp() {
            warmedUp = true
        }
    }

    /** The local engine, standing in for Qwen3 → placeholder: answers with a marker and counts calls. */
    private class FakeLocal : LocalLlmEngine {
        var generated = 0
        var admitted = 0
        var warmedUp = false
        override val spec = ModelSpec("Qwen3-4B", "4B", "Q4_K_M", isPlaceholder = false)
        override val status = "qwen loaded"
        override val contextTokens = 8192
        override fun generate(prompt: AdvisorPrompt): String = "local answer".also { generated++ }
        override fun admit(): InferenceBudget = InferenceBudget.UNCONSTRAINED.also { admitted++ }
        override fun warmUp() {
            warmedUp = true
        }
    }

    private fun grounded(conversation: List<ConversationTurn> = emptyList()): AdvisorPrompt {
        val chunks = listOf(
            RetrievedChunk(
                KnowledgeDocument("d0", SourceApp.LIFEOPS, "task", "Mow the lawn", "Task: Mow the lawn"), 1.0
            )
        )
        return PromptAssembler.assemble("what should I do", chunks).copy(conversation = conversation)
    }

    @Test
    fun switched_on_and_available_nano_answers() {
        val nano = FakeNano()
        val local = FakeLocal()
        val engine = GeminiNanoLlmEngine(nano, local, enabled = { true })

        val budget = engine.admit()
        val answer = engine.generate(grounded())

        assertEquals("Mow the lawn [1].", answer)
        assertEquals(0, local.generated)
        assertEquals("gemini-nano-test", engine.spec.name)
        assertFalse(engine.spec.isPlaceholder)
        assertEquals(GeminiNanoFormat.MAX_OUTPUT_TOKENS, budget.maxTokens)
        assertEquals(GeminiNanoFormat.MAX_OUTPUT_TOKENS, nano.lastParams!!.maxOutputTokens)
        assertEquals(4000, engine.contextTokens)
        assertTrue("Nano is given the grounded context", nano.lastPrompt!!.contains("Mow the lawn"))
    }

    @Test
    fun switched_off_never_touches_nano() {
        val nano = FakeNano()
        val local = FakeLocal()
        val engine = GeminiNanoLlmEngine(nano, local, enabled = { false })

        engine.admit()
        val answer = engine.generate(grounded())

        assertEquals("local answer", answer)
        assertNull(nano.lastPrompt)
        assertEquals("Qwen3-4B", engine.spec.name)
        assertEquals(8192, engine.contextTokens)
        assertEquals(1, local.admitted)
    }

    @Test
    fun phone_without_nano_falls_through_to_local() {
        for (status in listOf(NanoAvailability.UNAVAILABLE, NanoAvailability.DOWNLOADABLE, NanoAvailability.DOWNLOADING)) {
            val nano = FakeNano(status = status)
            val local = FakeLocal()
            val engine = GeminiNanoLlmEngine(nano, local, enabled = { true })

            engine.admit()

            assertEquals("local answer", engine.generate(grounded()))
            assertNull(nano.lastPrompt)
            assertTrue("status says why: $status", engine.status.contains(status.label))
        }
    }

    @Test
    fun failing_call_is_answered_by_local_and_reported() {
        val nano = FakeNano(reply = { throw IllegalStateException("AICore busy") })
        val local = FakeLocal()
        val engine = GeminiNanoLlmEngine(nano, local, enabled = { true })

        engine.admit()
        val answer = engine.generate(grounded())

        assertEquals("local answer", answer)
        assertEquals("the local engine reads the phone for the turn it took over", 1, local.admitted)
        assertEquals("the turn is reported as the local engine's", "Qwen3-4B", engine.spec.name)

        // The next turn tries Nano again, and the failure is on the card meanwhile.
        engine.admit()
        assertTrue(engine.status.contains("AICore busy"))
    }

    @Test
    fun blank_reply_is_answered_by_local() {
        val engine = GeminiNanoLlmEngine(FakeNano(reply = { NanoResult("   ") }), FakeLocal(), enabled = { true })
        engine.admit()
        assertEquals("local answer", engine.generate(grounded()))
    }

    @Test
    fun streaming_reports_the_cleaned_answer_so_far() {
        val engine = GeminiNanoLlmEngine(
            FakeNano(reply = { NanoResult("ANSWER: Mow the lawn.") }), FakeLocal(), enabled = { true }
        )
        engine.admit()
        val partials = mutableListOf<String>()

        val answer = engine.generate(grounded()) { partials += it }

        assertEquals("Mow the lawn.", answer)
        assertEquals("Mow the lawn.", partials.last())
        assertTrue("partials only grow", partials.zipWithNext().all { (a, b) -> b.startsWith(a) })
    }

    @Test
    fun reply_cut_at_the_limit_says_so() {
        val engine = GeminiNanoLlmEngine(
            FakeNano(reply = { NanoResult("A long answer", hitTokenLimit = true) }), FakeLocal(), enabled = { true }
        )
        engine.admit()
        assertTrue(engine.generate(grounded()).endsWith("(Cut short at Gemini Nano's reply limit.)"))
    }

    @Test
    fun switch_is_read_per_turn() {
        var on = false
        val nano = FakeNano()
        val engine = GeminiNanoLlmEngine(nano, FakeLocal(), enabled = { on })

        engine.admit()
        assertEquals("local answer", engine.generate(grounded()))

        on = true
        engine.admit()
        assertEquals("Mow the lawn [1].", engine.generate(grounded()))
    }

    @Test
    fun warm_up_goes_to_whichever_will_answer() {
        val nano = FakeNano()
        val local = FakeLocal()
        GeminiNanoLlmEngine(nano, local, enabled = { true }).warmUp()
        assertTrue(nano.warmedUp)
        assertFalse(local.warmedUp)

        val offNano = FakeNano()
        val offLocal = FakeLocal()
        GeminiNanoLlmEngine(offNano, offLocal, enabled = { false }).warmUp()
        assertFalse(offNano.warmedUp)
        assertTrue(offLocal.warmedUp)
    }

    @Test
    fun prompt_closes_the_earlier_conversation_and_ends_on_the_cue() {
        val prompt = grounded(
            listOf(ConversationTurn(true, "what's due"), ConversationTurn(false, "The lawn."))
        )

        val text = GeminiNanoFormat.forPrompt(prompt)

        assertTrue(text.startsWith(prompt.system.trim()))
        val transcript = text.indexOf("Advisor: The lawn.")
        val closed = text.indexOf("(end of earlier conversation)")
        assertTrue("earlier turns are present", transcript >= 0)
        assertTrue("and closed before the question", closed > transcript && closed < text.indexOf("QUESTION:"))
        assertTrue(text.trimEnd().endsWith("ANSWER:"))
        assertEquals("system prompt appears once", 1, text.split(prompt.system.trim()).size - 1)
    }

    @Test
    fun clean_output_drops_echoed_labels() {
        assertEquals("Mow it.", GeminiNanoFormat.cleanOutput("  ANSWER: Advisor: Mow it.  "))
        assertEquals("Mow it.", GeminiNanoFormat.cleanOutput("assistant:Mow it."))
        assertEquals("The answer: mow it.", GeminiNanoFormat.cleanOutput("The answer: mow it."))
    }
}
