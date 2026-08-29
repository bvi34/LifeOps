package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoreTest {

    private fun entry(
        id: String,
        name: String,
        body: String = "",
        aliases: List<String> = emptyList(),
        category: LoreCategory = LoreCategory.OTHER
    ) = LoreEntry(id, name, category, null, body, aliases)

    @Test
    fun `links are found in order, de-duplicated, and keep the name over the display half`() {
        val text = "She met [[Kestrel]] at [[The Harbour|the docks]], then [[kestrel]] again."

        assertEquals(listOf("Kestrel", "The Harbour"), Lore.links(text))
    }

    @Test
    fun `text with no links yields nothing`() {
        assertEquals(emptyList<String>(), Lore.links(null))
        assertEquals(emptyList<String>(), Lore.links("no brackets here [not a link]"))
        assertEquals(emptyList<String>(), Lore.links("[[]]"))
    }

    @Test
    fun `an alias resolves to its entry`() {
        val entries = listOf(entry("k", "Kestrel", aliases = listOf("the Captain", "Kes")))
        val index = Lore.index(entries)

        assertEquals("k", index.resolve("Kestrel"))
        assertEquals("k", index.resolve("the captain"))
        assertEquals("k", index.resolve("  KES  "))
        assertNull(index.resolve("nobody"))
    }

    @Test
    fun `an ambiguous name resolves to nothing rather than to whichever row came first`() {
        val entries = listOf(
            entry("a", "Alia", aliases = listOf("the Captain")),
            entry("b", "Brann", aliases = listOf("The Captain"))
        )
        val index = Lore.index(entries)

        assertNull(index.resolve("the Captain"))
        assertTrue(index.isAmbiguous("the captain"))
        assertTrue(index.knows("the captain"))
        assertEquals(listOf("the captain"), Lore.ambiguousNames(entries))
    }

    @Test
    fun `an ambiguous mention is reported as ambiguous, not as broken-and-missing`() {
        val entries = listOf(
            entry("a", "Alia", aliases = listOf("the Captain")),
            entry("b", "Brann", aliases = listOf("the Captain")),
            entry("c", "Ship", body = "Commanded by [[the Captain]] and by [[Nobody At All]].")
        )
        val index = Lore.index(entries)

        val mentions = Lore.mentions(entries.first { it.id == "c" }.body, index)

        assertEquals(2, mentions.size)
        assertTrue(mentions[0].ambiguous)
        assertTrue(mentions[0].isBroken)
        assertFalse(mentions[1].ambiguous)
        assertTrue(mentions[1].isBroken)

        // Over-supplied is not missing: an ambiguous name needs a rename, not a new page.
        assertEquals(listOf("Nobody At All"), Lore.brokenLinks(entries))
    }

    @Test
    fun `backlinks are collected without anyone having recorded them`() {
        val entries = listOf(
            entry("k", "Kestrel"),
            entry("h", "The Harbour", body = "Where [[Kestrel]] keeps her boat."),
            entry("s", "The Storm", body = "It caught [[Kestrel]] off [[The Harbour]].")
        )

        val backlinks = Lore.backlinks(entries)

        assertEquals(setOf("h", "s"), backlinks["k"]!!.toSet())
        assertEquals(listOf("s"), backlinks["h"])
        assertNull(backlinks["s"])
    }

    @Test
    fun `an entry linking to itself is not its own backlink`() {
        val entries = listOf(entry("k", "Kestrel", body = "See [[Kestrel]]."))

        assertTrue(Lore.backlinks(entries).isEmpty())
    }

    @Test
    fun `broken links are the list of pages worth writing next`() {
        val entries = listOf(
            entry("k", "Kestrel", body = "Trained by [[Master Iven]] on [[The Harbour]]."),
            entry("h", "The Harbour", body = "Also known to [[Master Iven]].")
        )

        assertEquals(listOf("Master Iven"), Lore.brokenLinks(entries))
    }

    @Test
    fun `aliases round-trip through their stored column`() {
        assertEquals(listOf("Kes", "the Captain"), Lore.parseAliases(" Kes ,the Captain ; "))
        assertEquals(emptyList<String>(), Lore.parseAliases(null))
        assertEquals("Kes, the Captain", Lore.joinAliases(listOf("Kes", "", "the Captain")))
    }

    @Test
    fun `search looks at name, alias, summary and body`() {
        val entries = listOf(
            LoreEntry("k", "Kestrel", LoreCategory.CHARACTER, "A smuggler", "Sails the [[Narrow Sea]].", listOf("Kes")),
            LoreEntry("h", "The Harbour", LoreCategory.PLACE, null, "Wet.", emptyList())
        )

        assertEquals(listOf("k"), Lore.search(entries, "smug").map { it.id })
        assertEquals(listOf("k"), Lore.search(entries, "kes").map { it.id })
        assertEquals(listOf("k"), Lore.search(entries, "narrow").map { it.id })
        assertEquals(2, Lore.search(entries, "  ").size)
    }
}
