package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerTest {

    private fun chunk(id: String, title: String, body: String, score: Double = 1.0) =
        RetrievedChunk(KnowledgeDocument(id, SourceApp.LIFEOPS, "task", title, body), score)

    @Test
    fun numbers_context_blocks_from_one() {
        val prompt = PromptAssembler.assemble(
            "what's due",
            listOf(chunk("a", "A", "body a"), chunk("b", "B", "body b"))
        )
        assertEquals(listOf(1, 2), prompt.context.map { it.ref })
        assertEquals("what's due", prompt.question)
    }

    @Test
    fun trims_over_long_excerpts() {
        val long = "word ".repeat(200) // ~1000 chars
        val prompt = PromptAssembler.assemble("q", listOf(chunk("a", "A", long)))
        val excerpt = prompt.context.first().excerpt
        assertTrue(excerpt.length <= PromptAssembler.MAX_EXCERPT + 1)
        assertTrue(excerpt.endsWith("…"))
    }

    @Test
    fun render_includes_system_question_and_citations() {
        val prompt = PromptAssembler.assemble("do I have flour", listOf(chunk("a", "Flour", "In stock: 1 kg")))
        val text = prompt.render()
        assertTrue(text.contains(PromptAssembler.SYSTEM))
        assertTrue(text.contains("[1]"))
        assertTrue(text.contains("LifeOps"))
        assertTrue(text.contains("QUESTION: do I have flour"))
    }

    @Test
    fun render_handles_empty_context() {
        val prompt = PromptAssembler.assemble("anything", emptyList())
        assertTrue(prompt.render().contains("CONTEXT: (none available)"))
    }

    @Test
    fun render_includes_recent_conversation_turns() {
        val prompt = PromptAssembler.assemble(
            "who wrote it",
            emptyList(),
            conversation = listOf(
                ConversationTurn(fromUser = true, text = "tell me about Dune"),
                ConversationTurn(fromUser = false, text = "Dune is a novel by Frank Herbert")
            )
        )
        val text = prompt.render()
        assertTrue(text.contains("CONVERSATION"))
        assertTrue(text.contains("User: tell me about Dune"))
        assertTrue(text.contains("Advisor: Dune is a novel by Frank Herbert"))
    }

    @Test
    fun a_user_written_system_prompt_replaces_the_default() {
        val prompt = PromptAssembler.assemble(
            "anything", emptyList(), system = "Answer only in haiku."
        )
        assertEquals("Answer only in haiku.", prompt.system)
        assertTrue(prompt.render().startsWith("Answer only in haiku."))
    }

    @Test
    fun a_blank_system_prompt_falls_back_to_the_default_rather_than_none() {
        // An empty standing instruction makes the model answer unusably, so it must not be reachable
        // by clearing the editor.
        assertEquals(PromptAssembler.SYSTEM, PromptAssembler.assemble("q", emptyList(), system = "   ").system)
        assertEquals(PromptAssembler.SYSTEM, PromptAssembler.assemble("q", emptyList()).system)
    }

    @Test
    fun the_default_prompt_forbids_restating_earlier_replies() {
        // Guards the other half of the runaway-answer bug: the shipped instruction used to offer a
        // literal example opener, which the model reproduced verbatim on every turn.
        assertTrue(PromptAssembler.SYSTEM.contains("Never repeat or restate an earlier reply"))
        assertFalse(PromptAssembler.SYSTEM.contains("It looks like today you have"))
    }

    @Test
    fun conversation_can_be_left_out_for_callers_that_emit_real_turns() {
        val prompt = PromptAssembler.assemble(
            "follow up",
            emptyList(),
            conversation = listOf(ConversationTurn(fromUser = false, text = "an earlier answer"))
        )
        assertTrue(prompt.render().contains("an earlier answer"))
        assertFalse(prompt.render(includeConversation = false).contains("an earlier answer"))
        assertFalse(prompt.render(includeConversation = false).contains("CONVERSATION"))
    }
}
