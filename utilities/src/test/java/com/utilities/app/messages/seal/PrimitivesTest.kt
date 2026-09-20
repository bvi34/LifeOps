package com.utilities.app.messages.seal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The primitives, against the numbers the specifications publish.
 *
 * This is the only kind of test a curve or a KDF can usefully have. Everything above this file is
 * checked by round trip — seal something, open it, compare — and a round trip is blind to an
 * implementation that is self-consistently wrong: two ends agreeing perfectly with each other and
 * with no other implementation on earth. The vectors are what stop that.
 */
class Curve25519Test {

    /** RFC 7748 §5.2, the two worked examples. */
    @Test
    fun `the ladder matches RFC 7748's own vectors`() {
        assertArrayEquals(
            Bytes.fromHex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"),
            Curve25519.scalarMultiply(
                Bytes.fromHex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                Bytes.fromHex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
            )
        )
        assertArrayEquals(
            Bytes.fromHex("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"),
            Curve25519.scalarMultiply(
                Bytes.fromHex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                Bytes.fromHex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
            )
        )
    }

    /** RFC 7748 §6.1, the worked Diffie-Hellman between Alice and Bob. */
    @Test
    fun `Alice and Bob from the RFC reach the RFC's shared secret`() {
        val alicePrivate = Bytes.fromHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePublic = Bytes.fromHex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPrivate = Bytes.fromHex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPublic = Bytes.fromHex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = Bytes.fromHex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        assertArrayEquals(alicePublic, Curve25519.publicKey(alicePrivate))
        assertArrayEquals(bobPublic, Curve25519.publicKey(bobPrivate))
        assertArrayEquals(shared, Curve25519.agree(alicePrivate, bobPublic))
        assertArrayEquals(shared, Curve25519.agree(bobPrivate, alicePublic))
    }

    @Test
    fun `any two generated keypairs agree, both ways round`() {
        repeat(10) {
            val a = Curve25519.generateKeyPair()
            val b = Curve25519.generateKeyPair()
            assertArrayEquals(
                Curve25519.agree(a.privateKey, b.publicKey),
                Curve25519.agree(b.privateKey, a.publicKey)
            )
        }
    }

    @Test
    fun `two keypairs are not the same keypair`() {
        val keys = (1..20).map { Bytes.hex(Curve25519.generateKeyPair().publicKey) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a small-order point is refused rather than agreed with`() {
        // All-zero output means the peer chose a point that forces the shared secret. Both ends
        // would derive the same keys from something an attacker picked.
        val zero = ByteArray(32)
        assertThrows(SealException::class.java) {
            Curve25519.agree(Curve25519.generatePrivateKey(), zero)
        }
    }

    @Test
    fun `a key of the wrong length is refused`() {
        assertThrows(SealException::class.java) { Curve25519.scalarMultiply(ByteArray(31), ByteArray(32)) }
        assertThrows(SealException::class.java) { Curve25519.scalarMultiply(ByteArray(32), ByteArray(33)) }
    }

    @Test
    fun `a keypair does not print its private half`() {
        // A private key in a log is a private key.
        val pair = Curve25519.generateKeyPair()
        assertFalse(pair.toString().contains(Bytes.hex(pair.privateKey)))
    }
}

/** HKDF against RFC 5869, and AES-GCM against itself. */
class KdfTest {

    /** RFC 5869 Appendix A.1, the basic SHA-256 case. */
    @Test
    fun `HKDF matches RFC 5869`() {
        val ikm = Bytes.fromHex("0b".repeat(22))
        val salt = Bytes.fromHex("000102030405060708090a0b0c")
        val info = Bytes.fromHex("f0f1f2f3f4f5f6f7f8f9")

        val prk = Kdf.extract(salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            Bytes.hex(prk)
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Bytes.hex(Kdf.expand(prk, info, 42))
        )
    }

    /** RFC 5869 Appendix A.3: no salt, no info. */
    @Test
    fun `HKDF with nothing to salt it matches too`() {
        val prk = Kdf.extract(ByteArray(0), Bytes.fromHex("0b".repeat(22)))
        assertEquals(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            Bytes.hex(prk)
        )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Bytes.hex(Kdf.expand(prk, ByteArray(0), 42))
        )
    }

    @Test
    fun `expanding past what SHA-256 can produce is refused`() {
        assertThrows(SealException::class.java) { Kdf.expand(ByteArray(32), ByteArray(0), 255 * 32 + 1) }
    }

    @Test
    fun `a sealed payload opens with the right key and not with a wrong one`() {
        val key = ByteArray(32) { it.toByte() }
        val ad = "who to who".toByteArray()
        val sealed = Aead.seal(key, "meet me at six".toByteArray(), ad)

        assertEquals("meet me at six", String(Aead.open(key, sealed.nonce, sealed.ciphertext, ad)))

        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        assertThrows(SealException::class.java) { Aead.open(wrongKey, sealed.nonce, sealed.ciphertext, ad) }
    }

    @Test
    fun `changing the associated data makes it fail to open`() {
        // Which is what binds a message to its header and to the pair of identities it belongs to.
        val key = ByteArray(32)
        val sealed = Aead.seal(key, "hello".toByteArray(), "alice-bob".toByteArray())
        assertThrows(SealException::class.java) {
            Aead.open(key, sealed.nonce, sealed.ciphertext, "alice-mallory".toByteArray())
        }
    }

    @Test
    fun `a single flipped bit fails to open`() {
        val key = ByteArray(32)
        val sealed = Aead.seal(key, "hello there".toByteArray(), ByteArray(0))
        sealed.ciphertext[3] = (sealed.ciphertext[3].toInt() xor 1).toByte()
        assertThrows(SealException::class.java) {
            Aead.open(key, sealed.nonce, sealed.ciphertext, ByteArray(0))
        }
    }

    @Test
    fun `every failure looks the same from outside`() {
        // Distinguishing a wrong key from a wrong tag for the caller would mean distinguishing them
        // for whoever sent it.
        val key = ByteArray(32)
        val sealed = Aead.seal(key, "hello".toByteArray(), ByteArray(0))
        val reasons = listOf(
            runCatching { Aead.open(ByteArray(32) { 9 }, sealed.nonce, sealed.ciphertext, ByteArray(0)) },
            runCatching { Aead.open(key, ByteArray(12) { 9 }, sealed.ciphertext, ByteArray(0)) },
            runCatching { Aead.open(key, sealed.nonce, ByteArray(20), ByteArray(0)) }
        ).map { (it.exceptionOrNull() as SealException).message }
        assertEquals(1, reasons.toSet().size)
    }

    @Test
    fun `two sealings of the same thing are different`() {
        val key = ByteArray(32)
        val one = Aead.seal(key, "same".toByteArray(), ByteArray(0))
        val two = Aead.seal(key, "same".toByteArray(), ByteArray(0))
        assertNotEquals(Bytes.hex(one.nonce), Bytes.hex(two.nonce))
        assertNotEquals(Bytes.hex(one.ciphertext), Bytes.hex(two.ciphertext))
    }
}

/** The byte handling, and the one function in it that is not obvious. */
class BytesTest {

    @Test
    fun `a constant-time comparison still compares`() {
        assertTrue(Bytes.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(Bytes.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(Bytes.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertTrue(Bytes.constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun `integers round trip big-endian`() {
        listOf(0, 1, 255, 256, 65_535, Int.MAX_VALUE).forEach { value ->
            assertEquals(value, Bytes.readInt(Bytes.int(value), 0))
        }
    }

    @Test
    fun `a slice past the end is refused rather than clamped`() {
        assertThrows(SealException::class.java) { Bytes.slice(ByteArray(4), 2, 9) }
        assertThrows(SealException::class.java) { Bytes.readInt(ByteArray(2), 0) }
    }

    @Test
    fun `base64 round trips, and is URL-safe and unpadded`() {
        val bytes = ByteArray(40) { (it * 7).toByte() }
        val text = Bytes.encode(bytes)
        assertFalse("padding is dropped by too many gateways", text.contains("="))
        assertFalse(text.contains("+"))
        assertFalse(text.contains("/"))
        assertArrayEquals(bytes, Bytes.decode(text))
    }

    @Test
    fun `something that is not base64 is refused with a sentence`() {
        assertThrows(SealException::class.java) { Bytes.decode("not base64 at all !!") }
    }

    @Test
    fun `hex round trips`() {
        val bytes = ByteArray(32) { (it * 11).toByte() }
        assertArrayEquals(bytes, Bytes.fromHex(Bytes.hex(bytes)))
        assertThrows(SealException::class.java) { Bytes.fromHex("abc") }
    }
}
