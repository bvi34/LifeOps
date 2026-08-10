package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallTest {

    private fun mem(
        id: String,
        content: String,
        tags: List<String> = emptyList(),
        salience: Int = 50,
        pinned: Boolean = false,
        updatedAt: Long = 0
    ) = MemoryRecord(id, content, "note", tags, salience, pinned, updatedAt = updatedAt)

    @Test
    fun surfaces_relevant_excludes_unrelated() {
        val memories = listOf(
            mem("a", "prefers oat milk in coffee", tags = listOf("topic:food")),
            mem("b", "allergic to penicillin", tags = listOf("topic:health"))
        )
        val result = MemoryRecall.recall("what coffee do I like", memories)
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun pinned_memory_is_always_included() {
        val memories = listOf(
            mem("pin", "call mom on Sundays", pinned = true),
            mem("other", "prefers window seats")
        )
        val result = MemoryRecall.recall("something totally unrelated xyz", memories)
        assertTrue(result.any { it.id == "pin" })
        assertFalse(result.any { it.id == "other" })
    }

    @Test
    fun focus_tags_boost_ranks_tagged_memory_first() {
        val memories = listOf(
            mem("lexical", "routine of stretching"),
            mem("tagged", "morning walk", tags = listOf("health"))
        )
        val result = MemoryRecall.recall("routine", memories, focusTags = setOf("health"))
        // "tagged" has a +2 tag boost; "lexical" only a +1 word overlap.
        assertEquals("tagged", result.first().id)
    }

    @Test
    fun salience_breaks_ties_among_equally_relevant() {
        val memories = listOf(
            mem("low", "budget planning", salience = 10),
            mem("high", "budget planning", salience = 90)
        )
        val result = MemoryRecall.recall("budget", memories)
        assertEquals("high", result.first().id)
    }

    @Test
    fun respects_limit() {
        val memories = (1..10).map { mem("m$it", "shared keyword note $it") }
        val result = MemoryRecall.recall("keyword", memories, limit = 3)
        assertEquals(3, result.size)
    }

    @Test
    fun empty_store_returns_empty() {
        assertTrue(MemoryRecall.recall("anything", emptyList()).isEmpty())
    }
}
