package com.repository.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf, written down beside its documents.
 *
 * The rule every assertion here serves is that **a manifest must never be able to stop somebody
 * importing their own files**. It is an enrichment — titles, kinds, notes, what a document is about
 * — and the documents are the point, so every way it can be wrong (missing, truncated, some other
 * JSON that happens to sit in the folder, written by a newer build) has to come back null and let
 * the import carry on plainly.
 */
class SidecarTest {

    private val statement = ShelfEntry(
        file = "Mortgage statement March 2026.pdf",
        id = "doc-1",
        title = "Mortgage statement March 2026",
        kind = "statement",
        note = "the one from the bank",
        app = "maintenance",
        record = "b7c1",
        about = "12 Oak Lane",
        addedAt = 1_700_000_000_000L
    )

    private val manual = ShelfEntry(
        file = "Manual.pdf",
        id = "doc-2",
        title = "Manual",
        kind = "manual",
        app = "maintenance",
        record = "a3f2",
        about = "2018 Jeep Wrangler"
    )

    @Test
    fun `a manifest survives the round trip with everything a person typed`() {
        val manifest = ShelfManifest(documents = listOf(statement, manual))

        val read = Sidecar.decode(Sidecar.encode(manifest))!!

        assertEquals(Sidecar.VERSION, read.version)
        assertEquals(manifest.documents, read.documents)
    }

    @Test
    fun `an entry knows the drawer it came out of, label and all`() {
        // The payoff of the label being carried on the row rather than looked up: the other phone
        // may not have this asset, and the drawer still reads "12 Oak Lane" rather than `b7c1`.
        assertEquals(DocumentOwner("maintenance", "b7c1", "12 Oak Lane"), statement.owner)
        assertEquals(DocumentKind.STATEMENT, statement.documentKind)
        // Kind is vocabulary, so one this build does not know reads as Other rather than refusing
        // the document.
        assertEquals(DocumentKind.OTHER, statement.copy(kind = "blueprint").documentKind)
    }

    @Test
    fun `a picked file is matched to its entry by the name the drive gave it`() {
        val manifest = ShelfManifest(documents = listOf(statement, manual))

        assertEquals(statement, manifest.entryFor("Mortgage statement March 2026.pdf"))
        assertEquals(manual, manifest.entryFor("  Manual.pdf  "))
        assertNull(manifest.entryFor("Something else.pdf"))
        assertNull(manifest.entryFor(null))
        assertNull(manifest.entryFor("   "))
    }

    @Test
    fun `two documents that landed under different names stay different documents`() {
        // A folder that already holds a Statement.pdf answers the second one with "Statement (1)",
        // which is why the manifest is keyed on what the drive actually wrote.
        val first = statement.copy(file = "Statement.pdf", id = "doc-1")
        val second = statement.copy(file = "Statement (1).pdf", id = "doc-9", title = "Statement 2025")
        val manifest = ShelfManifest(documents = listOf(first, second))

        assertEquals("doc-1", manifest.entryFor("Statement.pdf")?.id)
        assertEquals("doc-9", manifest.entryFor("Statement (1).pdf")?.id)
    }

    @Test
    fun `everything that is not a manifest reads as no manifest at all`() {
        listOf(
            null,
            "",
            "   ",
            "not json at all",
            """{"version":1,"documents":[]}""",
            // Some other JSON that happens to be sitting in the folder. Gson will build an object
            // out of it happily; a manifest describing no documents is not one.
            """{"hello":"world"}""",
            """[1,2,3]""",
            // Entries with nothing to match on or nothing to identify. Filing these would put a
            // document under a blank id and lose the skip-if-already-here rule with it.
            """{"version":1,"documents":[{"file":"","id":"doc-1","title":"x","kind":"other"}]}""",
            """{"version":1,"documents":[{"file":"a.pdf","id":"","title":"x","kind":"other"}]}"""
        ).forEach { text ->
            assertNull("“$text” should not have read as a manifest", Sidecar.decode(text))
        }
    }

    @Test
    fun `a manifest from a newer Repository is ignored rather than half-read`() {
        val text = Sidecar.encode(ShelfManifest(version = Sidecar.VERSION + 1, documents = listOf(manual)))

        // The documents still import, plainly. Reading a v2 field under v1 rules is how one ends up
        // filed against the wrong thing, and a missing caption is the cheaper failure by far.
        assertNull(Sidecar.decode(text))
    }

    @Test
    fun `a truncated manifest is nothing, not a crash`() {
        val whole = Sidecar.encode(ShelfManifest(documents = listOf(statement, manual)))

        assertNull(Sidecar.decode(whole.substring(0, whole.length / 2)))
    }

    @Test
    fun `the manifest is recognised by name, whatever case the drive gave it`() {
        assertTrue(Sidecar.isManifest("repository-shelf.json"))
        assertTrue(Sidecar.isManifest("  Repository-Shelf.JSON "))
        assertFalse(Sidecar.isManifest("repository-shelf.json.pdf"))
        assertFalse(Sidecar.isManifest("shelf.json"))
        assertFalse(Sidecar.isManifest(null))
    }

    @Test
    fun `a row becomes an entry carrying what a person typed and nothing derived`() {
        val facts = DocumentFacts(
            id = "doc-1",
            title = "Mortgage statement March 2026",
            kind = DocumentKind.STATEMENT,
            owner = DocumentOwner("maintenance", "b7c1", "12 Oak Lane"),
            mimeType = "application/pdf",
            sizeBytes = 4_096L,
            addedAt = 1_700_000_000_000L,
            note = "the one from the bank"
        )

        // Keyed on what the drive called it, not on what the export asked for.
        val entry = facts.entry("Mortgage statement March 2026 (1).pdf")

        assertEquals("Mortgage statement March 2026 (1).pdf", entry.file)
        assertEquals(statement.copy(file = entry.file), entry)
        // The size and the type are facts about the file, which the importing shelf reads from the
        // bytes it is handed. A manifest that disagreed with the file beside it would describe
        // something that is not there.
        val written = Sidecar.encode(ShelfManifest(documents = listOf(entry)))
        assertFalse(written.contains("4096"))
        assertFalse(written.contains("application/pdf"))
    }
}
