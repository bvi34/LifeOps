package com.operations.connectkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a name somebody said into a row.
 *
 * Only routes need this — a screen hands back the id of the thing that was tapped — and it is
 * exactly where a wrong answer is expensive, because the caller on the other end is a sentence
 * relayed by Advisor and nobody is watching which row it lands on.
 *
 * The rows below are Project's, because that is the app the rule was written for; every assertion
 * holds for a document or an asset, which is why the rule is in the kit and not in Project.
 */
class NameLookupTest {

    private data class Row(val id: String, val name: String)

    private fun resolve(reference: String, vararg rows: Row) =
        NameLookup.resolve(reference, rows.toList(), { it.id }, { it.name })

    private val kestrel = Row("p1", "The Kestrel")
    private val app = Row("p2", "The app")

    @Test
    fun `an id wins, because a caller holding one means it`() {
        assertEquals(NameLookup.Match.Found(kestrel), resolve("p1", kestrel, app))
    }

    @Test
    fun `a name matches however it was typed or spaced`() {
        assertEquals(NameLookup.Match.Found(kestrel), resolve("The Kestrel", kestrel, app))
        assertEquals(NameLookup.Match.Found(kestrel), resolve("the kestrel", kestrel, app))
        assertEquals(NameLookup.Match.Found(kestrel), resolve("  THE KESTREL  ", kestrel, app))
    }

    @Test
    fun `matching is exact, never a prefix`() {
        // "Kes" finding "The Kestrel" is a guess, and a route that guesses writes into the wrong
        // project the first time two of them start with the same letters.
        // A real prefix of the name, which is the case a startsWith would wave through.
        assertEquals(NameLookup.Match.None, resolve("The Kes", kestrel, app))
        assertEquals(NameLookup.Match.None, resolve("The", kestrel, app))
        // A word inside it, which is the case a contains would wave through.
        assertEquals(NameLookup.Match.None, resolve("Kestrel", kestrel, app))
        assertEquals(NameLookup.Match.None, resolve("Kes", kestrel, app))
        // And longer than the name, which is neither.
        assertEquals(NameLookup.Match.None, resolve("The Kestrel and more", kestrel, app))
    }

    @Test
    fun `nothing, and nothing of that name, are both nothing`() {
        assertEquals(NameLookup.Match.None, resolve(""))
        assertEquals(NameLookup.Match.None, resolve("   ", kestrel))
        assertEquals(NameLookup.Match.None, resolve("The Peregrine", kestrel, app))
    }

    @Test
    fun `two of the same name resolve to neither, and say what they are`() {
        // The same rule Lore uses for a `[[link]]`: an ambiguous name resolves to nothing rather
        // than to whichever row came first. A caller told "which one?" can ask again; one told
        // nothing writes into somebody else's work and never finds out.
        val draft = Row("p3", "Draft")
        val otherDraft = Row("p4", "draft")

        val match = resolve("Draft", draft, otherDraft, kestrel)

        assertEquals(NameLookup.Match.Ambiguous(listOf("Draft", "draft")), match)
    }

    @Test
    fun `an id still wins when the name it carries is ambiguous`() {
        val draft = Row("p3", "Draft")
        val otherDraft = Row("p4", "Draft")

        assertEquals(NameLookup.Match.Found(draft), resolve("p3", draft, otherDraft))
    }
    @Test
    fun `the three answers become the three words the address scheme has for them`() {
        val draft = Row("p3", "Draft")
        val otherDraft = Row("p4", "draft")

        assertTrue(resolve("p1", kestrel).orProblem("project", "p1") is Resolution.Ok)

        // Not found is NOT_FOUND. Ambiguous is INVALID_PARAMS — the thing *was* found, more than
        // once, and the caller has to say something different, so the message names the candidates
        // rather than leaving them to guess which two collided.
        val missing = resolve("The Peregrine", kestrel).orProblem("project", "The Peregrine")
        assertEquals(
            ConnectionError.NOT_FOUND,
            ((missing as Resolution.Problem).failure).error
        )
        assertEquals("No project called 'The Peregrine'", missing.failure.message)

        val ambiguous = resolve("Draft", draft, otherDraft).orProblem("project", "Draft")
        assertEquals(
            ConnectionError.INVALID_PARAMS,
            ((ambiguous as Resolution.Problem).failure).error
        )
        assertEquals(
            "More than one project is called 'Draft' (Draft, draft). Use its id.",
            ambiguous.failure.message
        )
    }
}
