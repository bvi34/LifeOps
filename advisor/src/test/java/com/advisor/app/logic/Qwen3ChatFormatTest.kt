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

    private fun withConversation(question: String, vararg turns: ConversationTurn): AdvisorPrompt =
        PromptAssembler.assemble(question, emptyList(), conversation = turns.toList())

    @Test
    fun prior_turns_are_real_chatml_turns_not_flat_text() {
        val out = Qwen3ChatFormat.forPrompt(
            withConversation(
                "What's your name?",
                ConversationTurn(fromUser = true, text = "test"),
                ConversationTurn(fromUser = false, text = "It looks like today you've been testing the waters.")
            )
        )
        // The regression: prior turns rendered as "Advisor: …" inside one user message have nothing
        // closing them, so the model continues the previous answer instead of writing a new one, and
        // every reply grows by the whole of the one before it.
        assertFalse("no flat Advisor: label", out.contains("Advisor: It looks like"))
        assertFalse("no flat conversation block", out.contains("CONVERSATION (recent turns"))
        assertTrue(
            "prior assistant turn is closed with an end-of-turn token",
            out.contains("<|im_start|>assistant\nIt looks like today you've been testing the waters.<|im_end|>")
        )
        assertTrue(
            "prior user turn is a real turn",
            out.contains("<|im_start|>user\ntest<|im_end|>")
        )
    }

    @Test
    fun turns_are_ordered_oldest_first_then_this_question_last() {
        val out = Qwen3ChatFormat.forPrompt(
            withConversation(
                "what can you do?",
                ConversationTurn(fromUser = true, text = "test"),
                ConversationTurn(fromUser = false, text = "first reply"),
                ConversationTurn(fromUser = true, text = "second question")
            )
        )
        val system = out.indexOf("<|im_start|>system")
        val oldest = out.indexOf("test<|im_end|>")
        val reply = out.indexOf("first reply<|im_end|>")
        val newer = out.indexOf("second question<|im_end|>")
        val question = out.indexOf("what can you do?")
        val open = out.lastIndexOf("<|im_start|>assistant")
        assertTrue("system first", system == 0)
        assertTrue("oldest turn before newer", oldest < reply && reply < newer)
        assertTrue("this question comes after the history", newer < question)
        assertTrue("the open assistant turn is last", question < open)
        assertTrue("assistant turn left open for the model", out.endsWith("<think>\n\n</think>\n\n"))
    }

    @Test
    fun the_prefix_before_this_question_is_stable_across_turns() {
        // The native backend keeps whatever leading text two consecutive prompts share, so everything
        // volatile has to come last. Two questions over the same history must agree up to the final
        // user turn — that shared span is what is not re-prefilled.
        val history = arrayOf(
            ConversationTurn(fromUser = true, text = "test"),
            ConversationTurn(fromUser = false, text = "first reply")
        )
        val a = Qwen3ChatFormat.forPrompt(withConversation("question one", *history))
        val b = Qwen3ChatFormat.forPrompt(withConversation("a totally different question", *history))
        val shared = a.commonPrefixWith(b)
        assertTrue(
            "history is inside the shared prefix (was ${shared.length} chars)",
            shared.contains("first reply<|im_end|>")
        )
        assertFalse("the question itself is not", shared.contains("question one"))
    }

    @Test
    fun blank_turns_are_dropped_rather_than_emitted_as_empty_turns() {
        val out = Qwen3ChatFormat.forPrompt(
            withConversation(
                "hi",
                ConversationTurn(fromUser = false, text = "   "),
                ConversationTurn(fromUser = true, text = "real")
            )
        )
        assertFalse(out.contains("<|im_start|>assistant\n<|im_end|>"))
        assertTrue(out.contains("<|im_start|>user\nreal<|im_end|>"))
    }

    @Test
    fun no_conversation_still_produces_system_then_user_then_open_assistant() {
        val out = Qwen3ChatFormat.forPrompt(grounded("what is due?", "Ship the release"))
        val sys = out.indexOf("<|im_start|>system")
        val user = out.indexOf("<|im_start|>user")
        val asst = out.lastIndexOf("<|im_start|>assistant")
        assertTrue(sys == 0 && sys < user && user < asst)
        assertTrue("the grounded context rides in the final user turn", out.contains("Ship the release"))
        assertFalse("the ANSWER: cue is replaced by the open assistant turn", out.contains("ANSWER:"))
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
