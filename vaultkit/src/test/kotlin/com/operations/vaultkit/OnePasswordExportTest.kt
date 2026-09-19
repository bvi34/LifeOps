package com.operations.vaultkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `.1pux`, read from documents shaped like the real export.
 *
 * The CSV half of leaving 1Password is easy and lossy. This is the half with the household's card
 * numbers, licence keys, custom fields and password history in it, so the tests are mostly about
 * what survives the trip — and about the two kinds of value that must *not* arrive as ordinary
 * text: a concealed field, which has to stay masked, and a second factor, which has to arrive as a
 * seed that makes codes rather than as a string nobody can use.
 */
class OnePasswordExportTest {

    private val now = 1_700_000_000_000L

    private fun read(json: String): VaultImport.Read? {
        var next = 0
        return OnePasswordExport.read(json, now) { "id-${next++}" }
    }

    private fun export(vault: String = "Personal", items: String) = """
        {"accounts":[{"attrs":{"name":"Household"},"vaults":[
          {"attrs":{"name":"$vault"},"items":[$items]}
        ]}]}
    """.trimIndent()

    private val login = """
        {"item":{
          "uuid":"abc","favIndex":1,"createdAt":1500000000,"updatedAt":1550000000,
          "state":"active","categoryUuid":"001",
          "overview":{"title":"Bank","url":"https://bank.example",
            "urls":[{"label":"website","url":"https://bank.example"},
                    {"label":"app","url":"https://app.bank.example"}],
            "tags":["money"]},
          "details":{
            "loginFields":[
              {"designation":"username","value":"me@example.com"},
              {"designation":"password","value":"hunter2"}],
            "notesPlain":"the one with the card reader",
            "passwordHistory":[{"value":"older","time":1400000000},{"value":"oldest","time":1300000000}],
            "sections":[{"title":"Security","fields":[
              {"title":"one-time password","value":{"totp":"otpauth://totp/Bank:me?secret=JBSWY3DPEHPK3PXP&issuer=Bank"}},
              {"title":"memorable word","value":{"concealed":"badger"}},
              {"title":"branch","value":{"string":"Hexham"}}]}]}}}
    """.trimIndent()

    @Test
    fun `a login arrives whole`() {
        val read = read(export(items = login))!!

        assertEquals(VaultImport.Format.ONEPASSWORD_1PUX, read.format)
        val item = read.items.single()
        assertEquals(VaultItemKind.LOGIN, item.kind)
        assertEquals("Bank", item.title)
        assertEquals("me@example.com", item.username)
        assertEquals("hunter2", item.secret)
        assertEquals("https://bank.example", item.url)
        assertEquals("the one with the card reader", item.note)
        assertEquals(listOf("money"), item.tags)
        assertTrue(item.favourite)
        assertEquals(1_550_000_000_000L, item.updatedAt)
        assertEquals(1_500_000_000_000L, item.createdAt)
    }

    @Test
    fun `the second factor arrives as a seed rather than as a field`() {
        val item = read(export(items = login))!!.items.single()

        assertEquals("JBSWY3DPEHPK3PXP", item.totp?.secret)
        assertEquals("Bank", item.totp?.issuer)
        assertTrue(item.fields.none { it.value.startsWith("otpauth://") })
        assertNotNull(Totp.code(item.totp!!, now))
    }

    @Test
    fun `a concealed field stays concealed`() {
        val item = read(export(items = login))!!.items.single()

        val word = item.fields.single { it.name.endsWith("memorable word") }
        assertEquals("badger", word.value)
        assertTrue(word.secret)

        val branch = item.fields.single { it.name.endsWith("branch") }
        assertFalse(branch.secret)
        // The section it lived in is kept in the name: `notes` under `Recovery` and `notes` under
        // `Billing` are two different things and a flattened item would show two fields called
        // `notes`.
        assertEquals("Security · branch", branch.name)
    }

    @Test
    fun `the second address is kept, because autofill only matches the first`() {
        val item = read(export(items = login))!!.items.single()

        assertEquals("https://app.bank.example", item.fields.single { it.name == "Website 2" }.value)
    }

    @Test
    fun `the passwords it used to have come with it, newest first`() {
        val item = read(export(items = login))!!.items.single()

        assertEquals(listOf("older", "oldest"), item.history.map { it.secret })
        assertEquals(1_400_000_000_000L, item.history[0].replacedAt)
    }

    @Test
    fun `a card keeps its number as the secret and its expiry as a field`() {
        val card = """
            {"item":{"uuid":"c","categoryUuid":"002","state":"active",
              "overview":{"title":"Visa"},
              "details":{"loginFields":[],"notesPlain":"","sections":[{"title":"","fields":[
                {"title":"number","value":{"creditCardNumber":"4111111111111111"}},
                {"title":"expiry date","value":{"monthYear":202601}},
                {"title":"verification number","value":{"concealed":"123"}},
                {"title":"type","value":{"creditCardType":"visa"}}]}]}}}
        """.trimIndent()

        val item = read(export(items = card))!!.items.single()

        assertEquals(VaultItemKind.CARD, item.kind)
        assertEquals("4111111111111111", item.secret)
        assertEquals("2026-01", item.fields.single { it.name == "expiry date" }.value)
        assertTrue(item.fields.single { it.name == "verification number" }.secret)
    }

    @Test
    fun `a wifi item takes the concealed password as its secret`() {
        val router = """
            {"item":{"uuid":"w","categoryUuid":"109","state":"active",
              "overview":{"title":"Home router"},
              "details":{"loginFields":[],"notesPlain":"","sections":[{"title":"","fields":[
                {"title":"network name","value":{"string":"Hexham"}},
                {"title":"wireless network password","value":{"concealed":"correct horse"}}]}]}}}
        """.trimIndent()

        val item = read(export(items = router))!!.items.single()

        assertEquals(VaultItemKind.WIFI, item.kind)
        assertEquals("correct horse", item.secret)
    }

    @Test
    fun `a secure note keeps its text and none of it becomes a password`() {
        val note = """
            {"item":{"uuid":"n","categoryUuid":"003","state":"active",
              "overview":{"title":"Safe"},
              "details":{"loginFields":[],"notesPlain":"the code is 1234","sections":[]}}}
        """.trimIndent()

        val item = read(export(items = note))!!.items.single()

        assertEquals(VaultItemKind.NOTE, item.kind)
        assertEquals("", item.secret)
        assertEquals("the code is 1234", item.note)
    }

    @Test
    fun `the trash and the archive stay where they were put`() {
        val filed = """
            {"item":{"uuid":"t","categoryUuid":"001","state":"trashed","overview":{"title":"Gone"},
              "details":{"loginFields":[{"designation":"password","value":"x"}]}}},
            {"item":{"uuid":"a","categoryUuid":"001","state":"archived","overview":{"title":"Filed"},
              "details":{"loginFields":[{"designation":"password","value":"y"}]}}}
        """.trimIndent()

        val read = read(export(items = filed))!!

        assertTrue(read.items.isEmpty())
        assertEquals(setOf("Gone", "Filed"), read.skipped.map { it.what }.toSet())
    }

    @Test
    fun `an attachment is left behind and said so`() {
        val document = """
            {"item":{"uuid":"d","categoryUuid":"006","state":"active",
              "overview":{"title":"Passport scan"},
              "details":{"loginFields":[],"notesPlain":"issued 2019",
                "documentAttributes":{"fileName":"passport.pdf","documentId":"x"}}}}
        """.trimIndent()

        val read = read(export(items = document))!!

        assertEquals("issued 2019", read.items.single().note)
        assertEquals("passport.pdf", read.skipped.single().what)
    }

    @Test
    fun `the vault an item came from becomes a tag, unless it is the one everybody has`() {
        assertEquals(
            listOf("money", "Shared"),
            read(export(vault = "Shared", items = login))!!.items.single().tags
        )
        assertEquals(listOf("money"), read(export(items = login))!!.items.single().tags)
    }

    @Test
    fun `an item with nothing in it but a name is reported rather than imported`() {
        val empty = """
            {"item":{"uuid":"e","categoryUuid":"001","state":"active","overview":{"title":"Stub"},
              "details":{"loginFields":[],"notesPlain":"","sections":[]}}}
        """.trimIndent()

        val read = read(export(items = empty))!!

        assertTrue(read.items.isEmpty())
        assertEquals("Stub", read.skipped.single().what)
    }

    @Test
    fun `a seed that will not decode is kept as a field rather than dropped`() {
        val broken = """
            {"item":{"uuid":"b","categoryUuid":"001","state":"active","overview":{"title":"Odd"},
              "details":{"loginFields":[{"designation":"password","value":"x"}],
                "sections":[{"title":"","fields":[
                  {"title":"one-time password","value":{"totp":"this is not base 32 (0189!)"}}]}]}}}
        """.trimIndent()

        val item = read(export(items = broken))!!.items.single()

        assertNull(item.totp)
        assertEquals("this is not base 32 (0189!)", item.fields.single { it.name == "one-time password" }.value)
    }

    @Test
    fun `something that is not an export is not read as one`() {
        assertNull(read("""{"transactions":[{"amount":12.4}]}"""))
        assertNull(read("not json at all"))
    }

    @Test
    fun `a half-written item does not take the other four hundred down with it`() {
        val items = """
            {"item":{"uuid":"broken"}},
            $login
        """.trimIndent()

        val read = read(export(items = items))!!

        assertEquals("Bank", read.items.single().title)
        assertEquals(1, read.skipped.size)
    }
}
