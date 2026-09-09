package com.operations.vaultkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultDocumentTest {

    private fun item(
        id: String,
        title: String = id,
        secret: String = "s3cret-$id",
        ref: String? = null
    ) = VaultItem(id = id, title = title, secret = secret, ref = ref, createdAt = 1, updatedAt = 1)

    @Test
    fun `upsert adds then replaces, and never duplicates an id`() {
        val one = VaultDocument.EMPTY.upsert(item("a", title = "Bank"), now = 10)
        val two = one.upsert(item("a", title = "Bank of Somewhere"), now = 20)

        assertEquals(1, two.items.size)
        assertEquals("Bank of Somewhere", two.item("a")?.title)
        assertEquals(20, two.updatedAt)
    }

    @Test
    fun `deleting leaves a tombstone that holds no secret`() {
        val document = VaultDocument.EMPTY.upsert(item("a", secret = "hunter2"), now = 10)

        val after = document.delete("a", now = 30)

        assertNull("a deleted item is not readable", after.item("a"))
        assertTrue(after.live.isEmpty())
        val tombstone = after.items.single()
        assertTrue(tombstone.isDeleted)
        assertEquals("", tombstone.secret)
        assertEquals("", tombstone.title)
        assertEquals(30L, tombstone.deletedAt)
    }

    @Test
    fun `deleting something that was never there changes nothing`() {
        val document = VaultDocument.EMPTY.upsert(item("a"), now = 10)

        assertEquals(document, document.delete("nope", now = 30))
    }

    @Test
    fun `a managed item is found by its ref, and a deleted one is not`() {
        val ref = SecretRef("finance", "usaa", "access-token")
        val document = VaultDocument.EMPTY.upsert(item("a", ref = ref.format()), now = 10)

        assertNotNull(document.managed(ref))
        assertNull(document.managed(SecretRef("finance", "usaa", "client-secret")))
        assertNull(document.delete("a", now = 20).managed(ref))
    }

    @Test
    fun `matching looks at everything except the secret`() {
        val row = VaultItem(
            id = "a",
            title = "Allotment gate",
            username = "brenden",
            secret = "elderflower",
            url = "https://gate.example",
            note = "the one by the shed",
            tags = listOf("outdoors"),
            fields = listOf(
                VaultField("Panel", "north"),
                VaultField("Backup code", "elderflower-2", secret = true)
            )
        )

        assertTrue(row.matches("allot"))
        assertTrue(row.matches("BRENDEN"))
        assertTrue(row.matches("shed"))
        assertTrue(row.matches("outdoors"))
        assertTrue(row.matches("north"))
        assertTrue(row.matches("gate.example"))
        assertTrue("an empty query matches everything", row.matches("  "))

        // The whole point: typing a password into a search box finds nothing, so nobody learns to.
        assertFalse(row.matches("elderflower"))
        assertFalse(row.matches("elderflower-2"))
    }

    @Test
    fun `the document survives a JSON round trip`() {
        val document = VaultDocument(
            items = listOf(
                VaultItem(
                    id = "a",
                    kind = VaultItemKind.CARD,
                    title = "Credit union card",
                    username = "",
                    secret = "4111111111111111",
                    fields = listOf(VaultField("CVV", "123", secret = true), VaultField("Expires", "04/29")),
                    tags = listOf("cards"),
                    createdAt = 5,
                    updatedAt = 6
                ),
                item("b", ref = "finance/usaa/access-token").copy(managedBy = "finance")
            ),
            updatedAt = 7
        )

        val parsed = VaultJson.decode(VaultJson.encode(document))

        assertEquals(document, parsed)
    }

    @Test
    fun `rubbish and half-written JSON decode to null rather than to an empty vault`() {
        assertNull(VaultJson.decode("not json".toByteArray()))
        assertNull(VaultJson.decode(ByteArray(0)))
        assertNull(VaultJson.decode("""{"items":[{"id":"a"}""".toByteArray()))
    }

    @Test
    fun `JSON missing the fields Gson would leave null comes back usable`() {
        // What a document written by an older build, or hand-edited during a recovery, looks like.
        val parsed = VaultJson.decode("""{"version":1,"items":[{"id":"a","title":"Wifi"}]}""".toByteArray())

        assertNotNull(parsed)
        val item = parsed!!.items.single()
        assertEquals("", item.secret)
        assertEquals(emptyList<VaultField>(), item.fields)
        assertEquals(emptyList<String>(), item.tags)
        assertTrue(item.matches("wifi"))
    }

    @Test
    fun `an empty vault encodes to something small and decodes back`() {
        val bytes = VaultJson.encode(VaultDocument.EMPTY)

        assertTrue(bytes.size < 200)
        assertEquals(VaultDocument.EMPTY, VaultJson.decode(bytes))
    }
}
