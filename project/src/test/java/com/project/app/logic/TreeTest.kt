package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeTest {

    private data class Node(val id: String, val parentId: String?, val order: Int = 0)

    private val order: Comparator<Node> = compareBy({ it.order }, { it.id })

    private fun flatten(nodes: List<Node>) =
        Tree.flatten(nodes, { it.id }, { it.parentId }, order)

    @Test
    fun `flatten walks depth-first and numbers each level`() {
        val nodes = listOf(
            Node("a", null, 0),
            Node("b", null, 1),
            Node("a1", "a", 0),
            Node("a2", "a", 1),
            Node("a1x", "a1", 0)
        )

        val rows = flatten(nodes)

        assertEquals(listOf("a", "a1", "a1x", "a2", "b"), rows.map { it.item.id })
        assertEquals(listOf(0, 1, 2, 1, 0), rows.map { it.depth })
        assertEquals(listOf("1", "1.1", "1.1.1", "1.2", "2"), rows.map { it.number })
        assertEquals(listOf(true, true, false, false, false), rows.map { it.hasChildren })
    }

    @Test
    fun `an orphan is drawn as a root rather than hidden`() {
        val rows = flatten(listOf(Node("root", null, 0), Node("lost", "gone", 1)))

        assertEquals(listOf("root", "lost"), rows.map { it.item.id })
        assertEquals(listOf(0, 0), rows.map { it.depth })
    }

    @Test
    fun `a node claiming itself as its parent is a root, not a cycle`() {
        val rows = flatten(listOf(Node("self", "self")))

        assertEquals(listOf("self"), rows.map { it.item.id })
        assertEquals(0, rows.single().depth)
    }

    @Test
    fun `a cycle terminates instead of spinning`() {
        val rows = flatten(listOf(Node("a", "b"), Node("b", "a")))

        // Neither is reachable from a root — but the walk returns.
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `subtree collects a node and its descendants, once each`() {
        val nodes = listOf(
            Node("a", null),
            Node("a1", "a"),
            Node("a1x", "a1"),
            Node("b", null)
        )

        assertEquals(setOf("a", "a1", "a1x"), Tree.subtree(nodes, { it.id }, { it.parentId }, "a").toSet())
        assertEquals(listOf("b"), Tree.subtree(nodes, { it.id }, { it.parentId }, "b"))
    }

    @Test
    fun `subtree of a cyclic branch still terminates`() {
        val nodes = listOf(Node("a", "b"), Node("b", "a"))

        assertEquals(setOf("a", "b"), Tree.subtree(nodes, { it.id }, { it.parentId }, "a").toSet())
    }

    @Test
    fun `a node cannot be re-parented under itself or its own descendant`() {
        val nodes = listOf(
            Node("a", null),
            Node("a1", "a"),
            Node("a1x", "a1"),
            Node("b", null)
        )
        val id: (Node) -> String = { it.id }
        val parent: (Node) -> String? = { it.parentId }

        assertFalse(Tree.canReparent(nodes, id, parent, "a", "a"))
        assertFalse(Tree.canReparent(nodes, id, parent, "a", "a1"))
        assertFalse(Tree.canReparent(nodes, id, parent, "a", "a1x"))
        assertTrue(Tree.canReparent(nodes, id, parent, "a", "b"))
        assertTrue(Tree.canReparent(nodes, id, parent, "a", null))
        assertTrue(Tree.canReparent(nodes, id, parent, "a1x", "b"))
    }

    @Test
    fun `childMap keys roots under null and sorts every run`() {
        val nodes = listOf(
            Node("b", null, 1),
            Node("a", null, 0),
            Node("a2", "a", 1),
            Node("a1", "a", 0)
        )

        val children = Tree.childMap(nodes, { it.id }, { it.parentId }, order)

        assertEquals(listOf("a", "b"), children[null]!!.map { it.id })
        assertEquals(listOf("a1", "a2"), children["a"]!!.map { it.id })
    }
}
