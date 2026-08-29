package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSearchTest {

    private val outline = listOf(
        OutlineNode("n1", null, "The harbour scene", "She leaves before the tide turns", OutlineStatus.IDEA, 0, 0, 0)
    )
    private val docs = listOf(
        SearchDoc("d1", "Chapter One", "She left the harbour before the tide turned, and did not look back."),
        SearchDoc("d2", "Notes", "Nothing relevant in here.")
    )
    private val lore = listOf(
        LoreEntry("l1", "Kestrel", LoreCategory.CHARACTER, "A smuggler", "Keeps her boat at the harbour.", listOf("Kes"))
    )
    private val events = listOf(
        TimelineEvent("e1", "The storm", "It caught her off the harbour", "Before", "Day 3", 0)
    )
    private val cards = listOf(
        BoardCard("c1", "todo", "Rewrite the harbour scene", "tighten the tide imagery", 0)
    )

    private fun search(query: String) = ProjectSearch.search(query, outline, docs, lore, events, cards)

    @Test
    fun `a query reaches every section`() {
        val sections = search("harbour").map { it.section }.toSet()

        assertEquals(
            setOf(
                SearchSection.OUTLINE,
                SearchSection.DOCS,
                SearchSection.LORE,
                SearchSection.TIMELINE,
                SearchSection.BOARD
            ),
            sections
        )
    }

    @Test
    fun `title matches rank above body matches`() {
        val hits = search("harbour")

        // "The harbour scene" and "Rewrite the harbour scene" name it; the rest merely mention it.
        assertTrue(hits.take(2).all { it.titleMatch })
        assertTrue(hits.drop(2).none { it.titleMatch })
    }

    @Test
    fun `a title match carries no snippet, because the title is already shown`() {
        val hit = search("harbour").first { it.section == SearchSection.OUTLINE }

        assertTrue(hit.titleMatch)
        assertNull(hit.snippet)
    }

    @Test
    fun `a body match carries the matching text in context`() {
        val hit = search("did not look back").first { it.section == SearchSection.DOCS }

        assertTrue(hit.snippet!!.contains("did not look back"))
    }

    @Test
    fun `all terms must appear, in any order`() {
        assertTrue(search("tide harbour").isNotEmpty())
        assertTrue(search("harbour tide").isNotEmpty())
        assertTrue(search("harbour submarine").isEmpty())
    }

    @Test
    fun `terms may straddle the title and the body`() {
        // "The harbour scene" / "She leaves before the tide turns" — one word each side. Matching
        // the halves separately would find nothing while the record plainly contains both.
        val hit = search("harbour tide").single { it.section == SearchSection.OUTLINE }

        assertEquals("n1", hit.id)
        assertFalse(hit.titleMatch)
        assertTrue(hit.snippet!!.contains("tide"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(search("KESTREL").map { it.id }, search("kestrel").map { it.id })
    }

    @Test
    fun `an alias finds its entry`() {
        val hit = search("kes").firstOrNull { it.section == SearchSection.LORE }

        assertEquals("l1", hit?.id)
        assertTrue(hit!!.titleMatch)
    }

    @Test
    fun `an empty query returns nothing rather than everything`() {
        assertTrue(search("").isEmpty())
        assertTrue(search("   ").isEmpty())
        assertTrue(ProjectSearch.terms("  ").isEmpty())
    }

    @Test
    fun `a doc hit knows which document to open`() {
        val hit = search("Chapter One").single { it.section == SearchSection.DOCS }

        assertEquals("d1", hit.id)
        assertEquals("d1", hit.parentId)
    }

    @Test
    fun `a timeline event is found by its when-label and its era`() {
        assertTrue(search("Day 3").any { it.section == SearchSection.TIMELINE })
        assertTrue(search("Before").any { it.section == SearchSection.TIMELINE })
    }

    @Test
    fun `snippets collapse whitespace and mark where they were cut`() {
        val long = "start " + "filler ".repeat(40) + "needle " + "filler ".repeat(40) + "end"

        val snippet = ProjectSearch.snippet(long, listOf("needle"), radius = 20)!!

        assertTrue(snippet.contains("needle"))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(!snippet.contains("\n"))
    }

    @Test
    fun `a snippet for a term that is not there is nothing`() {
        assertNull(ProjectSearch.snippet("some text", listOf("absent")))
        assertNull(ProjectSearch.snippet("   ", listOf("a")))
    }

    @Test
    fun `results are grouped in section order, empty sections dropped`() {
        val grouped = ProjectSearch.grouped(search("Kestrel"))

        assertEquals(listOf(SearchSection.LORE), grouped.map { it.first })
    }

    @Test
    fun `the same query always returns the same order`() {
        assertEquals(search("harbour").map { it.id }, search("harbour").map { it.id })
    }
}
