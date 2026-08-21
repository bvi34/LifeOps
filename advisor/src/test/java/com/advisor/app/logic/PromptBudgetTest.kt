package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetTest {

    @Test
    fun the_token_estimate_runs_high_rather_than_low() {
        // Deliberately pessimistic: this exists to keep a prompt under a hard limit, so over-counting
        // costs a little history and under-counting costs the system instruction.
        val text = "a".repeat(300)
        assertEquals(100, PromptBudget.estimate(text))
        assertTrue(PromptBudget.estimate(text) >= text.length / 4)
    }

    @Test
    fun an_empty_prompt_costs_nothing() {
        assertEquals(0, PromptBudget.estimate(""))
    }

    @Test
    fun history_gets_what_is_left_after_the_prompt_and_the_reply() {
        val base = "x".repeat(300)  // 100 tokens
        val chars = PromptBudget.historyChars(base, contextTokens = 2048, replyTokens = 512)
        val expectedTokens = 2048 - 512 - PromptBudget.SAFETY_TOKENS - 100
        assertEquals(expectedTokens * PromptBudget.CHARS_PER_TOKEN, chars)
    }

    @Test
    fun a_bigger_context_window_buys_history() {
        val base = "x".repeat(300)
        val small = PromptBudget.historyChars(base, contextTokens = 2048, replyTokens = 512)
        val large = PromptBudget.historyChars(base, contextTokens = 3072, replyTokens = 512)
        assertEquals(1024 * PromptBudget.CHARS_PER_TOKEN, large - small)
    }

    @Test
    fun a_prompt_that_already_fills_the_window_leaves_no_history_rather_than_going_negative() {
        val huge = "x".repeat(100_000)
        assertEquals(0, PromptBudget.historyChars(huge, contextTokens = 2048, replyTokens = 512))
    }

    /**
     * The realistic shape: the standing system instruction alone is a sizeable fixed cost, so what is
     * left for history is worth knowing rather than assuming.
     */
    @Test
    fun the_default_system_prompt_still_leaves_room_for_history() {
        val prompt = PromptAssembler.assemble("what's due today?", emptyList())
        val chars = PromptBudget.historyChars(prompt.render(includeConversation = false), contextTokens = 3072)
        assertTrue("only $chars chars left for history", chars > 2000)
    }
}
