package com.operations.vaultkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore, as an algebra problem.
 *
 * Every test here is a real sequence somebody will hit: the phone that was replaced, the archive
 * that is six months old, the password added since, the login deliberately deleted last week, and
 * the same archive imported twice because nobody could remember whether it had worked the first
 * time.
 */
class VaultMergeTest {

    private fun item(id: String, title: String, secret: String, at: Long) =
        VaultItem(id = id, title = title, secret = secret, createdAt = at, updatedAt = at)

    @Test
    fun `items only the archive has are added`() {
        val mine = VaultDocument(items = listOf(item("a", "Bank", "x", 100)))
        val archive = VaultDocument(items = listOf(item("b", "Library card", "y", 50)))

        val outcome = VaultMerge.merge(mine, archive, now = 200)

        assertEquals(1, outcome.added)
        assertEquals(2, outcome.document.live.size)
        assertNotNull(outcome.document.item("b"))
    }

    @Test
    fun `an edit made since the backup survives the merge`() {
        val mine = VaultDocument(items = listOf(item("a", "Bank", "new-password", 500)))
        val archive = VaultDocument(items = listOf(item("a", "Bank", "old-password", 100)))

        val outcome = VaultMerge.merge(mine, archive, now = 600)

        assertEquals("new-password", outcome.document.item("a")?.secret)
        assertEquals(0, outcome.changed)
    }

    @Test
    fun `an item the archive has newer wins - the phone that was replaced`() {
        // The old phone was the one being used; this one has an older copy of the same item.
        val mine = VaultDocument(items = listOf(item("a", "Bank", "stale", 100)))
        val archive = VaultDocument(items = listOf(item("a", "Bank", "current", 900)))

        val outcome = VaultMerge.merge(mine, archive, now = 1000)

        assertEquals("current", outcome.document.item("a")?.secret)
        assertEquals(1, outcome.updated)
    }

    @Test
    fun `restoring an old archive does not resurrect what was deleted since`() {
        val deleted = VaultDocument(items = listOf(item("a", "Old login", "x", 100)))
            .delete("a", now = 500)
        val archive = VaultDocument(items = listOf(item("a", "Old login", "x", 100)))

        val outcome = VaultMerge.merge(deleted, archive, now = 900)

        assertNull("the deletion is newer than the archive's copy", outcome.document.item("a"))
        assertEquals(0, outcome.added)
    }

    @Test
    fun `a deletion in the archive does not remove something edited here afterwards`() {
        val mine = VaultDocument(items = listOf(item("a", "Bank", "edited-after", 900)))
        val archive = VaultDocument(items = listOf(item("a", "Bank", "x", 100))).delete("a", now = 200)

        val outcome = VaultMerge.merge(mine, archive, now = 1000)

        assertNotNull(outcome.document.item("a"))
        assertEquals("edited-after", outcome.document.item("a")?.secret)
        assertEquals(0, outcome.deleted)
    }

    @Test
    fun `a deletion in the archive that is genuinely newer is applied`() {
        val mine = VaultDocument(items = listOf(item("a", "Bank", "x", 100)))
        val archive = VaultDocument(items = listOf(item("a", "Bank", "x", 100))).delete("a", now = 800)

        val outcome = VaultMerge.merge(mine, archive, now = 1000)

        assertNull(outcome.document.item("a"))
        assertEquals(1, outcome.deleted)
    }

    @Test
    fun `merging the same archive twice changes nothing the second time`() {
        val mine = VaultDocument(items = listOf(item("a", "Bank", "x", 100)))
        val archive = VaultDocument(items = listOf(item("b", "Card", "y", 50)))

        val once = VaultMerge.merge(mine, archive, now = 200)
        val twice = VaultMerge.merge(once.document, archive, now = 300)

        assertEquals(0, twice.changed)
        assertEquals(once.document.items.map { it.id }.sorted(), twice.document.items.map { it.id }.sorted())
    }

    @Test
    fun `merging an empty vault into a full one is a no-op, and the reverse is a copy`() {
        val full = VaultDocument(items = listOf(item("a", "Bank", "x", 100), item("b", "Card", "y", 100)))

        assertEquals(0, VaultMerge.merge(full, VaultDocument.EMPTY, now = 200).changed)

        val fresh = VaultMerge.merge(VaultDocument.EMPTY, full, now = 200)
        assertEquals(2, fresh.added)
        assertEquals(2, fresh.document.live.size)
    }

    @Test
    fun `a tombstone the archive alone holds is carried across rather than dropped`() {
        // It is the only thing standing between a third, older archive and a resurrected password.
        val mine = VaultDocument.EMPTY
        val archive = VaultDocument(items = listOf(item("a", "Gone", "x", 100))).delete("a", now = 200)

        val outcome = VaultMerge.merge(mine, archive, now = 300)

        assertEquals(0, outcome.added)
        assertTrue(outcome.document.items.single().isDeleted)
        assertTrue(outcome.document.live.isEmpty())
    }

    @Test
    fun `pruning drops tombstones past their year and keeps everything else`() {
        val now = 10 * VaultMerge.YEAR_MILLIS
        val document = VaultDocument(
            items = listOf(
                item("live", "Bank", "x", now),
                item("recent", "Gone last month", "", now - 30L * 24 * 60 * 60 * 1000)
                    .copy(deletedAt = now - 30L * 24 * 60 * 60 * 1000),
                item("ancient", "Gone years ago", "", now - 3 * VaultMerge.YEAR_MILLIS)
                    .copy(deletedAt = now - 3 * VaultMerge.YEAR_MILLIS)
            )
        )

        val pruned = VaultMerge.prune(document, now)

        assertEquals(listOf("live", "recent"), pruned.items.map { it.id })
    }

    @Test
    fun `pruning a document with nothing to prune returns the same document`() {
        val document = VaultDocument(items = listOf(item("a", "Bank", "x", 100)))

        assertTrue(VaultMerge.prune(document, now = 200) === document)
    }
}
