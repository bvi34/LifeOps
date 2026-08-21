package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationsTest {

    @Test
    fun finds_every_cited_ref() {
        assertEquals(setOf(1, 3), Citations.refs("Dune [1] is on your shelf, and so is Emma [3]."))
    }

    @Test
    fun repeated_and_adjacent_refs_collapse() {
        assertEquals(setOf(1, 2), Citations.refs("Both [1][2] agree, and [1] again."))
    }

    @Test
    fun ignores_brackets_that_are_not_citations() {
        assertTrue(Citations.refs("Published [2026], remembered [M1], see [link](x).").isEmpty())
        assertTrue(Citations.refs("Nothing cited here at all.").isEmpty())
    }

    @Test
    fun maps_refs_back_to_document_ids() {
        val blocks = listOf(
            ContextBlock(1, doc("lifeops:task:1"), "Water the plants"),
            ContextBlock(2, doc("citation:book:7"), "Dune"),
            ContextBlock(3, doc("logistics:item:4"), "Oat milk")
        )
        assertEquals(
            setOf("lifeops:task:1", "logistics:item:4"),
            Citations.citedIds("You have [1] due, and [3] is low.", blocks)
        )
    }

    @Test
    fun an_uncited_answer_maps_to_nothing() {
        val blocks = listOf(ContextBlock(1, doc("lifeops:task:1"), "Water the plants"))
        assertTrue(Citations.citedIds("I couldn't find anything for that.", blocks).isEmpty())
    }

    /** A ref the model invented has no block, so it maps to no document rather than to the wrong one. */
    @Test
    fun invented_refs_map_to_nothing() {
        val blocks = listOf(ContextBlock(1, doc("lifeops:task:1"), "Water the plants"))
        assertTrue(Citations.citedIds("As shown in [9].", blocks).isEmpty())
    }

    private fun doc(id: String) = KnowledgeDocument(
        id = id,
        source = SourceApp.LIFEOPS,
        kind = "task",
        title = "t",
        body = "b",
        timestamp = 0L
    )
}
