package com.operations.vaultkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the vault remembers about a password that was replaced — and, just as much, what it refuses
 * to remember.
 */
class VaultHistoryTest {

    private val seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    private fun login(id: String = "a", secret: String, history: List<VaultSecretVersion> = emptyList()) =
        VaultItem(id = id, title = "Bank", secret = secret, history = history, createdAt = 1, updatedAt = 1)

    // --- Recording -------------------------------------------------------------------------------

    @Test
    fun `changing a password keeps the one it replaced`() {
        val before = VaultDocument.EMPTY.upsert(login(secret = "old-one"), now = 10)

        val after = before.upsert(before.item("a")!!.copy(secret = "new-one"), now = 20)

        val item = after.item("a")!!
        assertEquals("new-one", item.secret)
        assertEquals(1, item.history.size)
        assertEquals("old-one", item.history.single().secret)
        assertEquals(20L, item.history.single().replacedAt)
    }

    @Test
    fun `saving an item without touching its password records nothing`() {
        val before = VaultDocument.EMPTY.upsert(login(secret = "same"), now = 10)

        val after = before.upsert(before.item("a")!!.copy(title = "The joint account"), now = 20)

        assertEquals("The joint account", after.item("a")!!.title)
        assertTrue("editing a title is not replacing a password", after.item("a")!!.history.isEmpty())
    }

    @Test
    fun `a brand-new item has replaced nothing`() {
        val document = VaultDocument.EMPTY.upsert(login(secret = "first"), now = 10)

        assertTrue(document.item("a")!!.history.isEmpty())
    }

    @Test
    fun `filling in a blank password is not a replacement`() {
        // The stub somebody started last year and never finished. Filing "" as a password they once
        // used would put a meaningless row in the one list that is supposed to be worth reading.
        val before = VaultDocument.EMPTY.upsert(login(secret = ""), now = 10)

        val after = before.upsert(before.item("a")!!.copy(secret = "finally"), now = 20)

        assertTrue(after.item("a")!!.history.isEmpty())
    }

    @Test
    fun `history is newest first and stops at the limit`() {
        var document = VaultDocument.EMPTY.upsert(login(secret = "p0"), now = 0)
        for (i in 1..15) {
            document = document.upsert(document.item("a")!!.copy(secret = "p$i"), now = i.toLong())
        }

        val history = document.item("a")!!.history
        assertEquals(VaultItem.HISTORY_LIMIT, history.size)
        assertEquals("p14", history.first().secret)
        assertEquals("p5", history.last().secret)
        assertEquals("p15", document.item("a")!!.secret)
    }

    @Test
    fun `a mirrored credential never accumulates one`() {
        // A rotated access token is dead the moment it rotates, and these rotate on a schedule
        // rather than when a person decides something — so a history of them would be an unbounded
        // pile of plaintext that opens nothing.
        val ref = SecretRef("finance", "usaa", "access-token")
        val managed = VaultItem(
            id = "m",
            title = "Finance — USAA access token",
            secret = "token-1",
            ref = ref.format(),
            managedBy = "finance",
            createdAt = 1,
            updatedAt = 1
        )
        val before = VaultDocument.EMPTY.upsert(managed, now = 10)

        val after = before.upsert(before.item("m")!!.copy(secret = "token-2"), now = 20)

        assertEquals("token-2", after.item("m")!!.secret)
        assertTrue(after.item("m")!!.history.isEmpty())
    }

    @Test
    fun `history handed to a mirrored credential is dropped rather than stored`() {
        val smuggled = VaultItem(
            id = "m",
            secret = "token",
            ref = "finance/usaa/access-token",
            managedBy = "finance",
            history = listOf(VaultSecretVersion("older-token", 5))
        )

        val document = VaultDocument.EMPTY.upsert(smuggled, now = 10)

        assertTrue(document.item("m")!!.history.isEmpty())
    }

    @Test
    fun `deleting takes the previous passwords and the second factor with it`() {
        val item = login(secret = "current", history = listOf(VaultSecretVersion("previous", 5)))
            .copy(totp = TotpConfig(secret = seed))
        val document = VaultDocument.EMPTY.upsert(item, now = 10)

        val tombstone = document.delete("a", now = 30).items.single()

        assertTrue(tombstone.isDeleted)
        assertEquals("", tombstone.secret)
        assertTrue("a tombstone holds no password anybody ever used", tombstone.history.isEmpty())
        assertNull(tombstone.totp)
    }

    // --- Never searched --------------------------------------------------------------------------

    @Test
    fun `neither a previous password nor a second-factor seed is ever matched`() {
        val item = login(secret = "current", history = listOf(VaultSecretVersion("elderflower", 5)))
            .copy(totp = TotpConfig(secret = seed, issuer = "Monzo"))

        assertTrue(item.matches("bank"))
        // The rule the search box states, extended to the two places a secret now also lives.
        assertFalse("a password used last year is still a password", item.matches("elderflower"))
        assertFalse("a seed is the one secret whose theft is silent", item.matches(seed))
        assertFalse(item.matches("GEZDGNBV"))
    }

    // --- The audit -------------------------------------------------------------------------------

    @Test
    fun `an item that exists for its second factor is not an abandoned stub`() {
        val codesOnly = VaultItem(
            id = "a",
            title = "Work VPN",
            secret = "",
            totp = TotpConfig(secret = seed),
            updatedAt = 1
        )
        val actualStub = VaultItem(id = "b", title = "Started and forgotten", secret = "", updatedAt = 1)

        val report = VaultAudit.run(listOf(codesOnly, actualStub), now = 2)

        assertEquals(1, report.count(VaultAudit.Kind.NO_SECRET))
        assertEquals("b", report.findings.single { it.kind == VaultAudit.Kind.NO_SECRET }.itemId)
    }

    @Test
    fun `a seed is never compared against a password for reuse`() {
        // The reuse check groups on the password. A seed that happened to match one would be a
        // finding nobody could act on, about two things that are not the same kind of secret.
        val one = VaultItem(id = "a", title = "One", secret = seed, updatedAt = 1)
        val two = VaultItem(id = "b", title = "Two", secret = "different", totp = TotpConfig(seed), updatedAt = 1)

        val report = VaultAudit.run(listOf(one, two), now = 2)

        assertEquals(0, report.count(VaultAudit.Kind.REUSED))
    }

    // --- The format version ----------------------------------------------------------------------

    @Test
    fun `a vault that uses neither feature stays readable by the older build`() {
        val document = VaultDocument.EMPTY
            .upsert(login(secret = "plain"), now = 10)
            .upsert(login(id = "b", secret = "also plain"), now = 20)

        assertEquals(VaultDocument.BASELINE_VERSION, document.version)
    }

    @Test
    fun `a second factor or a replaced password moves the document forward`() {
        val withTotp = VaultDocument.EMPTY
            .upsert(login(secret = "p").copy(totp = TotpConfig(secret = seed)), now = 10)
        assertEquals(VaultDocument.DOCUMENT_VERSION, withTotp.version)

        val changed = VaultDocument.EMPTY.upsert(login(secret = "old"), now = 10)
        assertEquals(VaultDocument.BASELINE_VERSION, changed.version)
        val withHistory = changed.upsert(changed.item("a")!!.copy(secret = "new"), now = 20)
        assertEquals(VaultDocument.DOCUMENT_VERSION, withHistory.version)
    }

    @Test
    fun `the version never walks backwards`() {
        // Removing the last second factor does not hand the file back to a build that would strip
        // the next one, and a document from a future build is not quietly demoted to this one.
        val withTotp = VaultDocument.EMPTY
            .upsert(login(secret = "p").copy(totp = TotpConfig(secret = seed)), now = 10)

        val without = withTotp.upsert(withTotp.item("a")!!.copy(totp = null), now = 20)
        assertEquals(VaultDocument.DOCUMENT_VERSION, without.version)

        val fromTheFuture = VaultDocument(version = 9, items = listOf(login(secret = "p")))
        assertEquals(9, fromTheFuture.stamped().version)
        assertEquals(9, fromTheFuture.upsert(login(id = "b", secret = "q"), now = 30).version)
    }

    @Test
    fun `a merge stamps what the winning items need`() {
        val mine = VaultDocument.EMPTY.upsert(login(secret = "mine"), now = 10)
        val archive = VaultDocument.EMPTY.upsert(
            login(id = "b", secret = "theirs").copy(totp = TotpConfig(secret = seed)),
            now = 20
        )

        val merged = VaultMerge.merge(mine, archive, now = 30)

        assertEquals(1, merged.added)
        assertEquals(VaultDocument.DOCUMENT_VERSION, merged.document.version)
        assertNotNull(merged.document.item("b")!!.totp)
    }

    // --- On disk ---------------------------------------------------------------------------------

    @Test
    fun `both survive a JSON round trip`() {
        val document = VaultDocument(
            version = VaultDocument.DOCUMENT_VERSION,
            items = listOf(
                login(secret = "current", history = listOf(VaultSecretVersion("previous", 5)))
                    .copy(
                        totp = TotpConfig(
                            secret = seed,
                            algorithm = TotpAlgorithm.SHA256,
                            digits = 8,
                            periodSeconds = 60,
                            issuer = "Monzo",
                            account = "me@example.com"
                        )
                    )
            ),
            updatedAt = 7
        )

        assertEquals(document, VaultJson.decode(VaultJson.encode(document)))
    }

    @Test
    fun `a document written before either existed comes back usable`() {
        val parsed = VaultJson.decode(
            """{"version":1,"items":[{"id":"a","title":"Wifi","secret":"hunter2"}]}""".toByteArray()
        )

        assertNotNull(parsed)
        val item = parsed!!.items.single()
        assertNull(item.totp)
        assertEquals(emptyList<VaultSecretVersion>(), item.history)
    }

    @Test
    fun `a stored seed that cannot work is dropped on the way in`() {
        // Better an item with no second factor than one that shows six confident, wrong digits.
        val parsed = VaultJson.decode(
            """{"version":2,"items":[{"id":"a","totp":{"secret":"!!!!","digits":6,"periodSeconds":30}}]}"""
                .toByteArray()
        )

        assertNotNull(parsed)
        assertNull(parsed!!.items.single().totp)
    }

    @Test
    fun `a seed stored without its settings gets the ones every authenticator assumes`() {
        val parsed = VaultJson.decode(
            """{"version":2,"items":[{"id":"a","totp":{"secret":"$seed"}}]}""".toByteArray()
        )

        val totp = parsed!!.items.single().totp!!
        assertEquals(TotpAlgorithm.SHA1, totp.algorithm)
        assertEquals(6, totp.digits)
        assertEquals(30, totp.periodSeconds)
        assertNotNull(Totp.code(totp, 59_000L))
    }

    @Test
    fun `a history row with no password in it is not carried`() {
        val parsed = VaultJson.decode(
            """{"version":2,"items":[{"id":"a","secret":"x","history":[{"secret":"","replacedAt":1},{"replacedAt":2}]}]}"""
                .toByteArray()
        )

        assertTrue(parsed!!.items.single().history.isEmpty())
    }
}
