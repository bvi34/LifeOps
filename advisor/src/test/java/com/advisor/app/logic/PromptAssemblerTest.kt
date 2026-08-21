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
    fun the_default_prompt_forbids_claiming_writes_it_cannot_make() {
        // The model has no way to create anything in the other apps (task commands are performed
        // before it is ever asked), so it must never report having done so.
        assertTrue(PromptAssembler.SYSTEM.contains("never say you added, created or changed a task"))
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
        // The section header, not the bare word — the standing instruction names CONVERSATION too.
        assertFalse(prompt.render(includeConversation = false).contains("CONVERSATION (recent turns"))
    }

    /**
     * The exposed numbering must be *the* numbering. A reader that maps an answer's `[n]` back to a
     * document is only correct while it agrees with what the prompt showed, so the two cannot be
     * allowed to come from separate copies of "index + 1".
     */
    @Test
    fun exposed_ref_numbering_is_the_one_the_prompt_shows() {
        val chunks = listOf(
            chunk("lifeops:task:1", "Water the plants", "body a"),
            chunk("citation:book:7", "Dune", "body b"),
            chunk("logistics:item:4", "Oat milk", "body c")
        )
        val prompt = PromptAssembler.assemble("what's up?", chunks)
        assertEquals(prompt.context, PromptAssembler.blocks(chunks))
        assertEquals(listOf(1, 2, 3), PromptAssembler.blocks(chunks).map { it.ref })
    }

    @Test
    fun a_lone_row_gets_the_full_excerpt() {
        val body = "x".repeat(1000)
        val prompt = PromptAssembler.assemble("what's due", listOf(chunk("a", "A", body)))
        // Trimmed to the per-row cap (plus the ellipsis the trim adds), not to a share of the budget.
        assertEquals(PromptAssembler.MAX_EXCERPT, PromptAssembler.excerptShare(1))
        assertTrue(prompt.context.single().excerpt.length <= PromptAssembler.MAX_EXCERPT + 1)
    }

    @Test
    fun many_rows_share_one_budget_instead_of_each_taking_the_cap() {
        val chunks = (1..6).map { chunk("d$it", "T$it", "x".repeat(1000)) }
        val prompt = PromptAssembler.assemble("what's due", chunks)
        val total = prompt.context.sumOf { it.excerpt.length }
        // Six rows at the old flat cap would be ~2400 characters of volatile prompt.
        assertTrue("context was $total chars", total <= PromptAssembler.CONTEXT_BUDGET + chunks.size)
        assertEquals(6, prompt.context.size)
    }

    @Test
    fun a_row_is_never_trimmed_below_the_floor() {
        val chunks = (1..40).map { chunk("d$it", "T$it", "x".repeat(1000)) }
        val share = PromptAssembler.excerptShare(chunks.size)
        assertEquals(PromptAssembler.MIN_EXCERPT, share)
        val prompt = PromptAssembler.assemble("what's due", chunks)
        assertTrue(prompt.context.all { it.excerpt.length >= PromptAssembler.MIN_EXCERPT })
    }

    @Test
    fun short_bodies_are_untouched_by_the_budget() {
        // The common case: rows are a line or two, so nothing is trimmed at all.
        val chunks = (1..6).map { chunk("d$it", "T$it", "Task: mow the lawn") }
        val prompt = PromptAssembler.assemble("what's due", chunks)
        assertTrue(prompt.context.all { it.excerpt == "Task: mow the lawn" })
    }
}
