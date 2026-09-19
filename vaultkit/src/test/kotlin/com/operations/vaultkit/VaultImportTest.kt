package com.operations.vaultkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import, meeting a vault that is not empty.
 *
 * The first import into a new vault is the easy case and it is one test here. The rest are the
 * second one: the household who imported Chrome in March and again in June, the login they have
 * since edited by hand, the password they changed *here* and not there, and the mirrored credential
 * an app owns that a CSV row must never be allowed to overwrite.
 */
class VaultImportTest {

    private val now = 1_700_000_000_000L
    private val MARCH = 1_678_000_000_000L
    private val JUNE = 1_686_000_000_000L

    private fun candidate(
        id: String,
        title: String,
        url: String,
        username: String,
        secret: String
    ) = VaultItem(
        id = id,
        title = title,
        url = url,
        username = username,
        secret = secret,
        createdAt = now,
        updatedAt = now
    )

    private fun read(vararg items: VaultItem) =
        VaultImport.Read(VaultImport.Format.CHROMIUM_CSV, items.toList())

    @Test
    fun `everything is new in an empty vault`() {
        val plan = VaultImport.plan(
            VaultDocument.EMPTY,
            read(candidate("a", "Bank", "https://bank.example", "me", "hunter2"))
        )

        assertEquals(1, plan.newCount)
        assertEquals(setOf("a"), plan.defaultSelection)

        val outcome = VaultImport.apply(VaultDocument.EMPTY, plan, plan.defaultSelection, now)
        assertEquals(1, outcome.added)
        assertEquals("hunter2", outcome.document.live.single().secret)
    }

    @Test
    fun `the same export twice adds nothing the second time`() {
        val existing = candidate("here", "Bank", "https://www.bank.example/login", "me", "hunter2")
        val document = VaultDocument(items = listOf(existing))

        val plan = VaultImport.plan(
            document,
            // A different row id, the address written differently, the same account.
            read(candidate("a", "Bank", "https://bank.example", "me", "hunter2"))
        )

        assertEquals(1, plan.alreadyHereCount)
        assertTrue(plan.defaultSelection.isEmpty())
        assertEquals(1, VaultImport.apply(document, plan, setOf("a"), now).document.live.size)
    }

    @Test
    fun `a different password with nothing to date it is left to the household`() {
        val document = VaultDocument(items = listOf(candidate("here", "Bank", "bank.example", "me", "old")))

        val plan = VaultImport.plan(
            document,
            // A Chrome CSV carries no dates, so both sides are undated and neither can win.
            read(candidate("a", "Bank", "https://bank.example", "me", "new").copy(updatedAt = 0))
        )

        assertEquals(1, plan.count(VaultImport.Verdict.UNDECIDED))
        assertFalse("a" in plan.defaultSelection)
    }

    @Test
    fun `an undated import does not get to claim it is the newest copy`() {
        // The bug this guards: a reader that stamped undated rows with the moment of the import
        // would make every CSV newer than everything in the vault, and every import a silent
        // overwrite of passwords changed since.
        val document = VaultDocument(items = listOf(candidate("here", "Bank", "bank.example", "me", "old")))
        val undated = candidate("a", "Bank", "https://bank.example", "me", "new")
            .copy(createdAt = 0, updatedAt = 0)

        val plan = VaultImport.plan(document, read(undated))

        assertEquals(VaultImport.Verdict.UNDECIDED, plan.entries.single().verdict)
    }

    @Test
    fun `the copy dated later wins, and the one it replaced is kept`() {
        val mine = candidate("here", "Bank", "bank.example", "me", "march").copy(updatedAt = MARCH)
        val document = VaultDocument(items = listOf(mine))
        val theirs = candidate("a", "Bank", "https://bank.example", "me", "june").copy(updatedAt = JUNE)

        val plan = VaultImport.plan(document, read(theirs))
        assertEquals(VaultImport.Verdict.REPLACES, plan.entries.single().verdict)
        // Ticked: the evidence is on the screen and the password it displaces is recoverable.
        assertTrue("a" in plan.defaultSelection)

        val outcome = VaultImport.apply(document, plan, plan.defaultSelection, now)
        val item = outcome.document.live.single()
        assertEquals("june", item.secret)
        assertEquals(listOf("march"), item.history.map { it.secret })
        assertEquals(1, outcome.updated)
    }

    @Test
    fun `the copy dated earlier is filed as a previous password, and changes nothing else`() {
        val mine = candidate("here", "Bank", "bank.example", "me", "june").copy(updatedAt = JUNE)
        val document = VaultDocument(items = listOf(mine))
        // The browser that has not been opened since March still holds what it was then.
        val theirs = candidate("a", "Bank", "https://bank.example", "me", "march").copy(updatedAt = MARCH)

        val plan = VaultImport.plan(document, read(theirs))
        assertEquals(VaultImport.Verdict.PREVIOUS, plan.entries.single().verdict)
        assertTrue("a" in plan.defaultSelection)

        val outcome = VaultImport.apply(document, plan, plan.defaultSelection, now)
        val item = outcome.document.live.single()
        assertEquals("june", item.secret)
        assertEquals(listOf("march"), item.history.map { it.secret })
        assertEquals(1, outcome.recorded)
        assertEquals(0, outcome.updated)
        // The password did not change today, and the audit reads this field to decide what is old.
        assertEquals(JUNE, item.updatedAt)
    }

    @Test
    fun `a password this vault already replaced is recognised as the older one, undated`() {
        val mine = candidate("here", "Bank", "bank.example", "me", "new").copy(
            history = listOf(VaultSecretVersion("old", MARCH))
        )
        val document = VaultDocument(items = listOf(mine))

        val plan = VaultImport.plan(
            document,
            read(candidate("a", "Bank", "https://bank.example", "me", "old").copy(updatedAt = 0))
        )

        // Nothing to do: it is already on the record as a previous password.
        assertEquals(VaultImport.Verdict.ALREADY_HERE, plan.entries.single().verdict)
    }

    @Test
    fun `an import that knows this vault's password as an old one is the newer copy, undated`() {
        val document = VaultDocument(items = listOf(candidate("here", "Bank", "bank.example", "me", "old")))
        val theirs = candidate("a", "Bank", "https://bank.example", "me", "new").copy(
            updatedAt = 0,
            history = listOf(VaultSecretVersion("old", MARCH))
        )

        val plan = VaultImport.plan(document, read(theirs))

        assertEquals(VaultImport.Verdict.REPLACES, plan.entries.single().verdict)
    }

    @Test
    fun `the same stale export imported twice does not stack duplicate history`() {
        val mine = candidate("here", "Bank", "bank.example", "me", "june").copy(updatedAt = JUNE)
        val document = VaultDocument(items = listOf(mine))
        val stale = candidate("a", "Bank", "https://bank.example", "me", "march").copy(updatedAt = MARCH)

        val first = VaultImport.apply(
            document,
            VaultImport.plan(document, read(stale)),
            setOf("a"),
            now
        ).document

        val again = VaultImport.plan(first, read(stale.copy(id = "b")))
        assertEquals(VaultImport.Verdict.ALREADY_HERE, again.entries.single().verdict)
        val outcome = VaultImport.apply(first, again, setOf("b"), now)
        assertEquals(1, outcome.document.live.single().history.size)
    }

    @Test
    fun `a transfer bringing a passkey for an account held as a password adds it`() {
        val document = VaultDocument(items = listOf(candidate("here", "Bank", "bank.example", "me", "hunter2")))
        val theirs = candidate("a", "Bank", "https://bank.example", "me", "hunter2").copy(
            passkey = VaultPasskey(
                credentialId = "Y3JlZA",
                rpId = "bank.example",
                privateKey = "cHJpdmF0ZQ",
                publicKey = "cHVibGlj"
            ),
            totp = TotpConfig(secret = "JBSWY3DPEHPK3PXP")
        )

        val plan = VaultImport.plan(document, read(theirs))
        assertEquals(VaultImport.Verdict.ADDS, plan.entries.single().verdict)
        assertTrue("a" in plan.defaultSelection)

        val item = VaultImport.apply(document, plan, setOf("a"), now).document.live.single()
        assertNotNull(item.passkey)
        assertNotNull(item.totp)
        assertEquals("hunter2", item.secret)
        // Untouched: nothing about the password changed, so nothing about its age did either.
        assertTrue(item.history.isEmpty())
    }

    @Test
    fun `taking a change keeps the item and its history, not the row from the file`() {
        val existing = candidate("here", "Bank", "https://bank.example", "me", "old").copy(
            tags = listOf("money"),
            fields = listOf(VaultField("memorable word", "badger", secret = true)),
            favourite = true
        )
        val document = VaultDocument(items = listOf(existing))
        val plan = VaultImport.plan(
            document,
            read(candidate("a", "Bank", "https://bank.example", "me", "new"))
        )

        val outcome = VaultImport.apply(document, plan, setOf("a"), now + 1000)

        assertEquals(1, outcome.updated)
        val item = outcome.document.live.single()
        // Same item, with the new password — everything the household did to it here survives.
        assertEquals("here", item.id)
        assertEquals("new", item.secret)
        assertEquals(listOf("money"), item.tags)
        assertTrue(item.favourite)
        assertEquals("badger", item.fields.single().value)
        // And the password it replaced is kept, by the same rule every other change goes through.
        assertEquals(listOf("old"), item.history.map { it.secret })
    }

    @Test
    fun `a change fills in what the vault's copy was missing`() {
        val existing = VaultItem(id = "here", title = "Bank", secret = "old", createdAt = now, updatedAt = now)
        val document = VaultDocument(items = listOf(existing))
        val incoming = candidate("a", "Bank", "https://bank.example", "", "new")
            .copy(totp = TotpConfig(secret = "JBSWY3DPEHPK3PXP"), note = "the one with the reader")

        val plan = VaultImport.plan(document, read(incoming))
        val item = VaultImport.apply(document, plan, setOf("a"), now).document.live.single()

        assertEquals("https://bank.example", item.url)
        assertEquals("the one with the reader", item.note)
        assertNotNull(item.totp)
    }

    @Test
    fun `two logins at one site are two items`() {
        val document = VaultDocument(items = listOf(candidate("here", "Bank", "bank.example", "me", "mine")))

        val plan = VaultImport.plan(
            document,
            read(candidate("a", "Bank", "https://bank.example", "someone-else", "theirs"))
        )

        assertEquals(1, plan.newCount)
        assertEquals(2, VaultImport.apply(document, plan, setOf("a"), now).document.live.size)
    }

    @Test
    fun `a mirrored credential is never what a row matches`() {
        // Finance's token, filed under a ref rather than typed in. A CSV row that happened to carry
        // the same title must arrive as a new item, not as an offer to overwrite a bank token.
        val managed = VaultItem(
            id = "managed",
            title = "Bank",
            secret = "access-token",
            ref = "finance/bank",
            managedBy = "finance",
            createdAt = now,
            updatedAt = now
        )
        val document = VaultDocument(items = listOf(managed))

        val plan = VaultImport.plan(document, read(candidate("a", "Bank", "", "", "hunter2")))

        assertEquals(1, plan.newCount)
        val outcome = VaultImport.apply(document, plan, setOf("a"), now)
        assertEquals("access-token", outcome.document.item("managed")?.secret)
    }

    @Test
    fun `an item deleted here is not resurrected by a row that matches it`() {
        val document = VaultDocument(
            items = listOf(candidate("here", "Old forum", "forum.example", "me", "letmein").tombstone(now))
        )

        val row = candidate("a", "Old forum", "https://forum.example", "me", "letmein")
        val plan = VaultImport.plan(document, read(row))

        // The tombstone is not a match — what comes back is a new item, deliberately ticked, which
        // is the honest answer: the household is being shown a row and choosing to bring it back.
        assertEquals(1, plan.newCount)
    }

    @Test
    fun `nothing happens to an entry that was not ticked`() {
        val document = VaultDocument(items = listOf(candidate("here", "Bank", "bank.example", "me", "old")))
        val plan = VaultImport.plan(document, read(candidate("a", "Bank", "https://bank.example", "me", "new")))

        val outcome = VaultImport.apply(document, plan, emptySet(), now)

        assertEquals(0, outcome.changed)
        assertEquals("old", outcome.document.live.single().secret)
    }

    @Test
    fun `a note with no address is matched on its title`() {
        val document = VaultDocument(
            items = listOf(
                VaultItem(
                    id = "here",
                    kind = VaultItemKind.NOTE,
                    title = "Safe",
                    note = "1234",
                    createdAt = now,
                    updatedAt = now
                )
            )
        )

        val plan = VaultImport.plan(
            document,
            read(VaultItem(id = "a", kind = VaultItemKind.NOTE, title = "safe", note = "1234"))
        )

        assertEquals(1, plan.alreadyHereCount)
    }

    @Test
    fun `an import into a vault with a passkey does not walk the format version back`() {
        val document = VaultDocument(
            version = VaultDocument.DOCUMENT_VERSION,
            items = listOf(candidate("here", "Bank", "bank.example", "me", "old"))
        )
        val plan = VaultImport.plan(document, read(candidate("a", "Shop", "shop.example", "me", "x")))

        val outcome = VaultImport.apply(document, plan, plan.defaultSelection, now)

        assertEquals(VaultDocument.DOCUMENT_VERSION, outcome.document.version)
    }
}
