package com.operations.vaultkit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tests that matter most in this module: the ones that assert the vault *fails* to open.
 *
 * A password manager that opens with the right passphrase is a thing anybody can demonstrate by
 * hand once. What nobody demonstrates by hand — and what a later refactor breaks silently — is that
 * it stays shut for the wrong passphrase, for a header somebody edited, for a body somebody spliced
 * in from another file, and for a truncated download. Each of those is a test here.
 *
 * The KDF is run at [TEST_ITERATIONS] rather than the shipped 310,000 so the suite stays quick.
 * That is a cost knob, not a behaviour: the same code path runs either way, and
 * [defaultsAreTheShippedCost] asserts the shipped default has not been quietly lowered.
 */
class VaultEnvelopeTest {

    private val passphrase = "correct horse battery staple".toCharArray()

    private fun document(text: String = "hello") = text.toByteArray()

    @Test
    fun `a vault opens with its passphrase and yields the document`() {
        val file = VaultEnvelope.create(passphrase, document("the pantry code is 4417"), TEST_ITERATIONS)

        val key = VaultEnvelope.unwrapKey(file, passphrase)
        assertNotNull(key)
        assertEquals("the pantry code is 4417", String(VaultEnvelope.read(file, key!!)!!))
    }

    @Test
    fun `the wrong passphrase yields nothing rather than an error anyone can read`() {
        val file = VaultEnvelope.create(passphrase, document(), TEST_ITERATIONS)

        assertNull(VaultEnvelope.unwrapKey(file, "correct horse battery stapl".toCharArray()))
        assertNull(VaultEnvelope.unwrapKey(file, "".toCharArray()))
    }

    @Test
    fun `encoding and decoding is a round trip, byte for byte`() {
        val file = VaultEnvelope.create(passphrase, document("a note"), TEST_ITERATIONS)
        val bytes = VaultEnvelope.encode(file)

        val parsed = VaultEnvelope.decode(bytes)
        assertNotNull(parsed)
        assertArrayEquals(bytes, VaultEnvelope.encode(parsed!!))

        val key = VaultEnvelope.unwrapKey(parsed, passphrase)!!
        assertEquals("a note", String(VaultEnvelope.read(parsed, key)!!))
    }

    @Test
    fun `two vaults made from the same passphrase and document share no bytes`() {
        val first = VaultEnvelope.encode(VaultEnvelope.create(passphrase, document(), TEST_ITERATIONS))
        val second = VaultEnvelope.encode(VaultEnvelope.create(passphrase, document(), TEST_ITERATIONS))

        // Fresh salt, fresh vault key, fresh nonces. Identical inputs producing identical files
        // would mean one of those three had stopped being random.
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `a flipped bit in the body is a failure to open, not a corrupt document`() {
        val file = VaultEnvelope.create(passphrase, document("balance: 12"), TEST_ITERATIONS)
        val bytes = VaultEnvelope.encode(file)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()

        val parsed = VaultEnvelope.decode(bytes)!!
        val key = VaultEnvelope.unwrapKey(parsed, passphrase)!!
        assertNull(VaultEnvelope.read(parsed, key))
    }

    @Test
    fun `editing the iteration count in the header breaks the vault rather than weakening it`() {
        val file = VaultEnvelope.create(passphrase, document(), TEST_ITERATIONS)
        val bytes = VaultEnvelope.encode(file)

        // The iteration count sits at offset 10: 8 bytes of magic, one of version, one of KDF id.
        // Rewriting it to 1 is the attack this AAD exists to stop — a header that says the key is
        // cheap to derive, over a body that was sealed against a header saying it was not.
        bytes[10] = 0; bytes[11] = 0; bytes[12] = 0; bytes[13] = 1

        val parsed = VaultEnvelope.decode(bytes)!!
        assertEquals(1, parsed.iterations)
        assertNull("a downgraded header must not unwrap the key", VaultEnvelope.unwrapKey(parsed, passphrase))
    }

    @Test
    fun `a wrapped key from another vault cannot be spliced onto this one`() {
        val mine = VaultEnvelope.create(passphrase, document("mine"), TEST_ITERATIONS)
        val theirs = VaultEnvelope.create(passphrase, document("theirs"), TEST_ITERATIONS)

        val spliced = VaultFile(
            formatVersion = mine.formatVersion,
            kdfId = mine.kdfId,
            iterations = mine.iterations,
            salt = mine.salt,
            wrappedKey = theirs.wrappedKey,
            body = mine.body
        )

        // The other vault's wrapped key was sealed against the other vault's salt, so it does not
        // even unwrap here. Belt and braces: were it to, the body's AAD covers the wrapped key too,
        // so the document would still refuse to open.
        assertNull(VaultEnvelope.unwrapKey(spliced, passphrase))
    }

    @Test
    fun `a body from another vault cannot be spliced onto this header`() {
        val mine = VaultEnvelope.create(passphrase, document("mine"), TEST_ITERATIONS)
        val theirs = VaultEnvelope.create(passphrase, document("theirs"), TEST_ITERATIONS)
        val theirKey = VaultEnvelope.unwrapKey(theirs, passphrase)!!

        val spliced = VaultFile(
            formatVersion = mine.formatVersion,
            kdfId = mine.kdfId,
            iterations = mine.iterations,
            salt = mine.salt,
            wrappedKey = mine.wrappedKey,
            body = theirs.body
        )

        assertNull(VaultEnvelope.read(spliced, theirKey))
    }

    @Test
    fun `rubbish, truncation and a version from the future all decode to null`() {
        assertNull(VaultEnvelope.decode(ByteArray(0)))
        assertNull(VaultEnvelope.decode("not a vault at all".toByteArray()))

        val bytes = VaultEnvelope.encode(VaultEnvelope.create(passphrase, document(), TEST_ITERATIONS))
        assertNull(VaultEnvelope.decode(bytes.copyOf(bytes.size / 2)))

        val future = bytes.copyOf()
        future[8] = 99
        assertNull("a format version this build does not know must be refused", VaultEnvelope.decode(future))
    }

    @Test
    fun `looksLikeVault tells a vault from whatever else ended up under that name`() {
        val bytes = VaultEnvelope.encode(VaultEnvelope.create(passphrase, document(), TEST_ITERATIONS))

        assertTrue(VaultEnvelope.looksLikeVault(bytes))
        assertFalse(VaultEnvelope.looksLikeVault("PK a zip, actually".toByteArray()))
        assertFalse(VaultEnvelope.looksLikeVault(ByteArray(0)))
    }

    @Test
    fun `saving a change keeps the header and costs no key derivation`() {
        val file = VaultEnvelope.create(passphrase, document("before"), TEST_ITERATIONS)
        val key = VaultEnvelope.unwrapKey(file, passphrase)!!

        val saved = VaultEnvelope.reseal(file, key, document("after"))

        assertArrayEquals(file.salt, saved.salt)
        assertEquals(file.wrappedKey, saved.wrappedKey)
        assertEquals("after", String(VaultEnvelope.read(saved, key)!!))
        // The same passphrase still opens it, because the half that the passphrase touches did not
        // move.
        assertArrayEquals(key, VaultEnvelope.unwrapKey(saved, passphrase))
    }

    @Test
    fun `changing the passphrase keeps the vault key, so nothing has to be re-encrypted`() {
        val file = VaultEnvelope.create(passphrase, document("the same secrets"), TEST_ITERATIONS)
        val key = VaultEnvelope.unwrapKey(file, passphrase)!!
        val newPassphrase = "a longer thing nobody else knows".toCharArray()

        val rewrapped = VaultEnvelope.rewrap(key, document("the same secrets"), newPassphrase, TEST_ITERATIONS)

        assertArrayEquals(key, VaultEnvelope.unwrapKey(rewrapped, newPassphrase))
        assertNull("the old passphrase must stop working", VaultEnvelope.unwrapKey(rewrapped, passphrase))
        assertFalse("a new passphrase gets a new salt", rewrapped.salt.contentEquals(file.salt))
        assertEquals("the same secrets", String(VaultEnvelope.read(rewrapped, key)!!))
    }

    @Test
    fun `a vault written at a lower cost still opens after the default rises`() {
        val cheap = VaultEnvelope.create(passphrase, document(), 1_000)

        assertEquals(1_000, cheap.iterations)
        assertNotNull(VaultEnvelope.unwrapKey(cheap, passphrase))
    }

    @Test
    fun defaultsAreTheShippedCost() {
        // OWASP's floor for PBKDF2-HMAC-SHA256. If somebody lowers this to make a screen feel
        // snappier, they get to change this line and explain themselves in the diff.
        assertEquals(310_000, VaultCrypto.DEFAULT_ITERATIONS)
        assertEquals(32, VaultCrypto.KEY_BYTES)
        assertEquals(12, VaultCrypto.NONCE_BYTES)
    }

    private companion object {
        /** Enough to exercise the KDF, few enough that the whole suite stays under a second. */
        const val TEST_ITERATIONS = 1_000
    }
}
