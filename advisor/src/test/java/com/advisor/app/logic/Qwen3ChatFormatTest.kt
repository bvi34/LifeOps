package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Qwen3ChatFormatTest {

    private fun grounded(question: String, vararg titles: String): AdvisorPrompt {
        val chunks = titles.mapIndexed { i, t ->
            RetrievedChunk(
                KnowledgeDocument("d$i", SourceApp.LIFEOPS, "task", t, "Task: $t. Status: pending"), 1.0
            )
        }
        return PromptAssembler.assemble(question, chunks)
    }

    @Test
    fun build_emits_chatml_turns_in_order() {
        val out = Qwen3ChatFormat.build("SYS", "hello", enableThinking = false)
        val sys = out.indexOf("<|im_start|>system")
        val user = out.indexOf("<|im_start|>user")
        val asst = out.indexOf("<|im_start|>assistant")
        assertTrue(sys in 0 until user)
        assertTrue(user < asst)
        assertTrue("system content present", out.contains("SYS"))
        assertTrue("user content present", out.contains("hello"))
        assertTrue("assistant turn is open (no closing im_end after it)", out.trimEnd().endsWith(">"))
    }

    @Test
    fun thinking_disabled_seeds_empty_think_block() {
        val off = Qwen3ChatFormat.build("SYS", "hi", enableThinking = false)
        val on = Qwen3ChatFormat.build("SYS", "hi", enableThinking = true)
        assertTrue("non-thinking pre-seeds an empty block", off.contains("<think>\n\n</think>"))
        assertFalse("thinking mode leaves the block to the model", on.contains("</think>"))
    }

    @Test
    fun for_prompt_puts_system_in_system_turn_only() {
        val prompt = grounded("what should I do", "Mow the lawn")
        val out = Qwen3ChatFormat.forPrompt(prompt)

        // The system contract appears once, inside the system turn.
        val systemStart = out.indexOf("<|im_start|>system")
        val userStart = out.indexOf("<|im_start|>user")
        val systemTurn = out.substring(systemStart, userStart)
        assertTrue(systemTurn.contains(PromptAssembler.SYSTEM))

        // The user turn carries the assembled context, not the system preamble or the trailing cue.
        val userTurn = out.substring(userStart)
        assertTrue("question reaches the user turn", userTurn.contains("what should I do"))
        assertTrue("context reaches the user turn", userTurn.contains("Mow the lawn"))
        assertFalse("system preamble not duplicated into the user turn",
            userTurn.contains(PromptAssembler.SYSTEM))
        assertFalse("trailing ANSWER: cue is dropped", userTurn.contains("ANSWER:"))
    }

    @Test
    fun clean_output_strips_thinking_and_control_tokens() {
        val raw = "<think>\nlet me reason\n</think>\n\nMow the lawn first.<|im_end|>\n<|im_start|>user"
        assertEquals("Mow the lawn first.", Qwen3ChatFormat.cleanOutput(raw))
    }

    @Test
    fun clean_output_handles_dangling_seed_and_plain_text() {
        // The empty seed block with no answer-side think tags: everything after </think> is the answer.
        assertEquals("Done.", Qwen3ChatFormat.cleanOutput("</think>\n\nDone."))
        // Plain text passes through, trimmed.
        assertEquals("Just an answer.", Qwen3ChatFormat.cleanOutput("  Just an answer.  "))
    }
}
