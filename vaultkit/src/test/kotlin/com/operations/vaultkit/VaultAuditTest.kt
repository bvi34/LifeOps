package com.operations.vaultkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultAuditTest {

    private val now = 1_700_000_000_000L

    private fun item(
        id: String,
        secret: String,
        title: String = id,
        updatedAt: Long = now,
        kind: VaultItemKind = VaultItemKind.LOGIN,
        ref: String? = null
    ) = VaultItem(
        id = id,
        kind = kind,
        title = title,
        secret = secret,
        ref = ref,
        createdAt = updatedAt,
        updatedAt = updatedAt
    )

    private val strong = PasswordGenerator.password(PasswordRecipe(length = 24)).value

    @Test
    fun `a vault of generated passwords has nothing to say about itself`() {
        val items = (1..5).map { item("i$it", PasswordGenerator.password(PasswordRecipe(length = 20)).value) }

        val report = VaultAudit.run(items, now)

        assertTrue(report.findings.toString(), report.clean)
        assertEquals(5, report.examined)
    }

    @Test
    fun `a weak password is reported once`() {
        val report = VaultAudit.run(listOf(item("a", "hunter2"), item("b", strong)), now)

        assertEquals(1, report.count(VaultAudit.Kind.WEAK))
        assertEquals("a", report.findings.single { it.kind == VaultAudit.Kind.WEAK }.itemId)
    }

    @Test
    fun `reuse is reported on every item that shares the password, naming the others`() {
        val shared = PasswordGenerator.password(PasswordRecipe(length = 20)).value
        val items = listOf(
            item("a", shared, title = "Bank"),
            item("b", shared, title = "Broadband"),
            item("c", strong, title = "Elsewhere")
        )

        val report = VaultAudit.run(items, now)

        assertEquals(2, report.count(VaultAudit.Kind.REUSED))
        val bank = report.findings.single { it.itemId == "a" && it.kind == VaultAudit.Kind.REUSED }
        assertEquals(listOf("Broadband"), bank.alsoUsedBy)
    }

    @Test
    fun `a deleted item is examined for nothing`() {
        val items = listOf(
            item("a", "hunter2").copy(deletedAt = now - 1),
            item("b", strong)
        )

        val report = VaultAudit.run(items, now)

        assertTrue(report.clean)
        assertEquals(1, report.examined)
    }

    @Test
    fun `an old password is reported stale, and a fresh one is not`() {
        val items = listOf(
            item("old", strong, updatedAt = now - VaultAudit.TWO_YEARS - 1),
            item("new", strong, updatedAt = now - 1000)
        )

        val report = VaultAudit.run(items, now)

        assertEquals(1, report.count(VaultAudit.Kind.STALE))
        assertEquals("old", report.findings.single { it.kind == VaultAudit.Kind.STALE }.itemId)
    }

    @Test
    fun `a mirrored credential is never nagged about its age`() {
        // Nobody can "change" a Plaid access token from this screen, so saying it is old is noise.
        val items = listOf(
            item("t", strong, updatedAt = now - 5 * VaultAudit.TWO_YEARS, ref = "finance/usaa/access-token")
        )

        val report = VaultAudit.run(items, now)

        assertFalse(report.findings.any { it.kind == VaultAudit.Kind.STALE })
    }

    @Test
    fun `an empty secret is a finding for a login and expected for a note`() {
        val items = listOf(
            item("stub", "", kind = VaultItemKind.LOGIN),
            item("note", "", kind = VaultItemKind.NOTE)
        )

        val report = VaultAudit.run(items, now)

        assertEquals(1, report.count(VaultAudit.Kind.NO_SECRET))
        assertEquals("stub", report.findings.single().itemId)
    }

    @Test
    fun `an item can be both weak and reused`() {
        val items = listOf(item("a", "abc123"), item("b", "abc123"))

        val report = VaultAudit.run(items, now)

        val forA = report.findings.filter { it.itemId == "a" }.map { it.kind }.toSet()
        assertEquals(setOf(VaultAudit.Kind.WEAK, VaultAudit.Kind.REUSED), forA)
    }

    @Test
    fun `search ranks a title match above a note mention and never matches a secret`() {
        val items = listOf(
            VaultItem(id = "1", title = "Riverbank note", note = "nothing here", secret = strong),
            VaultItem(id = "2", title = "Bank", secret = "riverbank"),
            VaultItem(id = "3", title = "Broadband", note = "pay the bank on the 4th", secret = strong)
        )

        val results = VaultSearch.search(items, "bank")

        assertEquals(listOf("2", "1", "3"), results.map { it.id })
        assertTrue("a secret is not searchable", VaultSearch.search(items, "riverbank").none { it.id == "2" })
    }

    @Test
    fun `an empty query lists everything, favourites first`() {
        val items = listOf(
            VaultItem(id = "1", title = "Zebra"),
            VaultItem(id = "2", title = "Apple"),
            VaultItem(id = "3", title = "Mango", favourite = true),
            VaultItem(id = "4", title = "Gone", deletedAt = 1)
        )

        val results = VaultSearch.search(items, "")

        assertEquals(listOf("3", "2", "1"), results.map { it.id })
    }
}
