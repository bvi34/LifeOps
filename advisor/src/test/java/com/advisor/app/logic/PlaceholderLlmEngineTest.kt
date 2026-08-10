package com.advisor.app.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderLlmEngineTest {

    private val engine = PlaceholderLlmEngine()

    private fun promptWith(vararg titles: String): AdvisorPrompt {
        val chunks = titles.mapIndexed { i, t ->
            RetrievedChunk(KnowledgeDocument("d$i", SourceApp.LIFEOPS, "task", t, "Task: $t. Status: pending"), 1.0)
        }
        return PromptAssembler.assemble("what should I do", chunks)
    }

    @Test
    fun spec_advertises_a_local_placeholder_model() {
        assertTrue(engine.spec.isPlaceholder)
        assertTrue(engine.spec.parameters.contains("2"))
        assertTrue(engine.spec.label().contains("placeholder"))
    }

    @Test
    fun empty_context_explains_and_points_at_permissions() {
        val answer = engine.generate(PromptAssembler.assemble("anything", emptyList()))
        assertTrue(answer.contains("Permissions") || answer.contains("granted"))
    }

    @Test
    fun grounded_answer_cites_and_is_deterministic() {
        val prompt = promptWith("Mow the lawn", "File taxes")
        val a = engine.generate(prompt)
        val b = engine.generate(prompt)
        assertTrue("cites [1]", a.contains("[1]"))
        assertTrue("cites [2]", a.contains("[2]"))
        assertTrue("mentions a retrieved title", a.contains("Mow the lawn"))
        assertFalse(a.isBlank())
        assertTrue("deterministic", a == b)
    }
}
