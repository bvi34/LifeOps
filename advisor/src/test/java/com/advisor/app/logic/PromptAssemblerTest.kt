package com.advisor.app.logic

import org.junit.Assert.assertEquals
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
}
