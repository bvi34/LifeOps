package com.operations.securestore

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * How one value is sealed: AES-256-GCM under the store's key, with the file's name and the entry's
 * name as associated data, written as `v1:` and the Base64 of the nonce followed by the ciphertext.
 *
 * The associated data is what stops a value being moved: a token copied from one entry to another,
 * or from one file to another, fails to open rather than being read as the other entry's. The
 * version prefix is so a later format can be told apart from this one.
 *
 * Plain `javax.crypto`, so the same code runs against a Keystore key on the phone and a software key
 * in the tests.
 */
object SealedStrings {

    private const val PREFIX = "v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    fun seal(key: SecretKey, file: String, name: String, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No nonce passed in: the provider picks a fresh random one, which is also the only thing a
        // Keystore key will accept.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData(file, name))
        val nonce = cipher.iv
        check(nonce.size == NONCE_BYTES) { "Unexpected GCM nonce length ${nonce.size}" }
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(nonce + sealed)
    }

    /**
     * The plaintext, or null when [sealed] can't be opened: the wrong key, the wrong entry, a
     * damaged value, or something that was never sealed by this.
     */
    fun open(key: SecretKey, file: String, name: String, sealed: String): String? {
        if (!sealed.startsWith(PREFIX)) return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(sealed.substring(PREFIX.length))
            require(bytes.size > NONCE_BYTES) { "Too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
            cipher.updateAAD(associatedData(file, name))
            String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun associatedData(file: String, name: String): ByteArray =
        "$file\u0000$name".toByteArray(Charsets.UTF_8)
}
