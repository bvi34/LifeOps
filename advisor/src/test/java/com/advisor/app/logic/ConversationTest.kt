package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {

    @Test
    fun retrieval_query_is_just_the_question_with_no_history() {
        assertEquals("who wrote Dune", Conversation.retrievalQuery(emptyList(), "who wrote Dune"))
    }

    @Test
    fun retrieval_query_folds_in_the_recent_turns() {
        val history = listOf(
            ConversationTurn(fromUser = true, text = "tell me about Dune"),
            ConversationTurn(fromUser = false, text = "Dune is a novel by Frank Herbert")
        )
        val query = Conversation.retrievalQuery(history, "who wrote it")
        // The follow-up's subject ("Dune") is carried in from the prior turns.
        assertTrue(query.contains("Dune"))
        assertTrue(query.contains("who wrote it"))
    }

    @Test
    fun retrieval_query_uses_only_the_last_turns() {
        val history = (1..6).map { ConversationTurn(fromUser = true, text = "turn$it") }
        val query = Conversation.retrievalQuery(history, "and now")
        // Only the last RETRIEVAL_CONTEXT_TURNS turns are folded in; older ones are dropped.
        assertFalse(query.contains("turn1"))
        assertTrue(query.contains("turn5"))
        assertTrue(query.contains("turn6"))
        assertTrue(query.contains("and now"))
    }

    @Test
    fun has_context_reflects_whether_the_chat_is_underway() {
        assertFalse(Conversation.hasContext(emptyList()))
        assertFalse(Conversation.hasContext(listOf(ConversationTurn(true, "   "))))
        assertTrue(Conversation.hasContext(listOf(ConversationTurn(true, "hello"))))
    }
}
