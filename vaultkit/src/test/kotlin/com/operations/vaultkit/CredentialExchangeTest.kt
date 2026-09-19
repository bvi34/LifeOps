package com.operations.vaultkit

import com.google.gson.JsonParser
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payload of a Credential Exchange transfer, read as the other manager sends it.
 *
 * The centre of this file is the passkey test, and it is the one worth reading. CXF carries a
 * passkey's private key and not its public one, so the import recomputes the public half — and the
 * test does not stop at "a passkey arrived". It **signs with the imported credential and verifies
 * the signature against the public key that was computed**, which is the same check a relying
 * party's server runs. An imported passkey that cannot do that is an account nobody can get into,
 * and there is no way to discover it by reading the code.
 */
class CredentialExchangeTest {

    private val now = 1_700_000_000_000L

    private fun read(json: String): VaultImport.Read? {
        var next = 0
        return CredentialExchange.read(json, now) { "id-${next++}" }
    }

    private fun payload(
        items: String,
        collections: String = "",
        exporter: String = "1Password"
    ) = """
        {"version":{"major":1,"minor":0},
         "exporterRpId":"1password.com","exporterDisplayName":"$exporter",
         "timestamp":1700000000,
         "accounts":[{"id":"YWNjdA","username":"household","email":"me@example.com",
           "collections":[$collections],
           "items":[$items]}]}
    """.trimIndent()

    private val login = """
        {"id":"aXRlbTE","title":"Bank","subtitle":"me@example.com","favorite":true,
         "creationAt":1500000000,"modifiedAt":1550000000,
         "tags":["money"],
         "scope":{"urls":["https://bank.example","https://app.bank.example"],
                  "androidApps":[{"bundleId":"com.bank.app"}]},
         "credentials":[
           {"type":"basic-auth",
            "username":{"fieldType":"string","value":"me@example.com"},
            "password":{"fieldType":"concealed-string","value":"hunter2"}},
           {"type":"totp","secret":"JBSWY3DPEHPK3PXP","period":30,"digits":6,
            "algorithm":"sha1","issuer":"Bank","username":"me@example.com"},
           {"type":"custom-fields","label":"Security",
            "fields":[{"fieldType":"string","label":"branch","value":"Hexham"},
                      {"fieldType":"concealed-string","label":"memorable word","value":"badger"}]}]}
    """.trimIndent()

    @Test
    fun `a login arrives whole, and the exporter names itself`() {
        val read = read(payload(items = login))!!

        assertEquals(VaultImport.Format.CREDENTIAL_EXCHANGE, read.format)
        assertEquals("1Password", read.from)
        val item = read.items.single()
        assertEquals(VaultItemKind.LOGIN, item.kind)
        assertEquals("Bank", item.title)
        assertEquals("me@example.com", item.username)
        assertEquals("hunter2", item.secret)
        assertEquals("https://bank.example", item.url)
        assertTrue(item.favourite)
        assertEquals(1_550_000_000_000L, item.updatedAt)
        assertEquals(1_500_000_000_000L, item.createdAt)
    }

    @Test
    fun `the second factor arrives as a seed that makes codes`() {
        val item = read(payload(items = login))!!.items.single()

        assertEquals("JBSWY3DPEHPK3PXP", item.totp?.secret)
        assertEquals("Bank", item.totp?.issuer)
        assertNotNull(Totp.code(item.totp!!, now))
    }

    @Test
    fun `a concealed custom field stays concealed`() {
        val item = read(payload(items = login))!!.items.single()

        assertTrue(item.fields.single { it.name == "Security · memorable word" }.secret)
        assertFalse(item.fields.single { it.name == "Security · branch" }.secret)
    }

    @Test
    fun `the second address and the app it belongs to are kept`() {
        val item = read(payload(items = login))!!.items.single()

        assertEquals("https://app.bank.example", item.fields.single { it.name == "Website 2" }.value)
        assertEquals("com.bank.app", item.fields.single { it.name == "App" }.value)
    }

    @Test
    fun `a passkey arrives able to sign, and its signature verifies`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = generator.generateKeyPair()
        val privateKey = WebAuthn.base64Url(pair.private.encoded)

        val passkeyItem = """
            {"id":"aXRlbTI","title":"Bank","credentials":[
              {"type":"passkey","credentialId":"Y3JlZA","rpId":"bank.example",
               "username":"me@example.com","userDisplayName":"Me","userHandle":"aGFuZGxl",
               "key":"$privateKey"}]}
        """.trimIndent()

        val item = read(payload(items = passkeyItem))!!.items.single()
        val passkey = item.passkey!!

        assertEquals(VaultItemKind.PASSKEY, item.kind)
        assertEquals("bank.example", passkey.rpId)
        // The public half was not in the payload; it was recomputed. It must be the real one.
        assertEquals(WebAuthn.base64Url(pair.public.encoded), passkey.publicKey)
        assertTrue(passkey.isUsable)
        // And an item with a passkey is findable and fillable: the relying party is its address.
        assertEquals("bank.example", item.url)

        // The check a relying party's server runs: sign with the imported credential, then verify
        // against the public key this import computed rather than received.
        val clientData = WebAuthn.clientDataJson("webauthn.get", "Y2hhbGxlbmdl", "https://bank.example")
        val clientDataHash = MessageDigest.getInstance("SHA-256")
            .digest(clientData.toByteArray(Charsets.UTF_8))
        val assertionJson = Passkeys.assertion(passkey, clientData, clientDataHash)!!

        val inner = JsonParser.parseString(assertionJson).asJsonObject.getAsJsonObject("response")
        val authData = WebAuthn.fromBase64Url(inner.get("authenticatorData").asString)!!
        val signature = WebAuthn.fromBase64Url(inner.get("signature").asString)!!
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(Passkeys.publicKeyOf(passkey))
            update(authData)
            update(clientDataHash)
        }
        assertTrue("an imported passkey must sign for the site it came from", verifier.verify(signature))
    }

    @Test
    fun `a passkey on a curve this app cannot hold is reported rather than dropped silently`() {
        val broken = """
            {"id":"aXRlbTM","title":"Odd","credentials":[
              {"type":"passkey","credentialId":"Y3JlZA","rpId":"odd.example",
               "username":"me","userDisplayName":"Me","userHandle":"aGFuZGxl","key":"bm90LWEta2V5"}]}
        """.trimIndent()

        val read = read(payload(items = broken))!!

        assertTrue(read.items.isEmpty() || read.items.single().passkey == null)
        assertEquals("Odd", read.skipped.first().what)
    }

    @Test
    fun `a card keeps its number as the secret and its security code as a hidden field`() {
        val card = """
            {"id":"aXRlbTQ","title":"Visa","credentials":[
              {"type":"credit-card",
               "number":{"fieldType":"concealed-string","value":"4111111111111111"},
               "fullName":{"fieldType":"string","value":"A Household"},
               "verificationNumber":{"fieldType":"concealed-string","value":"123"},
               "expiryDate":{"fieldType":"year-month","value":"2026-01"}}]}
        """.trimIndent()

        val item = read(payload(items = card))!!.items.single()

        assertEquals(VaultItemKind.CARD, item.kind)
        assertEquals("4111111111111111", item.secret)
        assertTrue(item.fields.single { it.name == "Security code" }.secret)
        assertEquals("2026-01", item.fields.single { it.name == "Expires" }.value)
    }

    @Test
    fun `a wifi network, a note and an api key each keep the right thing as the secret`() {
        val items = """
            {"id":"dw","title":"Home","credentials":[{"type":"wifi",
              "ssid":{"fieldType":"string","value":"Hexham"},
              "passphrase":{"fieldType":"concealed-string","value":"correct horse"}}]},
            {"id":"bg","title":"Safe","credentials":[{"type":"note",
              "content":{"fieldType":"string","value":"the code is 1234"}}]},
            {"id":"YWs","title":"Weather","credentials":[{"type":"api-key",
              "key":{"fieldType":"concealed-string","value":"sk-123"},
              "url":{"fieldType":"string","value":"https://api.weather.example"}}]}
        """.trimIndent()

        val read = read(payload(items = items))!!
        val (wifi, note, key) = read.items

        assertEquals(VaultItemKind.WIFI, wifi.kind)
        assertEquals("correct horse", wifi.secret)
        assertEquals("Hexham", wifi.username)

        assertEquals(VaultItemKind.NOTE, note.kind)
        assertEquals("", note.secret)
        assertEquals("the code is 1234", note.note)

        assertEquals(VaultItemKind.API_KEY, key.kind)
        assertEquals("sk-123", key.secret)
        assertEquals("https://api.weather.example", key.url)
    }

    @Test
    fun `the other manager's folders become tags, nested ones as a path`() {
        val collections = """
            {"id":"Yw","title":"Family","items":[{"item":"aXRlbTE"}],
             "subCollections":[{"id":"YzI","title":"Utilities","items":[{"item":"aXRlbTE"}]}]}
        """.trimIndent()

        val item = read(payload(items = login, collections = collections))!!.items.single()

        assertEquals(listOf("money", "Family", "Family / Utilities"), item.tags)
    }

    @Test
    fun `a credential type this reader has never seen still arrives as its values`() {
        val future = """
            {"id":"ZnV0","title":"Something new","credentials":[
              {"type":"quantum-key","keyMaterial":{"fieldType":"concealed-string","value":"xyzzy"},
               "issuedBy":{"fieldType":"string","value":"Somebody"}}]}
        """.trimIndent()

        val item = read(payload(items = future))!!.items.single()

        assertEquals("xyzzy", item.fields.single { it.name == "Key material" }.value)
        assertTrue(item.fields.single { it.name == "Key material" }.secret)
        assertEquals("Somebody", item.fields.single { it.name == "Issued by" }.value)
    }

    @Test
    fun `a file is left behind and said so`() {
        val withFile = """
            {"id":"Zmw","title":"Passport","credentials":[
              {"type":"note","content":{"fieldType":"string","value":"issued 2019"}},
              {"type":"file","name":"passport.pdf","id":"Zg"}]}
        """.trimIndent()

        val read = read(payload(items = withFile))!!

        assertEquals("issued 2019", read.items.single().note)
        assertEquals("passport.pdf", read.skipped.single().what)
    }

    @Test
    fun `something that is not a credential exchange payload is not read as one`() {
        assertNull(read("""{"transactions":[]}"""))
        assertNull(read("not json"))
    }

    @Test
    fun `a malformed item does not take the rest of the transfer down with it`() {
        val items = """
            {"id":"Yng","title":"Broken","credentials":[]},
            $login
        """.trimIndent()

        val read = read(payload(items = items))!!

        assertEquals("Bank", read.items.single().title)
        assertEquals("Broken", read.skipped.single().what)
    }
}
