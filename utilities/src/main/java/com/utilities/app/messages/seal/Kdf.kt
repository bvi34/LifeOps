package com.utilities.app.messages.seal

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * The two symmetric primitives: HKDF to turn shared secrets into keys, and AES-GCM to use them.
 *
 * Both come from the platform (`javax.crypto`), which is the opposite of the decision taken for the
 * curve and for the same reason: HMAC-SHA256 and AES-GCM are present, identical and hardware
 * accelerated everywhere this runs, and there is no version where the tested path and the shipped
 * path differ. What is written here is the *composition* — HKDF is not a JCA algorithm — and it is
 * pinned against RFC 5869's vectors.
 */
object Kdf {

    private const val HMAC = "HmacSHA256"
    const val HASH_BYTES = 32

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH_BYTES) else key, HMAC))
        return mac.doFinal(data)
    }

    /** HKDF-Extract: a salt and some input keying material become one pseudorandom key. */
    fun extract(salt: ByteArray, keyMaterial: ByteArray): ByteArray = hmac(salt, keyMaterial)

    /** HKDF-Expand: one pseudorandom key becomes as many bytes as are asked for. */
    fun expand(pseudoRandomKey: ByteArray, info: ByteArray, length: Int): ByteArray {
        if (length < 0 || length > 255 * HASH_BYTES) throw SealException("cannot expand to $length bytes")
        val out = ByteArray(length)
        var block = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            block = hmac(pseudoRandomKey, Bytes.concat(block, info, byteArrayOf(counter.toByte())))
            val take = minOf(block.size, length - produced)
            System.arraycopy(block, 0, out, produced, take)
            produced += take
            counter++
        }
        return out
    }

    /** Both halves, which is how every caller here uses it. */
    fun derive(salt: ByteArray, keyMaterial: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, keyMaterial), info, length)
}

/**
 * Authenticated encryption.
 *
 * AES-256-GCM, with the nonce carried beside the ciphertext rather than derived, and the message's
 * own header as associated data — so a header somebody edited in flight makes the message fail to
 * open rather than open as something else.
 *
 * ## Nonces
 *
 * Twelve random bytes. The usual objection is that random nonces risk a repeat, and the usual answer
 * — a counter — is *wrong here*: every message in this package is encrypted under a **key used
 * exactly once**, derived from the ratchet. A repeat would need the same key and the same nonce, and
 * there is no second message under the same key for the nonce to collide with.
 */
object Aead {

    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val ALGORITHM = "AES"

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8

    private val random = SecureRandom()

    /** Encrypt, returning the nonce and the ciphertext-with-tag together. */
    fun seal(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): Sealed {
        if (key.size != KEY_BYTES) throw SealException("a message key is ${key.size} bytes")
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, ALGORITHM), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        return Sealed(nonce = nonce, ciphertext = cipher.doFinal(plaintext))
    }

    /**
     * Decrypt, or throw.
     *
     * Every failure is the same failure — a wrong key, a wrong nonce, a flipped bit and an edited
     * header all come back as "this did not open" — because distinguishing them for the caller would
     * mean distinguishing them for whoever sent it.
     */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        if (key.size != KEY_BYTES) throw SealException("a message key is ${key.size} bytes")
        if (nonce.size != NONCE_BYTES) throw SealException("a nonce is ${nonce.size} bytes")
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, ALGORITHM), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(associatedData)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw SealException("that message did not open")
        }
    }

    data class Sealed(val nonce: ByteArray, val ciphertext: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Sealed && nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)

        override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()
    }
}
