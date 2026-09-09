package com.operations.vaultkit

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The vault file itself: a header nobody needs a key to read, a vault key nobody can read without
 * the passphrase, and a document nobody can read without the vault key.
 *
 * ```
 *   magic      "OPSVAULT"          8 bytes
 *   version    1                   1 byte
 *   kdf        1 = PBKDF2-SHA256   1 byte
 *   iterations                     4 bytes, big-endian
 *   salt       len + bytes         1 + n
 *  ─── the KDF header ends here; it is the AAD the wrapped key is bound to ───
 *   wrap       nonce + len + bytes 1 + n + 2 + n     (the vault key, sealed with the passphrase key)
 *  ─── the full header ends here; it is the AAD the body is bound to ───
 *   body       nonce + len + bytes 1 + n + 4 + n     (the document, sealed with the vault key)
 * ```
 *
 * ## Why the header is in the clear
 *
 * It has to be: it says how to turn the passphrase into a key, and a reader that could not read it
 * could not open the vault. What it must not be is *malleable*, so both halves of it are
 * authenticated — the KDF header as the wrapped key's AAD, the whole header as the body's. Edit the
 * iteration count down and the vault stops opening; splice another vault's wrapped key beside this
 * one's body and it stops opening. Neither produces a weaker vault, which is the only property that
 * matters here.
 *
 * The header leaks exactly two things to somebody holding a stolen backup: that this is a vault (the
 * magic), and how expensive it was to make (the iteration count). Both are things they would assume.
 *
 * ## Why the key is wrapped rather than derived
 *
 * The document is encrypted with a random 256-bit key; the passphrase-derived key encrypts only
 * that. Three things fall out of it, and each of them is a feature this app needs rather than a
 * cryptographic nicety:
 *
 *  - **Changing the passphrase is instant** — [rewrap] re-seals 60 bytes and leaves the body alone,
 *    so a household that has just discovered somebody watched them type it is not deterred by a
 *    progress bar.
 *  - **The device's quick unlock is a second wrapping**, not a second copy. :secrets can hand the
 *    same vault key to the Android Keystore for a fingerprint to unwrap, and that convenience can be
 *    revoked, expire or die with the phone without touching what the passphrase opens.
 *  - **Raising the KDF cost is not a migration.** A vault written at 310,000 rounds re-wraps to
 *    600,000 without re-encrypting a single secret.
 *
 * ## Versioning
 *
 * [FORMAT_VERSION] covers the *layout*; the document inside carries its own version (see
 * [VaultDocument]). A file from the future is refused by [decode] rather than guessed at — a vault is
 * the one place in this suite where reading half of something correctly is worse than reading none
 * of it.
 */
class VaultFile(
    val formatVersion: Int,
    val kdfId: Int,
    val iterations: Int,
    val salt: ByteArray,
    /** The vault key, sealed under the passphrase-derived key, bound to the KDF header. */
    val wrappedKey: Sealed,
    /** The document, sealed under the vault key, bound to the whole header. */
    val body: Sealed
) {

    /** The bytes the wrapped key is authenticated against: everything up to and including the salt. */
    internal fun kdfHeader(): ByteArray = ByteArrayOutputStream().apply {
        write(VaultEnvelope.MAGIC)
        write(formatVersion)
        write(kdfId)
        write(ByteBuffer.allocate(4).putInt(iterations).array())
        write(salt.size)
        write(salt)
    }.toByteArray()

    /** The bytes the body is authenticated against: the KDF header plus the wrapped key. */
    internal fun header(): ByteArray = ByteArrayOutputStream().apply {
        write(kdfHeader())
        write(wrappedKey.nonce.size)
        write(wrappedKey.nonce)
        write(ByteBuffer.allocate(2).putShort(wrappedKey.ciphertext.size.toShort()).array())
        write(wrappedKey.ciphertext)
    }.toByteArray()
}

object VaultEnvelope {

    /** ASCII, eight bytes, so `file` and a human hex-dumping a backup both get a straight answer. */
    internal val MAGIC = "OPSVAULT".toByteArray(Charsets.US_ASCII)

    const val FORMAT_VERSION = 1

    /** PBKDF2-HMAC-SHA256. The only KDF this version knows; a file naming another is refused. */
    const val KDF_PBKDF2_SHA256 = 1

    /**
     * Seal [document] into a brand new vault protected by [passphrase].
     *
     * The caller keeps ownership of [passphrase] and should wipe it; this generates its own salt,
     * its own vault key and its own nonces, and none of them are parameters.
     */
    fun create(
        passphrase: CharArray,
        document: ByteArray,
        iterations: Int = VaultCrypto.DEFAULT_ITERATIONS
    ): VaultFile {
        val vaultKey = VaultCrypto.newVaultKey()
        try {
            return create(passphrase, document, vaultKey, iterations)
        } finally {
            VaultCrypto.wipe(vaultKey)
        }
    }

    /**
     * The same, with the vault key supplied — the form a *restore* uses, where the key already
     * exists and it is the passphrase that is new.
     */
    fun create(
        passphrase: CharArray,
        document: ByteArray,
        vaultKey: ByteArray,
        iterations: Int = VaultCrypto.DEFAULT_ITERATIONS
    ): VaultFile {
        require(vaultKey.size == VaultCrypto.KEY_BYTES) { "vault key must be ${VaultCrypto.KEY_BYTES} bytes" }
        val salt = VaultCrypto.newSalt()
        val kek = VaultCrypto.deriveKey(passphrase, salt, iterations)
        try {
            // The KDF header is built by hand here because the wrapped key does not exist yet — this
            // is the one place the two halves of the header cannot both come from a VaultFile.
            val skeleton = VaultFile(
                formatVersion = FORMAT_VERSION,
                kdfId = KDF_PBKDF2_SHA256,
                iterations = iterations,
                salt = salt,
                wrappedKey = Sealed(ByteArray(0), ByteArray(0)),
                body = Sealed(ByteArray(0), ByteArray(0))
            )
            val wrapped = VaultCrypto.seal(kek, vaultKey, skeleton.kdfHeader())
            val withKey = VaultFile(
                formatVersion = FORMAT_VERSION,
                kdfId = KDF_PBKDF2_SHA256,
                iterations = iterations,
                salt = salt,
                wrappedKey = wrapped,
                body = Sealed(ByteArray(0), ByteArray(0))
            )
            return VaultFile(
                formatVersion = FORMAT_VERSION,
                kdfId = KDF_PBKDF2_SHA256,
                iterations = iterations,
                salt = salt,
                wrappedKey = wrapped,
                body = VaultCrypto.seal(vaultKey, document, withKey.header())
            )
        } finally {
            VaultCrypto.wipe(kek)
        }
    }

    /**
     * Recover the vault key from [file] using [passphrase], or null if it is the wrong one.
     *
     * This is the expensive call — [VaultFile.iterations] rounds of PBKDF2 — and the only one that
     * can answer "is this the passphrase". Callers hold the returned key for as long as the vault is
     * unlocked and wipe it when it locks.
     */
    fun unwrapKey(file: VaultFile, passphrase: CharArray): ByteArray? {
        if (file.kdfId != KDF_PBKDF2_SHA256) return null
        val kek = VaultCrypto.deriveKey(passphrase, file.salt, file.iterations)
        try {
            return VaultCrypto.open(kek, file.wrappedKey, file.kdfHeader())
                ?.takeIf { it.size == VaultCrypto.KEY_BYTES }
        } finally {
            VaultCrypto.wipe(kek)
        }
    }

    /** The document bytes, or null if [vaultKey] is wrong or the file has been tampered with. */
    fun read(file: VaultFile, vaultKey: ByteArray): ByteArray? =
        VaultCrypto.open(vaultKey, file.body, file.header())

    /**
     * A new file holding [document], with the same header and the same wrapped key.
     *
     * Every save goes through here: the header is *reused*, not re-derived, so saving a change costs
     * one AES pass rather than 310,000 rounds of PBKDF2. The body gets a fresh nonce, as every seal
     * does.
     */
    fun reseal(file: VaultFile, vaultKey: ByteArray, document: ByteArray): VaultFile =
        VaultFile(
            formatVersion = file.formatVersion,
            kdfId = file.kdfId,
            iterations = file.iterations,
            salt = file.salt,
            wrappedKey = file.wrappedKey,
            body = VaultCrypto.seal(vaultKey, document, file.header())
        )

    /**
     * Re-wrap [vaultKey] under a new passphrase, keeping the document as it is.
     *
     * The salt is new too — a changed passphrase over an old salt would let anyone holding both
     * files confirm the change was a change of passphrase rather than of vault. The body is
     * re-sealed rather than copied because its AAD is the header, and the header now says something
     * different; that is one AES pass over the document, not a re-encryption of each secret.
     */
    fun rewrap(
        vaultKey: ByteArray,
        document: ByteArray,
        newPassphrase: CharArray,
        iterations: Int = VaultCrypto.DEFAULT_ITERATIONS
    ): VaultFile = create(newPassphrase, document, vaultKey, iterations)

    fun encode(file: VaultFile): ByteArray = ByteArrayOutputStream().apply {
        write(file.header())
        write(file.body.nonce.size)
        write(file.body.nonce)
        write(ByteBuffer.allocate(4).putInt(file.body.ciphertext.size).array())
        write(file.body.ciphertext)
    }.toByteArray()

    /**
     * Parse [bytes], or null if they are not a vault this build can open.
     *
     * Null covers a truncated file, a wrong magic, an unknown KDF and a format version from the
     * future. No exception escapes: a corrupt backup entry is a thing that happens, and the caller's
     * response to all of these is the same — say the file cannot be read, and change nothing.
     */
    fun decode(bytes: ByteArray): VaultFile? = try {
        val buffer = ByteBuffer.wrap(bytes)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        when {
            !magic.contentEquals(MAGIC) -> null
            else -> {
                val version = buffer.get().toInt() and 0xFF
                val kdfId = buffer.get().toInt() and 0xFF
                if (version != FORMAT_VERSION || kdfId != KDF_PBKDF2_SHA256) null else {
                    val iterations = buffer.int
                    val salt = buffer.readBytes(buffer.get().toInt() and 0xFF)
                    val wrapNonce = buffer.readBytes(buffer.get().toInt() and 0xFF)
                    val wrapped = buffer.readBytes(buffer.short.toInt() and 0xFFFF)
                    val bodyNonce = buffer.readBytes(buffer.get().toInt() and 0xFF)
                    val body = buffer.readBytes(buffer.int)
                    if (iterations <= 0 || salt.isEmpty()) null else VaultFile(
                        formatVersion = version,
                        kdfId = kdfId,
                        iterations = iterations,
                        salt = salt,
                        wrappedKey = Sealed(wrapNonce, wrapped),
                        body = Sealed(bodyNonce, body)
                    )
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Is this plausibly a vault file at all?
     *
     * Used by the restore path to tell "the archive holds a vault" from "the archive holds
     * something that ended up under that name", without deriving a key to find out.
     */
    fun looksLikeVault(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size && bytes.copyOf(MAGIC.size).contentEquals(MAGIC)

    private fun ByteBuffer.readBytes(count: Int): ByteArray {
        require(count >= 0 && count <= remaining()) { "truncated" }
        return ByteArray(count).also { get(it) }
    }
}
