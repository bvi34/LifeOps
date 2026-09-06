package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Turning a name somebody said into a row.
 *
 * Only the connection routes need this — a screen hands back the id of the thing that was tapped —
 * and it is exactly where a wrong answer is expensive, because the caller on the other end is a
 * sentence relayed by Advisor and nobody is watching which project it lands in.
 */
class ProjectLookupTest {

    private data class Row(val id: String, val name: String)

    private fun resolve(reference: String, vararg rows: Row) =
        ProjectLookup.resolve(reference, rows.toList(), { it.id }, { it.name })

    private val kestrel = Row("p1", "The Kestrel")
    private val app = Row("p2", "The app")

    @Test
    fun `an id wins, because a caller holding one means it`() {
        assertEquals(ProjectLookup.Match.Found(kestrel), resolve("p1", kestrel, app))
    }

    @Test
    fun `a name matches however it was typed or spaced`() {
        assertEquals(ProjectLookup.Match.Found(kestrel), resolve("The Kestrel", kestrel, app))
        assertEquals(ProjectLookup.Match.Found(kestrel), resolve("the kestrel", kestrel, app))
        assertEquals(ProjectLookup.Match.Found(kestrel), resolve("  THE KESTREL  ", kestrel, app))
    }

    @Test
    fun `matching is exact, never a prefix`() {
        // "Kes" finding "The Kestrel" is a guess, and a route that guesses writes into the wrong
        // project the first time two of them start with the same letters.
        // A real prefix of the name, which is the case a startsWith would wave through.
        assertEquals(ProjectLookup.Match.None, resolve("The Kes", kestrel, app))
        assertEquals(ProjectLookup.Match.None, resolve("The", kestrel, app))
        // A word inside it, which is the case a contains would wave through.
        assertEquals(ProjectLookup.Match.None, resolve("Kestrel", kestrel, app))
        assertEquals(ProjectLookup.Match.None, resolve("Kes", kestrel, app))
        // And longer than the name, which is neither.
        assertEquals(ProjectLookup.Match.None, resolve("The Kestrel and more", kestrel, app))
    }

    @Test
    fun `nothing, and nothing of that name, are both nothing`() {
        assertEquals(ProjectLookup.Match.None, resolve(""))
        assertEquals(ProjectLookup.Match.None, resolve("   ", kestrel))
        assertEquals(ProjectLookup.Match.None, resolve("The Peregrine", kestrel, app))
    }

    @Test
    fun `two of the same name resolve to neither, and say what they are`() {
        // The same rule Lore uses for a `[[link]]`: an ambiguous name resolves to nothing rather
        // than to whichever row came first. A caller told "which one?" can ask again; one told
        // nothing writes into somebody else's work and never finds out.
        val draft = Row("p3", "Draft")
        val otherDraft = Row("p4", "draft")

        val match = resolve("Draft", draft, otherDraft, kestrel)

        assertEquals(ProjectLookup.Match.Ambiguous(listOf("Draft", "draft")), match)
    }

    @Test
    fun `an id still wins when the name it carries is ambiguous`() {
        val draft = Row("p3", "Draft")
        val otherDraft = Row("p4", "Draft")

        assertEquals(ProjectLookup.Match.Found(draft), resolve("p3", draft, otherDraft))
    }

    // ------------------------------------------------------------------ the default column

    private data class Column(val name: String, val done: Boolean)

    @Test
    fun `a card with no column named starts where work starts`() {
        val columns = listOf(Column("Backlog", false), Column("Doing", false), Column("Shipped", true))

        assertEquals(Column("Backlog", false), ProjectLookup.defaultColumn(columns) { it.done })
    }

    @Test
    fun `a board of nothing but finished columns gets no default`() {
        // Falling back to the finished column would file brand-new work as already done.
        assertNull(ProjectLookup.defaultColumn(listOf(Column("Done", true))) { it.done })
        assertNull(ProjectLookup.defaultColumn(emptyList<Column>()) { it.done })
    }
}
