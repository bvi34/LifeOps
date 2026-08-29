package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineTest {

    private fun node(
        id: String,
        parent: String? = null,
        title: String = id,
        status: OutlineStatus = OutlineStatus.IDEA,
        target: Int = 0,
        actual: Int = 0,
        order: Int = 0
    ) = OutlineNode(id, parent, title, null, status, target, actual, order)

    @Test
    fun `flatten walks depth-first and numbers the rows`() {
        val nodes = listOf(
            node("act1", title = "Act I", order = 0),
            node("act2", title = "Act II", order = 1),
            node("s1", parent = "act1", title = "Opening", order = 0),
            node("s2", parent = "act1", title = "The letter", order = 1),
            node("s3", parent = "act2", title = "Reversal", order = 0)
        )

        val rows = Outline.flatten(nodes)

        assertEquals(listOf("act1", "s1", "s2", "act2", "s3"), rows.map { it.node.id })
        assertEquals(listOf(0, 1, 1, 0, 1), rows.map { it.depth })
        assertEquals(listOf("1", "1.1", "1.2", "2", "2.1"), rows.map { it.number })
        assertTrue(rows.first { it.node.id == "act1" }.hasChildren)
        assertTrue(!rows.first { it.node.id == "s1" }.hasChildren)
    }

    @Test
    fun `an orphan is shown as a root rather than dropped`() {
        val nodes = listOf(
            node("root", order = 0),
            node("lost", parent = "deleted-parent", order = 1)
        )

        val rows = Outline.flatten(nodes)

        assertEquals(listOf("root", "lost"), rows.map { it.node.id })
        assertEquals(listOf(0, 0), rows.map { it.depth })
    }

    @Test
    fun `a cycle cannot hang the walk`() {
        val nodes = listOf(
            node("a", parent = "b"),
            node("b", parent = "a")
        )

        // Neither is a root, so nothing is reachable — but the walk must return, not spin.
        assertEquals(emptyList<String>(), Outline.flatten(nodes).map { it.node.id })
        assertEquals(OutlineTotals.EMPTY, Outline.projectTotals(nodes))
    }

    @Test
    fun `rollups count leaf words only, so a parent cannot double-count its children`() {
        val nodes = listOf(
            node("act1", target = 20_000),
            // A stale word count on the grouping node must not reach the total.
            node("ch1", parent = "act1", actual = 999, target = 0, order = 0),
            node("s1", parent = "ch1", actual = 1_200, target = 2_000, order = 0, status = OutlineStatus.DONE),
            node("s2", parent = "ch1", actual = 800, target = 2_000, order = 1)
        )

        val totals = Outline.projectTotals(nodes)

        assertEquals(2_000, totals.actualWords)
        assertEquals(24_000, totals.targetWords)
        assertEquals(2, totals.pieces)
        assertEquals(1, totals.piecesComplete)
    }

    @Test
    fun `cut material keeps its place and stops counting`() {
        val nodes = listOf(
            node("s1", actual = 1_000, target = 1_000, order = 0),
            node("s2", actual = 4_000, target = 4_000, order = 1, status = OutlineStatus.CUT)
        )

        val totals = Outline.projectTotals(nodes)

        assertEquals(1_000, totals.actualWords)
        assertEquals(1_000, totals.targetWords)
        assertEquals(1, totals.pieces)
        assertEquals(1, totals.cut)
        // Still drawn — cut is not deleted.
        assertEquals(2, Outline.flatten(nodes).size)
    }

    @Test
    fun `progress falls back to finished pieces when nothing has a word target`() {
        val noTarget = OutlineTotals(targetWords = 0, actualWords = 0, pieces = 4, piecesComplete = 1, cut = 0)
        assertEquals(0.25f, noTarget.progress!!, 0.0001f)

        val withTarget = OutlineTotals(targetWords = 1_000, actualWords = 250, pieces = 4, piecesComplete = 4, cut = 0)
        assertEquals(0.25f, withTarget.progress!!, 0.0001f)

        assertNull(OutlineTotals.EMPTY.progress)
    }

    @Test
    fun `progress never exceeds one, and overshooting is not a debt`() {
        val over = OutlineTotals(targetWords = 1_000, actualWords = 1_800, pieces = 1, piecesComplete = 1, cut = 0)
        assertEquals(1f, over.progress!!, 0.0001f)
        assertEquals(0, over.wordsRemaining)
    }

    @Test
    fun `move renumbers the whole sibling run rather than swapping two numbers`() {
        // Restored rows can share a sort order; a swap of equal numbers would move nothing.
        val nodes = listOf(
            node("a", title = "A", order = 0),
            node("b", title = "B", order = 0),
            node("c", title = "C", order = 0)
        )

        val changed = Outline.move(nodes, "c", -1)
        val applied = nodes.map { original -> changed.firstOrNull { it.id == original.id } ?: original }

        assertEquals(listOf("a", "c", "b"), Outline.flatten(applied).map { it.node.id })
    }

    @Test
    fun `an impossible move changes nothing`() {
        val nodes = listOf(node("a", order = 0), node("b", order = 1))
        assertEquals(emptyList<OutlineNode>(), Outline.move(nodes, "a", -1))
        assertEquals(emptyList<OutlineNode>(), Outline.move(nodes, "b", 1))
        assertEquals(emptyList<OutlineNode>(), Outline.move(nodes, "nobody", 1))
    }

    @Test
    fun `indent nests under the sibling above, and the first row cannot indent`() {
        val nodes = listOf(
            node("a", order = 0),
            node("b", order = 1)
        )

        assertEquals(emptyList<OutlineNode>(), Outline.indent(nodes, "a"))

        val changed = Outline.indent(nodes, "b")
        assertEquals(1, changed.size)
        assertEquals("a", changed.single().parentId)

        val applied = nodes.map { original -> changed.firstOrNull { it.id == original.id } ?: original }
        val rows = Outline.flatten(applied)
        assertEquals(listOf(0, 1), rows.map { it.depth })
        assertEquals(listOf("1", "1.1"), rows.map { it.number })
    }

    @Test
    fun `outdent lands directly after the old parent and shifts what followed it`() {
        val nodes = listOf(
            node("p1", title = "P1", order = 0),
            node("p2", title = "P2", order = 1),
            node("kid", parent = "p1", title = "Kid", order = 0)
        )

        val changed = Outline.outdent(nodes, "kid")
        val applied = nodes.map { original -> changed.firstOrNull { it.id == original.id } ?: original }
        val rows = Outline.flatten(applied)

        assertEquals(listOf("p1", "kid", "p2"), rows.map { it.node.id })
        assertEquals(listOf(0, 0, 0), rows.map { it.depth })
    }

    @Test
    fun `a root cannot outdent`() {
        assertEquals(emptyList<OutlineNode>(), Outline.outdent(listOf(node("a")), "a"))
    }

    @Test
    fun `subtree is what a delete takes with it`() {
        val nodes = listOf(
            node("act"),
            node("ch", parent = "act"),
            node("s1", parent = "ch"),
            node("s2", parent = "ch"),
            node("elsewhere")
        )

        assertEquals(setOf("act", "ch", "s1", "s2"), Outline.subtree(nodes, "act").toSet())
        assertEquals(listOf("s1"), Outline.subtree(nodes, "s1"))
    }

    @Test
    fun `nextSortOrder appends under the right parent`() {
        val nodes = listOf(
            node("a", order = 0),
            node("kid", parent = "a", order = 4)
        )
        assertEquals(1, Outline.nextSortOrder(nodes, null))
        assertEquals(5, Outline.nextSortOrder(nodes, "a"))
        assertEquals(0, Outline.nextSortOrder(nodes, "somewhere-empty"))
    }
}
