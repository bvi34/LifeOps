package com.repository.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf, which is the whole app: one list, findable without having filed anything correctly.
 */
class ShelfTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private fun document(
        id: String,
        title: String,
        daysAgo: Long = 0,
        kind: DocumentKind = DocumentKind.OTHER,
        app: String? = null,
        record: String? = null,
        label: String? = null,
        note: String? = null,
        size: Long? = null,
        source: String? = null
    ) = DocumentFacts(
        id = id,
        title = title,
        kind = kind,
        owner = DocumentOwner(app, record, label),
        mimeType = "application/pdf",
        sizeBytes = size,
        addedAt = now - daysAgo * day,
        note = note,
        sourceKey = source
    )

    // ------------------------------------------------------------------ order and search

    @Test
    fun `newest filed first, because that is the only date this app has`() {
        // It does not read documents, so it cannot know the date printed on one.
        val ordered = Shelf.order(
            listOf(
                document("a", "Old survey", daysAgo = 400),
                document("b", "Statement", daysAgo = 1),
                document("c", "Warranty", daysAgo = 30)
            )
        )

        assertEquals(listOf("b", "c", "a"), ordered.map { it.id })
    }

    @Test
    fun `two filed in the same second still come out in a fixed order`() {
        val ordered = Shelf.order(
            listOf(document("z", "Zebra"), document("a", "Apple"), document("m", "Mango"))
        )

        assertEquals(listOf("Apple", "Mango", "Zebra"), ordered.map { it.title })
    }

    @Test
    fun `search finds a document by what it is about, not just what it is called`() {
        val shelf = listOf(
            document("a", "Owner's manual", app = "maintenance", record = "asset-1", label = "2018 Jeep Wrangler"),
            document("b", "Owner's manual", app = "maintenance", record = "asset-2", label = "Furnace"),
            document("c", "Mortgage statement", app = "maintenance", record = "asset-3", label = "The house")
        )

        // The owner's label is the search field that makes the shelf usable — and this module still
        // has no idea what a Wrangler is.
        assertEquals(listOf("a"), Shelf.search(shelf, "wrangler").map { it.id })
        // Terms may match different fields, so this finds the manual on the furnace.
        assertEquals(listOf("b"), Shelf.search(shelf, "furnace manual").map { it.id })
        assertEquals(listOf("c"), Shelf.search(shelf, "MORTGAGE").map { it.id })
    }

    @Test
    fun `search also covers the note and the kind, and a blank query is not a filter`() {
        val shelf = listOf(
            document("a", "Scan 004", note = "the roof survey"),
            document("b", "Policy 1121", kind = DocumentKind.POLICY)
        )

        assertEquals(listOf("a"), Shelf.search(shelf, "roof").map { it.id })
        assertEquals(listOf("b"), Shelf.search(shelf, "policy").map { it.id })
        // An empty search box is the state the screen opens in; it shows everything.
        assertEquals(2, Shelf.search(shelf, "   ").size)
        assertTrue(Shelf.search(shelf, "nothing like this").isEmpty())
    }

    // ------------------------------------------------------------------ drawers

    @Test
    fun `the household's own drawer leads, then the apps by name`() {
        val drawers = Shelf.drawers(
            listOf(
                document("a", "Manual", app = "maintenance"),
                document("b", "Will"),
                document("c", "Bloods", app = "health", source = "health")
            )
        ) { key -> key.replaceFirstChar { it.uppercase() } }

        // A document that belongs to no app is the one nothing else will ever show you.
        assertEquals(listOf(Shelf.HOUSEHOLD_LABEL, "Health", "Maintenance"), drawers.map { it.label })
        assertEquals(listOf("b"), drawers.first().documents.map { it.id })
    }

    @Test
    fun `a drawer from an app this build does not have is still a drawer`() {
        // A restored document from an app that is not installed is still a document.
        val drawers = Shelf.drawers(listOf(document("a", "Something", app = "atlantis"))) { null }

        assertEquals(listOf("atlantis"), drawers.map { it.label })
    }

    @Test
    fun `the documents on one record are the ones both keys match`() {
        val shelf = listOf(
            document("a", "Manual", app = "maintenance", record = "1"),
            document("b", "Bloods", app = "health", record = "1"),
            document("c", "Warranty", app = "maintenance", record = "1"),
            document("d", "Deed", app = "maintenance", record = "2")
        )

        // A record key is only unique inside the app that issued it, and two apps numbering their
        // things from one is not a hypothetical.
        assertEquals(listOf("a", "c"), Shelf.on(shelf, "maintenance", "1").map { it.id }.sorted())
    }

    @Test
    fun `the headline counts what is there and says how much of it there is`() {
        assertEquals("Nothing filed yet", Shelf.headline(emptyList()))
        assertEquals("1 document · 400 KB", Shelf.headline(listOf(document("a", "A", size = 400_000))))
        assertEquals(
            "2 documents · 1.4 MB",
            Shelf.headline(listOf(document("a", "A", size = 400_000), document("b", "B", size = 1_000_000)))
        )
    }

    // ------------------------------------------------------------------ the rows themselves

    @Test
    fun `a lent document knows it is lent, and a filed one knows what it is filed against`() {
        val lent = document("a", "Bloods", app = "health", source = "health")
        val filed = document("b", "Manual", app = "maintenance", record = "asset-1", label = "Furnace")
        val household = document("c", "Will")

        assertTrue(lent.isForeign)
        assertFalse(filed.isForeign)
        assertTrue(filed.owner.isFiled)
        assertFalse(household.owner.isFiled)
        assertNull(household.owner.label)
    }

    @Test
    fun `a document says what it is and how big, and nothing it does not know`() {
        assertEquals(
            "Policy or cover · 1.2 MB",
            document("a", "Policy", kind = DocumentKind.POLICY, size = 1_200_000).descriptor
        )
        // No size from the picker is not "0 bytes".
        assertEquals("Policy or cover", document("a", "Policy", kind = DocumentKind.POLICY).descriptor)
    }
}
