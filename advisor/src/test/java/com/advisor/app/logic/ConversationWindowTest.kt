package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWindowTest {

    private fun turns(n: Int, chars: Int = 20): List<ConversationTurn> =
        (0 until n).map { ConversationTurn(fromUser = it % 2 == 0, text = "t$it".padEnd(chars, 'x')) }

    @Test
    fun a_short_conversation_is_kept_whole() {
        assertEquals(0, ConversationWindow.startIndex(0))
        assertEquals(0, ConversationWindow.startIndex(8))
        assertEquals(0, ConversationWindow.startIndex(ConversationWindow.MAX_MESSAGES))
    }

    /**
     * The property the whole thing exists for: the start holds still while the conversation grows
     * into it, so consecutive prompts share their history and the KV cache keeps it.
     */
    @Test
    fun the_start_holds_still_across_several_turns_then_jumps() {
        val starts = (16..28 step 2).map { ConversationWindow.startIndex(it) }
        assertEquals(listOf(0, 4, 4, 8, 8, 12, 12), starts)

        // Put another way: over seven turns the window moved three times, not seven.
        assertEquals(3, starts.zipWithNext().count { (a, b) -> a != b })
    }

    @Test
    fun the_start_is_always_stride_aligned_so_a_window_never_opens_mid_exchange() {
        for (total in 0..200 step 2) {
            assertEquals(0, ConversationWindow.startIndex(total) % ConversationWindow.STRIDE)
        }
    }

    @Test
    fun the_window_never_runs_past_the_conversation() {
        for (total in 0..200 step 2) {
            assertTrue(ConversationWindow.startIndex(total) <= total)
        }
    }

    @Test
    fun a_budget_that_fits_changes_nothing() {
        val all = turns(8)
        assertEquals(all, ConversationWindow.fit(all, budgetChars = 10_000))
    }

    @Test
    fun a_tight_budget_drops_whole_strides_off_the_front() {
        val all = turns(8, chars = 100)
        val fitted = ConversationWindow.fit(all, budgetChars = 600)
        // Kept the most recent turns, and dropped a multiple of STRIDE of them.
        assertEquals(all.takeLast(fitted.size), fitted)
        assertEquals(0, (all.size - fitted.size) % ConversationWindow.STRIDE)
        assertTrue(fitted.size < all.size)
    }

    @Test
    fun no_budget_means_no_history_rather_than_an_overrun() {
        assertTrue(ConversationWindow.fit(turns(8), budgetChars = 0).isEmpty())
        assertTrue(ConversationWindow.fit(turns(8), budgetChars = -100).isEmpty())
        // A single turn too large for the budget is dropped, not truncated into nonsense.
        assertTrue(ConversationWindow.fit(turns(2, chars = 5_000), budgetChars = 100).isEmpty())
    }

    @Test
    fun an_empty_conversation_stays_empty() {
        assertTrue(ConversationWindow.fit(emptyList(), budgetChars = 10_000).isEmpty())
    }

    /**
     * Trimming for the budget must not undo the anchoring: it also moves in strides, so a window that
     * had to shrink still starts where the next turn's will.
     */
    @Test
    fun budget_trimming_is_stable_while_the_budget_is() {
        val budget = 700
        val first = ConversationWindow.fit(turns(12, chars = 100), budget)
        val second = ConversationWindow.fit(turns(14, chars = 100), budget)
        // The older turns that survive both are the same ones, in the same order.
        val shared = first.filter { it in second }
        assertEquals(shared, second.take(shared.size))
    }
}
