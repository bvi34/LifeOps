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

    private fun userProfile() = Profile(
        key = "user", name = "User", kind = ProfileKind.USER,
        entries = listOf(ProfileEntry("Name: Brenden Villaruel"))
    )

    @Test
    fun surfaces_the_matching_fact_from_the_user_profile() {
        val prompt = PromptAssembler.assemble("what is my name", emptyList(), profiles = listOf(userProfile()))
        val answer = engine.generate(prompt)
        assertTrue("surfaces the profile fact, not just the profile name", answer.contains("Brenden Villaruel"))
    }

    @Test
    fun who_am_i_surfaces_the_user_profile_without_term_overlap() {
        // "who am I" shares no words with "Name: …" — the whole user profile should still be surfaced.
        val prompt = PromptAssembler.assemble("who am I", emptyList(), profiles = listOf(userProfile()))
        assertTrue(engine.generate(prompt).contains("Brenden Villaruel"))
    }

    @Test
    fun unrelated_question_does_not_dump_the_profile() {
        val chunk = RetrievedChunk(
            KnowledgeDocument("d0", SourceApp.LIFEOPS, "task", "Mow the lawn", "Task: Mow the lawn. Status: pending"),
            1.0
        )
        val prompt = PromptAssembler.assemble("what tasks are pending", listOf(chunk), profiles = listOf(userProfile()))
        val answer = engine.generate(prompt)
        assertFalse("no personal fact leaks into an unrelated answer", answer.contains("Brenden Villaruel"))
        assertTrue("still answers the actual question", answer.contains("Mow the lawn"))
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
