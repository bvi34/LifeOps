package com.operations.vaultkit

import com.google.gson.JsonParser
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A passkey, made and then used — with the check a relying party's server actually performs.
 *
 * The test that matters here is [a registration produces a credential its own assertion verifies
 * against]. Everything else in this file exists to make that one meaningful: a signature that
 * verifies against the wrong key, over the wrong bytes, or with the wrong structure around it is a
 * sign-in that fails on somebody's phone for a reason no log will explain.
 */
class PasskeyTest {

    private val creationJson = """
        {
          "rp": {"id": "bank.com", "name": "The Bank"},
          "user": {"id": "dXNlci0x", "name": "me@example.com", "displayName": "Me"},
          "challenge": "Y2hhbGxlbmdl",
          "pubKeyCredParams": [{"type": "public-key", "alg": -7}, {"type": "public-key", "alg": -257}]
        }
    """.trimIndent()

    private fun register(json: String = creationJson): Passkeys.Registration {
        val options = PasskeyRequest.parseCreation(json)!!
        val clientData = WebAuthn.clientDataJson("webauthn.create", options.challenge, ORIGIN)
        return Passkeys.register(
            options = options,
            clientDataJson = clientData,
            clientDataHash = sha256(clientData.toByteArray(Charsets.UTF_8)),
            now = 1_000L
        )!!
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun response(json: String) = JsonParser.parseString(json).asJsonObject

    // --- The one that proves it works ------------------------------------------------------------

    @Test
    fun `a registration produces a credential its own assertion verifies against`() {
        val registered = register()

        val challenge = "YW5vdGhlci1jaGFsbGVuZ2U"
        val clientData = WebAuthn.clientDataJson("webauthn.get", challenge, ORIGIN)
        val clientDataHash = sha256(clientData.toByteArray(Charsets.UTF_8))
        val assertionJson = Passkeys.assertion(registered.passkey, clientData, clientDataHash)!!

        val inner = response(assertionJson).getAsJsonObject("response")
        val authData = WebAuthn.fromBase64Url(inner.get("authenticatorData").asString)!!
        val signature = WebAuthn.fromBase64Url(inner.get("signature").asString)!!

        // Exactly what the relying party does: verify the signature over authData ‖ SHA-256 of the
        // client data, using the public key it was handed at registration.
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(Passkeys.publicKeyOf(registered.passkey))
            update(authData)
            update(clientDataHash)
        }
        assertTrue("the assertion must verify against the registered public key", verifier.verify(signature))
    }

    @Test
    fun `a signature does not verify over different client data`() {
        val registered = register()
        val clientData = WebAuthn.clientDataJson("webauthn.get", "Y2hhbGxlbmdl", ORIGIN)
        val assertionJson = Passkeys.assertion(
            registered.passkey,
            clientData,
            sha256(clientData.toByteArray(Charsets.UTF_8))
        )!!

        val inner = response(assertionJson).getAsJsonObject("response")
        val authData = WebAuthn.fromBase64Url(inner.get("authenticatorData").asString)!!
        val signature = WebAuthn.fromBase64Url(inner.get("signature").asString)!!

        // A replayed signature against a different challenge is the attack the whole protocol is
        // about, so the test that it fails is worth as much as the test that the right one passes.
        val tampered = WebAuthn.clientDataJson("webauthn.get", "ZGlmZmVyZW50", ORIGIN)
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(Passkeys.publicKeyOf(registered.passkey))
            update(authData)
            update(sha256(tampered.toByteArray(Charsets.UTF_8)))
        }
        assertFalse(verifier.verify(signature))
    }

    @Test
    fun `two registrations are two different credentials`() {
        val one = register()
        val two = register()

        assertFalse(one.passkey.credentialId == two.passkey.credentialId)
        assertFalse(one.passkey.privateKey == two.passkey.privateKey)
    }

    // --- The structures a site parses --------------------------------------------------------------

    @Test
    fun `the attestation object says none, and carries the authenticator data`() {
        val registered = register()
        val attestation = WebAuthn.fromBase64Url(
            response(registered.responseJson).getAsJsonObject("response").get("attestationObject").asString
        )!!

        @Suppress("UNCHECKED_CAST")
        val parsed = CborReader.parse(attestation) as Map<Any?, Any?>

        assertEquals("none", parsed["fmt"])
        assertEquals(emptyMap<Any?, Any?>(), parsed["attStmt"])
        assertNotNull(parsed["authData"])
    }

    @Test
    fun `the authenticator data is shaped the way the spec lays it out`() {
        val registered = register()
        val authData = authDataOf(registered)

        // 32 rpIdHash + 1 flags + 4 signCount + 16 aaguid + 2 length + 16 credentialId + COSE key
        assertArrayEquals(
            "the hash is over the relying party id and nothing else",
            WebAuthn.sha256("bank.com".toByteArray(Charsets.UTF_8)),
            authData.copyOfRange(0, 32)
        )

        val flags = authData[32].toInt() and 0xFF
        assertTrue("user present", flags and WebAuthn.FLAG_USER_PRESENT != 0)
        assertTrue("user verified — the vault was unlocked to get here", flags and WebAuthn.FLAG_USER_VERIFIED != 0)
        assertTrue("backup eligible — the key is not bound to this phone", flags and WebAuthn.FLAG_BACKUP_ELIGIBLE != 0)
        assertTrue("backed up — it rides the archive", flags and WebAuthn.FLAG_BACKED_UP != 0)
        assertTrue("attested credential data follows", flags and WebAuthn.FLAG_ATTESTED_CREDENTIAL_DATA != 0)

        assertEquals("the counter is deliberately fixed", 0, signCountOf(authData))
        assertArrayEquals("no AAGUID is claimed", ByteArray(16), authData.copyOfRange(37, 53))

        val idLength = ((authData[53].toInt() and 0xFF) shl 8) or (authData[54].toInt() and 0xFF)
        assertEquals(16, idLength)
        assertArrayEquals(
            WebAuthn.fromBase64Url(registered.passkey.credentialId),
            authData.copyOfRange(55, 55 + idLength)
        )
    }

    @Test
    fun `an assertion carries no attested credential data`() {
        val registered = register()
        val clientData = WebAuthn.clientDataJson("webauthn.get", "Y2hhbGxlbmdl", ORIGIN)
        val assertionJson = Passkeys.assertion(
            registered.passkey, clientData, sha256(clientData.toByteArray(Charsets.UTF_8))
        )!!
        val authData = WebAuthn.fromBase64Url(
            response(assertionJson).getAsJsonObject("response").get("authenticatorData").asString
        )!!

        // 32 + 1 + 4 and nothing more: the credential was handed over once, at registration.
        assertEquals(37, authData.size)
        assertEquals(0, authData[32].toInt() and WebAuthn.FLAG_ATTESTED_CREDENTIAL_DATA)
    }

    @Test
    fun `the public key the site is given is the one the vault kept`() {
        val registered = register()
        val authData = authDataOf(registered)

        // The COSE key begins after the credential id and runs to the end.
        val coseStart = 55 + 16
        @Suppress("UNCHECKED_CAST")
        val cose = CborReader.parse(authData.copyOfRange(coseStart, authData.size)) as Map<Any?, Any?>

        assertEquals("EC2", 2L, cose[1L])
        assertEquals("ES256", -7L, cose[3L])
        assertEquals("P-256", 1L, cose[-1L])

        val stored = Passkeys.publicKeyOf(registered.passkey) as ECPublicKey
        assertArrayEquals(WebAuthn.fixedLength(stored.w.affineX, 32), cose[-2L] as ByteArray)
        assertArrayEquals(WebAuthn.fixedLength(stored.w.affineY, 32), cose[-3L] as ByteArray)
    }

    @Test
    fun `a coordinate with a leading zero is still thirty-two bytes`() {
        // BigInteger drops leading zeroes and prepends a sign byte, so a coordinate whose top byte
        // is zero is a 31-byte array and a rejected credential — about one time in two hundred and
        // fifty-six, which is exactly often enough to be a mystery rather than a bug report.
        val small = java.math.BigInteger("1")
        assertEquals(32, WebAuthn.fixedLength(small, 32).size)
        assertEquals(0, WebAuthn.fixedLength(small, 32)[0].toInt())

        val topBitSet = java.math.BigInteger(1, ByteArray(32) { 0xFF.toByte() })
        assertEquals(32, WebAuthn.fixedLength(topBitSet, 32).size)
    }

    @Test
    fun `client data is the exact JSON that gets hashed`() {
        val json = WebAuthn.clientDataJson("webauthn.get", "Y2hhbGxlbmdl", "https://bank.com")

        assertEquals(
            """{"type":"webauthn.get","challenge":"Y2hhbGxlbmdl","origin":"https://bank.com","crossOrigin":false}""",
            json
        )
    }

    @Test
    fun `an app names itself by the hash of its signing certificate`() {
        val origin = WebAuthn.apkOrigin(ByteArray(32) { 1 })

        assertTrue(origin.startsWith("android:apk-key-hash:"))
        assertFalse("base64url, never padded", origin.endsWith("="))
    }

    // --- Reading the request -----------------------------------------------------------------------

    @Test
    fun `a creation request is read`() {
        val options = PasskeyRequest.parseCreation(creationJson)!!

        assertEquals("bank.com", options.rpId)
        assertEquals("The Bank", options.rpName)
        assertEquals("me@example.com", options.userName)
        assertEquals("Me", options.userDisplayName)
        assertEquals("dXNlci0x", options.userHandle)
        assertTrue(options.supportsEs256)
    }

    @Test
    fun `a site that will not take ES256 is refused rather than served something it rejects`() {
        val rsaOnly = """
            {"rp":{"id":"bank.com"},"user":{"id":"dQ","name":"me"},"challenge":"Yw",
             "pubKeyCredParams":[{"type":"public-key","alg":-257}]}
        """.trimIndent()

        val options = PasskeyRequest.parseCreation(rsaOnly)!!
        assertFalse(options.supportsEs256)
        assertNull(
            "issuing a key of the wrong algorithm produces a credential that fails at first use",
            Passkeys.register(options, "{}", ByteArray(32), now = 1L)
        )
    }

    @Test
    fun `stating no preference means ES256, because that is what passkeys are`() {
        val noParams = """{"rp":{"id":"bank.com"},"user":{"id":"dQ"},"challenge":"Yw"}"""

        assertTrue(PasskeyRequest.parseCreation(noParams)!!.supportsEs256)
    }

    @Test
    fun `a request with no relying party or no challenge is not a request`() {
        assertNull(PasskeyRequest.parseCreation("""{"user":{"id":"dQ"},"challenge":"Yw"}"""))
        assertNull(PasskeyRequest.parseCreation("""{"rp":{"id":"bank.com"},"user":{"id":"dQ"}}"""))
        assertNull(PasskeyRequest.parseCreation("""{"rp":{"id":""},"challenge":"Yw"}"""))
        assertNull(PasskeyRequest.parseCreation("not json"))
        assertNull(PasskeyRequest.parseAssertion("""{"challenge":"Yw"}"""))
        assertNull(PasskeyRequest.parseAssertion("""{"rpId":"bank.com"}"""))
    }

    @Test
    fun `an assertion request names the relying party and may name credentials`() {
        val json = """
            {"rpId":"bank.com","challenge":"Y2hhbGxlbmdl",
             "allowCredentials":[{"type":"public-key","id":"YWJj"},{"type":"public-key","id":"ZGVm"}]}
        """.trimIndent()

        val request = PasskeyRequest.parseAssertion(json)!!

        assertEquals("bank.com", request.rpId)
        assertEquals(listOf("YWJj", "ZGVm"), request.allowCredentialIds)
    }

    @Test
    fun `an empty allow-list means any credential for that site`() {
        val json = """{"rpId":"bank.com","challenge":"Yw","allowCredentials":[]}"""

        assertTrue(PasskeyRequest.parseAssertion(json)!!.allowCredentialIds.isEmpty())
    }

    @Test
    fun `two spellings of the same credential id are the same credential`() {
        // A site echoes back an id this app issued, through its own library, which may have padded
        // it or used the standard alphabet. Comparing the strings would fail over punctuation.
        assertTrue(PasskeyRequest.sameCredential("YWJjZA", "YWJjZA=="))
        assertTrue(PasskeyRequest.sameCredential("q-_a", "q+/a"))
        assertFalse(PasskeyRequest.sameCredential("YWJjZA", "ZGVmZw"))
        assertFalse(PasskeyRequest.sameCredential("YWJjZA", "!!!"))
    }

    // --- Living in the vault -------------------------------------------------------------------------

    @Test
    fun `a stored passkey survives a JSON round trip and still signs`() {
        val registered = register()
        val item = VaultItem(
            id = "a",
            kind = VaultItemKind.PASSKEY,
            title = "The Bank",
            passkey = registered.passkey,
            createdAt = 1,
            updatedAt = 1
        )
        val document = VaultDocument.EMPTY.upsert(item, now = 10)

        val parsed = VaultJson.decode(VaultJson.encode(document))!!
        val restored = parsed.item("a")!!.passkey!!

        assertEquals(registered.passkey, restored)
        val clientData = WebAuthn.clientDataJson("webauthn.get", "Yw", ORIGIN)
        assertNotNull(
            Passkeys.assertion(restored, clientData, sha256(clientData.toByteArray(Charsets.UTF_8)))
        )
    }

    @Test
    fun `a passkey moves the document to version three and nothing else does`() {
        val withPasskey = VaultDocument.EMPTY.upsert(
            VaultItem(id = "a", passkey = register().passkey), now = 10
        )
        assertEquals(VaultDocument.DOCUMENT_VERSION, withPasskey.version)
        assertEquals(3, VaultDocument.DOCUMENT_VERSION)

        val plain = VaultDocument.EMPTY.upsert(VaultItem(id = "b", secret = "p"), now = 10)
        assertEquals(VaultDocument.BASELINE_VERSION, plain.version)
    }

    @Test
    fun `deleting a passkey takes the private key with it`() {
        val document = VaultDocument.EMPTY
            .upsert(VaultItem(id = "a", passkey = register().passkey), now = 10)

        val tombstone = document.delete("a", now = 20).items.single()

        assertTrue(tombstone.isDeleted)
        assertNull(tombstone.passkey)
    }

    @Test
    fun `a private key is never matched by the search box`() {
        val passkey = register().passkey
        val item = VaultItem(
            id = "a",
            kind = VaultItemKind.PASSKEY,
            title = "The Bank",
            url = "bank.com",
            username = "me@example.com",
            passkey = passkey
        )

        // Findable by the site and the account, which is how anybody looks for it...
        assertTrue(item.matches("bank.com"))
        assertTrue(item.matches("me@example"))
        // ...and never by the credential itself.
        assertFalse(item.matches(passkey.privateKey))
        assertFalse(item.matches(passkey.privateKey.take(24)))
    }

    @Test
    fun `a stored credential that cannot sign is dropped on the way in`() {
        val parsed = VaultJson.decode(
            """{"version":3,"items":[{"id":"a","passkey":{"credentialId":"YWJj","rpId":"bank.com"}}]}"""
                .toByteArray()
        )

        // No private key means no signature, and an item that offers a passkey it cannot use is a
        // sign-in that fails at the prompt.
        assertNotNull(parsed)
        assertNull(parsed!!.items.single().passkey)
    }

    private fun authDataOf(registered: Passkeys.Registration): ByteArray {
        val attestation = WebAuthn.fromBase64Url(
            response(registered.responseJson).getAsJsonObject("response").get("attestationObject").asString
        )!!
        @Suppress("UNCHECKED_CAST")
        val parsed = CborReader.parse(attestation) as Map<Any?, Any?>
        return parsed["authData"] as ByteArray
    }

    private fun signCountOf(authData: ByteArray): Int =
        ((authData[33].toInt() and 0xFF) shl 24) or
            ((authData[34].toInt() and 0xFF) shl 16) or
            ((authData[35].toInt() and 0xFF) shl 8) or
            (authData[36].toInt() and 0xFF)

    private companion object {
        const val ORIGIN = "android:apk-key-hash:AAAA"
    }
}
