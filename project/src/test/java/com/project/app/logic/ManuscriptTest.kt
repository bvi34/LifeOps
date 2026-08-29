package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManuscriptTest {

    private fun node(
        id: String,
        parent: String? = null,
        title: String = id,
        status: OutlineStatus = OutlineStatus.DRAFTED,
        synopsis: String? = null,
        order: Int = 0
    ) = OutlineNode(id, parent, title, synopsis, status, 0, 0, order)

    private fun doc(id: String, nodeId: String?, title: String = id, text: String = "", order: Int = 0) =
        ManuscriptDoc(id, nodeId, title, order, listOf(DocBlock("$id-b", BlockType.PARAGRAPH, text)))

    private val outline = listOf(
        node("act1", title = "Act One", order = 0),
        node("s1", parent = "act1", title = "Opening", order = 0),
        node("s2", parent = "act1", title = "The letter", order = 1),
        node("act2", title = "Act Two", order = 1),
        node("s3", parent = "act2", title = "Reversal", order = 0)
    )

    private fun compile(
        docs: List<ManuscriptDoc>,
        options: CompileOptions = CompileOptions(),
        nodes: List<OutlineNode> = outline
    ) = Manuscript.compile("The Book", Outline.flatten(nodes), docs, options)

    @Test
    fun `pieces come out in outline order, with their documents' text`() {
        val m = compile(
            listOf(
                doc("d3", "s3", text = "third"),
                doc("d1", "s1", text = "first"),
                doc("d2", "s2", text = "second")
            )
        )

        assertEquals(listOf("act1", "s1", "s2", "act2", "s3"), m.pieces.map { it.nodeId })
        assertEquals("first", m.pieces.first { it.nodeId == "s1" }.body)
        assertEquals("third", m.pieces.first { it.nodeId == "s3" }.body)
    }

    @Test
    fun `a piece with nothing written is reported as a gap, never silently dropped`() {
        val m = compile(listOf(doc("d1", "s1", text = "first")))

        assertEquals(5, m.pieces.size)
        // s2 and s3 are unwritten leaves; the acts are not gaps — they are not supposed to hold text.
        assertEquals(listOf("s2", "s3"), m.gaps.map { it.nodeId })
        assertTrue(m.pieces.first { it.nodeId == "s2" }.isEmpty)
    }

    @Test
    fun `a grouping node with children is never a gap`() {
        val m = compile(listOf(doc("d1", "s1"), doc("d2", "s2"), doc("d3", "s3")))

        assertTrue(m.gaps.isEmpty())
        assertFalse(m.pieces.first { it.nodeId == "act1" }.isEmpty.let { it && m.gaps.any { g -> g.nodeId == "act1" } })
    }

    @Test
    fun `cut material is left out by default and counted`() {
        val nodes = outline.map { if (it.id == "s2") it.copy(status = OutlineStatus.CUT) else it }
        val docs = listOf(doc("d1", "s1", text = "first"), doc("d2", "s2", text = "cut text"))

        val without = compile(docs, nodes = nodes)
        assertFalse(without.pieces.any { it.nodeId == "s2" })
        assertEquals(1, without.cutOmitted)
        assertFalse(without.render().contains("cut text"))

        val with = compile(docs, CompileOptions(includeCut = true), nodes)
        assertTrue(with.pieces.any { it.nodeId == "s2" })
        assertEquals(0, with.cutOmitted)
        assertTrue(with.render().contains("cut text"))
    }

    @Test
    fun `documents belonging to no piece are counted even when they are not included`() {
        val m = compile(listOf(doc("d1", "s1", text = "first"), doc("loose", null, text = "stray words here")))

        assertEquals(listOf("loose"), m.unplaced.map { it.id })
        // Not in the body, and not in the word total, but impossible to miss in the summary.
        assertFalse(m.render().contains("stray words here"))
        assertTrue(m.summary.contains("1 unplaced document"))
    }

    @Test
    fun `unplaced documents can be appended when asked`() {
        val options = CompileOptions(includeUnplaced = true)
        val m = compile(listOf(doc("loose", null, title = "Notes", text = "stray words here")), options)

        val rendered = m.render()
        assertTrue(rendered.contains("## Unplaced"))
        assertTrue(rendered.contains("### Notes"))
        assertTrue(rendered.contains("stray words here"))
        assertEquals(3, m.words)
    }

    @Test
    fun `two documents under one piece are separated rather than run together`() {
        val m = compile(
            listOf(
                doc("d1", "s1", text = "first half", order = 0),
                doc("d2", "s1", text = "second half", order = 1)
            )
        )

        val body = m.pieces.first { it.nodeId == "s1" }.body
        assertEquals("first half\n\n* * *\n\nsecond half", body)
    }

    @Test
    fun `heading level follows outline depth and stops at six`() {
        val deep = listOf(
            node("l0", title = "L0"),
            node("l1", parent = "l0", title = "L1"),
            node("l2", parent = "l1", title = "L2"),
            node("l3", parent = "l2", title = "L3"),
            node("l4", parent = "l3", title = "L4"),
            node("l5", parent = "l4", title = "L5"),
            node("l6", parent = "l5", title = "L6")
        )
        val rendered = compile(emptyList(), nodes = deep).render()

        assertTrue(rendered.contains("## L0"))
        assertTrue(rendered.contains("### L1"))
        assertTrue(rendered.contains("###### L4"))
        // Deeper than Markdown allows: flattened onto six rather than emitting a broken heading.
        assertTrue(rendered.contains("###### L5"))
        assertTrue(rendered.contains("###### L6"))
    }

    @Test
    fun `headings and synopses are optional`() {
        val nodes = outline.map { if (it.id == "s1") it.copy(synopsis = "she leaves") else it }
        val docs = listOf(doc("d1", "s1", text = "first"))

        val plain = CompileOptions(includeHeadings = false)
        val rendered = compile(docs, plain, nodes).render()
        assertFalse(rendered.contains("## Act One"))
        assertTrue(rendered.contains("first"))

        val annotated = CompileOptions(includeSynopses = true)
        val withSynopsis = compile(docs, annotated, nodes).render()
        assertTrue(withSynopsis.contains("> she leaves"))
    }

    @Test
    fun `word count is the words of what was actually included`() {
        val m = compile(
            listOf(
                doc("d1", "s1", text = "one two three"),
                doc("d2", "s2", text = "four five"),
                doc("loose", null, text = "not counted at all")
            )
        )

        assertEquals(5, m.words)
    }

    @Test
    fun `an empty project compiles to just its title`() {
        val m = Manuscript.compile("The Book", emptyList(), emptyList())

        assertEquals("# The Book\n", m.render())
        assertTrue(m.pieces.isEmpty())
        assertTrue(m.gaps.isEmpty())
        assertEquals(0, m.words)
    }

    @Test
    fun `the summary says what is in it and what is missing`() {
        val m = compile(listOf(doc("d1", "s1", text = "one two three")))

        val summary = m.summary
        assertTrue(summary.contains("3 words"))
        assertTrue(summary.contains("5 pieces"))
        assertTrue(summary.contains("2 with nothing written"))
    }
}
