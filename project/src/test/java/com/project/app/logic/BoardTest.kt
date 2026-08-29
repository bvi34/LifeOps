package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTest {

    private val columns = listOf(
        BoardColumn("todo", "To do", 0, wipLimit = null, isDone = false),
        BoardColumn("doing", "Doing", 1, wipLimit = 2, isDone = false),
        BoardColumn("done", "Done", 2, wipLimit = 1, isDone = true)
    )

    private fun card(id: String, column: String, order: Int, title: String = id, doneAt: Long? = null) =
        BoardCard(id, column, title, null, order, doneAt = doneAt)

    private fun apply(cards: List<BoardCard>, changed: List<BoardCard>) =
        cards.map { original -> changed.firstOrNull { it.id == original.id } ?: original }

    @Test
    fun `lanes come out in column order with their cards in card order`() {
        val cards = listOf(
            card("c", "doing", 1),
            card("a", "todo", 0),
            card("b", "doing", 0)
        )

        val lanes = Board.lanes(columns, cards)

        assertEquals(listOf("To do", "Doing", "Done"), lanes.map { it.column.name })
        assertEquals(listOf("a"), lanes[0].cards.map { it.id })
        assertEquals(listOf("b", "c"), lanes[1].cards.map { it.id })
        assertTrue(lanes[2].cards.isEmpty())
    }

    @Test
    fun `a limit warns and never refuses, and the done column is never over`() {
        val cards = listOf(
            card("a", "doing", 0),
            card("b", "doing", 1),
            card("c", "doing", 2),
            card("d", "done", 0),
            card("e", "done", 1)
        )

        val lanes = Board.lanes(columns, cards)

        assertTrue(lanes[1].isOverLimit)
        assertFalse(lanes[1].isAtLimit)
        // Two cards in a done column limited to one: finishing things is not a problem.
        assertFalse(lanes[2].isOverLimit)

        val stats = Board.stats(lanes)
        assertEquals(5, stats.total)
        assertEquals(2, stats.done)
        assertEquals(3, stats.inFlight)
        assertEquals(listOf("Doing"), stats.overLimitColumns)
    }

    @Test
    fun `at the limit is not over it`() {
        val cards = listOf(card("a", "doing", 0), card("b", "doing", 1))
        val lane = Board.lanes(columns, cards)[1]

        assertTrue(lane.isAtLimit)
        assertFalse(lane.isOverLimit)
    }

    @Test
    fun `moving between columns closes the gap behind and opens one ahead`() {
        val cards = listOf(
            card("a", "todo", 0),
            card("b", "todo", 1),
            card("c", "todo", 2),
            card("x", "doing", 0),
            card("y", "doing", 1)
        )

        val changed = Board.move(columns, cards, "b", "doing", toIndex = 1)
        val lanes = Board.lanes(columns, apply(cards, changed))

        assertEquals(listOf("a", "c"), lanes[0].cards.map { it.id })
        assertEquals(listOf(0, 1), lanes[0].cards.map { it.sortOrder })
        assertEquals(listOf("x", "b", "y"), lanes[1].cards.map { it.id })
        assertEquals(listOf(0, 1, 2), lanes[1].cards.map { it.sortOrder })
    }

    @Test
    fun `a drop past the end is an append rather than an error`() {
        val cards = listOf(card("a", "todo", 0), card("x", "doing", 0))

        val lanes = Board.lanes(columns, apply(cards, Board.move(columns, cards, "a", "doing", toIndex = 99)))

        assertEquals(listOf("x", "a"), lanes[1].cards.map { it.id })
    }

    @Test
    fun `reordering within a column is the same operation`() {
        val cards = listOf(
            card("a", "todo", 0),
            card("b", "todo", 1),
            card("c", "todo", 2)
        )

        val lanes = Board.lanes(columns, apply(cards, Board.move(columns, cards, "c", "todo", toIndex = 0)))

        assertEquals(listOf("c", "a", "b"), lanes[0].cards.map { it.id })
        assertEquals(listOf(0, 1, 2), lanes[0].cards.map { it.sortOrder })
    }

    @Test
    fun `landing in the done column stamps when, and it survives a later reorder`() {
        val cards = listOf(card("a", "doing", 0))

        val finished = apply(cards, Board.move(columns, cards, "a", "done", toIndex = 0, now = 1_700L))
        assertEquals(1_700L, finished.single().doneAt)

        val reordered = apply(finished, Board.move(columns, finished, "a", "done", toIndex = 0, now = 9_999L))
        assertEquals(1_700L, reordered.single().doneAt)
    }

    @Test
    fun `pulling a card back out of done clears when it was finished`() {
        val cards = listOf(card("a", "done", 0, doneAt = 1_700L))

        val moved = apply(cards, Board.move(columns, cards, "a", "doing", toIndex = 0, now = 9_999L))

        assertNull(moved.single().doneAt)
    }

    @Test
    fun `an unknown card or column moves nothing`() {
        val cards = listOf(card("a", "todo", 0))

        assertEquals(emptyList<BoardCard>(), Board.move(columns, cards, "ghost", "doing", 0))
        assertEquals(emptyList<BoardCard>(), Board.move(columns, cards, "a", "no-such-column", 0))
    }

    @Test
    fun `cards left behind by a deleted column are findable`() {
        val cards = listOf(card("a", "todo", 0), card("stranded", "deleted-column", 0))

        assertEquals(listOf("stranded"), Board.orphans(columns, cards).map { it.id })
        // And they are not drawn in any lane, which is why they have to be findable.
        assertEquals(1, Board.lanes(columns, cards).sumOf { it.count })
    }

    @Test
    fun `nextSortOrder appends within its own column`() {
        val cards = listOf(card("a", "todo", 0), card("b", "doing", 7))

        assertEquals(1, Board.nextSortOrder(cards, "todo"))
        assertEquals(8, Board.nextSortOrder(cards, "doing"))
        assertEquals(0, Board.nextSortOrder(cards, "done"))
    }

    @Test
    fun `a new project's columns speak its own vocabulary and end in a done column`() {
        ProjectKind.entries.forEach { kind ->
            val defaults = Board.defaultColumns(kind)
            assertTrue("$kind has columns", defaults.isNotEmpty())
            assertEquals("$kind ends done", 1, defaults.count { it.second })
            assertTrue("$kind's last column is the done one", defaults.last().second)
        }
        assertEquals("Drafting", Board.defaultColumns(ProjectKind.WRITING)[1].first)
        assertEquals("Backlog", Board.defaultColumns(ProjectKind.SOFTWARE)[0].first)
    }
}
