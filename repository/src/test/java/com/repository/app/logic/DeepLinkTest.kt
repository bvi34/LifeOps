package com.repository.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The address vocabulary: what can be written down, what can be read back, and what is refused.
 *
 * The rule the whole file is about is that **a bad address is never a plausible one**. Every other
 * failure mode here is a disappointment — somebody is not taken to the document they were promised —
 * whereas an address that parses into a *different* destination from the one that was written takes
 * them to somebody else's paperwork and looks like it worked.
 */
class DeepLinkTest {

    @Test
    fun `every destination survives the round trip`() {
        val destinations = listOf(
            RepositoryDestination.Shelf,
            RepositoryDestination.Drawer(null),
            RepositoryDestination.Drawer("maintenance"),
            RepositoryDestination.Record("maintenance", "a3f2"),
            RepositoryDestination.Document("doc-1"),
            RepositoryDestination.Document("lab-1", sourceKey = "health")
        )

        destinations.forEach { destination ->
            val address = RepositoryLinks.format(destination)
            assertEquals("$destination did not survive being written down", destination, RepositoryLinks.parse(address))
        }
    }

    @Test
    fun `the household's drawer is a place, written down as one`() {
        // Not an empty segment: an address with a hole in it is one nobody can read in a log, and
        // "no owner" is what this drawer *is* rather than something missing from it.
        assertEquals("drawer/household", RepositoryLinks.format(RepositoryDestination.Drawer(null)))
        assertEquals(RepositoryDestination.Drawer(null), RepositoryLinks.parse("drawer/household"))
    }

    @Test
    fun `a lent document is addressed by its lender as well as its id`() {
        // An id is only unique inside the app that minted it, which is why the shelf keys rows on
        // the pair. Health's `doc-1` and Repository's `doc-1` are two documents.
        assertEquals("document/doc-1", RepositoryLinks.format(RepositoryDestination.Document("doc-1")))
        assertEquals("lent/health/doc-1", RepositoryLinks.format(RepositoryDestination.Document("doc-1", "health")))
        assertEquals(
            RepositoryDestination.Document("doc-1", "health"),
            RepositoryLinks.parse("lent/health/doc-1")
        )
        assertEquals(
            "and the two addresses are not each other",
            RepositoryDestination.Document("doc-1", sourceKey = null),
            RepositoryLinks.parse("document/doc-1")
        )
    }

    @Test
    fun `a key that would split into extra segments cannot be written down at all`() {
        // Rather than being written and read back as something else. A caller handed null opens the
        // shelf, which is a disappointment; an address that means a different record is a bug that
        // shows somebody the wrong drawer and looks like it worked.
        assertNull(RepositoryLinks.format(RepositoryDestination.Record("maintenance", "a3f2/../b7c1")))
        assertNull(RepositoryLinks.format(RepositoryDestination.Document("doc/1")))
        assertNull(RepositoryLinks.format(RepositoryDestination.Document("doc-1", sourceKey = "he/alth")))
        assertNull(RepositoryLinks.format(RepositoryDestination.Drawer("main/tenance")))
    }

    @Test
    fun `nothing empty is written down as something`() {
        assertNull(RepositoryLinks.format(RepositoryDestination.Record("maintenance", "   ")))
        assertNull(RepositoryLinks.format(RepositoryDestination.Record("", "a3f2")))
        assertNull(RepositoryLinks.format(RepositoryDestination.Document("")))
    }

    @Test
    fun `an app that collided with the household sentinel is refused, not silently made the household`() {
        // No app in the suite has this key. If one ever did, an address for its drawer would read as
        // the household's — so it is refused at the point of writing rather than at the point of
        // being misread.
        assertNull(RepositoryLinks.format(RepositoryDestination.Drawer("household")))
        assertNull(RepositoryLinks.format(RepositoryDestination.Record("household", "a3f2")))
    }

    @Test
    fun `the household's drawer has no records in it`() {
        // Nothing owns those documents, which is what makes them the household's.
        assertNull(RepositoryLinks.parse("drawer/household/a3f2"))
    }

    @Test
    fun `anything this app does not recognise opens the shelf plainly`() {
        listOf(
            null,
            "",
            "   ",
            "//",
            "everything",
            "drawer",
            "drawer/maintenance/a3f2/extra",
            "document",
            "document/doc-1/extra",
            "lent/health",
            "lent/health/doc-1/extra",
            "shelf/maintenance",
            "https://example.com/document/doc-1"
        ).forEach { address ->
            assertNull("“$address” should not have parsed", RepositoryLinks.parse(address))
        }
    }

    @Test
    fun `an address is read the same however it is spaced`() {
        // It arrives as an intent extra somebody else assembled; leading space or a trailing slash
        // is not a reason to strand them on a document they were sent to.
        assertEquals(RepositoryDestination.Shelf, RepositoryLinks.parse("  shelf  "))
        assertEquals(
            RepositoryDestination.Record("maintenance", "a3f2"),
            RepositoryLinks.parse("/drawer/maintenance/a3f2/")
        )
    }

    // ------------------------------------------------------------------ how a narrowing reads

    private val documents = listOf(
        facts("doc-1", "2018 Jeep Wrangler manual", DocumentOwner("maintenance", "a3f2", "2018 Jeep Wrangler")),
        facts("doc-2", "Warranty", DocumentOwner("maintenance", "a3f2", "2018 Jeep Wrangler")),
        facts("doc-3", "Deed", DocumentOwner("maintenance", "b7c1", "12 Oak Lane")),
        facts("lab-1", "Blood panel", DocumentOwner.HOUSEHOLD, sourceKey = "health")
    )

    @Test
    fun `a whole drawer says nothing, because the chips already say it`() {
        assertNull(RepositoryDestination.Shelf.describe(documents))
        assertNull(RepositoryDestination.Drawer("maintenance").describe(documents))
        assertNull(RepositoryDestination.Drawer(null).describe(documents))
    }

    @Test
    fun `a record is named by what the owning app last called it`() {
        // Not looked up — carried. This module cannot ask Maintenance what `a3f2` is, which is the
        // decision that keeps it free of every other module.
        assertEquals(
            "2018 Jeep Wrangler · 2 documents",
            RepositoryDestination.Record("maintenance", "a3f2").describe(documents)
        )
        assertEquals(
            "12 Oak Lane · 1 document",
            RepositoryDestination.Record("maintenance", "b7c1").describe(documents)
        )
    }

    @Test
    fun `a record whose documents have all gone takes its name with them`() {
        // There is nothing left on the shelf that knows what `c4d5` was called, and guessing is the
        // one thing this module never does.
        assertEquals(
            "Nothing filed yet",
            RepositoryDestination.Record("maintenance", "c4d5").describe(documents)
        )
    }

    @Test
    fun `one document is named, and a stale link says so instead of showing an empty shelf`() {
        assertEquals(
            "Showing “Warranty”",
            RepositoryDestination.Document("doc-2").describe(documents)
        )
        assertEquals(
            "Showing “Blood panel”",
            RepositoryDestination.Document("lab-1", sourceKey = "health").describe(documents)
        )
        assertEquals(
            "That document isn't on the shelf any more",
            RepositoryDestination.Document("doc-2", sourceKey = "health").describe(documents)
        )
    }

    private fun facts(
        id: String,
        title: String,
        owner: DocumentOwner,
        sourceKey: String? = null
    ) = DocumentFacts(
        id = id,
        title = title,
        kind = DocumentKind.OTHER,
        owner = owner,
        mimeType = "application/pdf",
        sizeBytes = null,
        addedAt = 1_700_000_000_000L,
        sourceKey = sourceKey
    )
}
