package com.utilities.app.messages.seal

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.operations.backupkit.AppId
import com.operations.securestore.SecureStore
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretRef
import com.operations.vaultkit.SecretsAccess
import java.security.SecureRandom

/**
 * The key everything in `filesDir/utilities/seal` is written under.
 *
 * ## Why the sealed store is encrypted at all
 *
 * Because it travels. The peer records — who the household has exchanged keys with, and whom they
 * have verified — are in the suite's archive, which is a zip that ends up in a cloud drive, and a
 * plaintext list of everybody somebody messages privately is exactly the kind of thing that should
 * not be sitting in one. Encrypting them means the archive is useless without the vault's
 * passphrase, which is the same bargain the vault itself strikes and the reason it exists.
 *
 * ## One key, kept in two places, for two different reasons
 *
 * The key is thirty-two random bytes and is **portable** — not derived from this phone — because a
 * key bound to one handset would make the archive unreadable on the next one, which is the failure
 * the whole vault was built to fix. It is kept:
 *
 *  - **locally**, in a store sealed by an Android Keystore key of its own, so that reading
 *    a message does not need the vault to be open. A messaging app that could not show a text until
 *    somebody had typed a passphrase would be a messaging app nobody keeps;
 *  - **in the vault**, so that a restore onto a new phone can get it back. That copy is the one that
 *    makes the archive readable, and it is the only one, which is what makes the promise above true:
 *    **the sealed store in an archive does not open until Secrets does.**
 *
 * That is the same split Secrets uses for its own convenience unlock — Keystore for the day-to-day,
 * a passphrase as the root of trust — and it is written down here as well because the two halves
 * look redundant until you ask which one survives a new phone.
 *
 * ## When the Keystore cannot be used
 *
 * Some devices' key stores become unreadable after a restore or an OS update, and `Secrets` already
 * handles that by rebuilding. Here the fallback goes one step further and writes the key to a plain
 * preferences file — named so that it is **not in the archive** — because the alternative is a phone
 * that cannot read its own sealed messages at all. What that costs is at-rest protection against
 * somebody who already has this app's private storage, which is a position from which the messages
 * are readable anyway; what it does not cost is the archive promise, because the plain file never
 * travels.
 */
class SessionCipher(context: Context) {

    private val app = context.applicationContext

    @Volatile
    private var cached: ByteArray? = null

    /**
     * The key, found or made.
     *
     * The order is the order that makes a restore work: the local copy first because it is the
     * common case and costs nothing, the vault second because after a restore it is the only copy,
     * and generating last.
     */
    @Synchronized
    fun key(): ByteArray {
        cached?.let { return it }

        readLocal()?.let { cached = it; return it }

        SecretsAccess.read(KEY_REF)?.let { stored ->
            val fromVault = runCatching { Bytes.decode(stored) }.getOrNull()?.takeIf { it.size == Aead.KEY_BYTES }
            if (fromVault != null) {
                writeLocal(fromVault)
                cached = fromVault
                return fromVault
            }
        }

        val made = ByteArray(Aead.KEY_BYTES).also { SecureRandom().nextBytes(it) }
        writeLocal(made)
        mirror(made)
        cached = made
        return made
    }

    /**
     * Make sure the vault has a copy.
     *
     * Called whenever the vault's state changes, because a household that installed this app before
     * they made a vault would otherwise have a key that never gets mirrored — and would discover it
     * on the day they restore. `SecretsAccess` queues a write made while the vault is shut, so this
     * is safe to call at any time.
     */
    fun ensureMirrored() = mirror(key())

    /** `nonce || ciphertext`, which is what a session file contains. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val sealed = Aead.seal(key(), plaintext, FILE_CONTEXT)
        return Bytes.concat(sealed.nonce, sealed.ciphertext)
    }

    /** The other way, or null for a file this key cannot open. */
    fun decrypt(bytes: ByteArray): ByteArray? {
        if (bytes.size <= Aead.NONCE_BYTES) return null
        return runCatching {
            Aead.open(
                key = key(),
                nonce = Bytes.slice(bytes, 0, Aead.NONCE_BYTES),
                ciphertext = Bytes.slice(bytes, Aead.NONCE_BYTES, bytes.size - Aead.NONCE_BYTES),
                associatedData = FILE_CONTEXT
            )
        }.getOrNull()
    }

    /** Throw the key away, which makes every sealed file unreadable. Part of starting again. */
    @Synchronized
    fun forget() {
        cached = null
        runCatching { localPrefs().edit().remove(KEY_NAME).apply() }
        runCatching { plainPrefs().edit().remove(KEY_NAME).apply() }
        SecretsAccess.forget(KEY_REF)
    }

    private fun readLocal(): ByteArray? {
        val stored = runCatching { localPrefs().getString(KEY_NAME, null) }.getOrNull()
            ?: runCatching { plainPrefs().getString(KEY_NAME, null) }.getOrNull()
            ?: return null
        return runCatching { Bytes.decode(stored) }.getOrNull()?.takeIf { it.size == Aead.KEY_BYTES }
    }

    private fun writeLocal(key: ByteArray) {
        val encoded = Bytes.encode(key)
        val stored = runCatching { localPrefs().edit().putString(KEY_NAME, encoded).commit() }.getOrDefault(false)
        if (!stored) {
            Log.w(TAG, "Keystore-backed store unusable; keeping the session key in a file the archive never carries")
            runCatching { plainPrefs().edit().putString(KEY_NAME, encoded).apply() }
        }
    }

    private fun mirror(key: ByteArray) {
        SecretsAccess.remember(
            ref = KEY_REF,
            value = Bytes.encode(key),
            label = "Utilities — sealed message store",
            owner = SecretOwner.of(AppId.UTILITIES)
        )
    }

    /**
     * The Keystore-backed store, sealed by a key of its own (see `:securestore`). A file that
     * outlived its key opens empty, and losing it is survivable in a way it is not for Secrets: the
     * key is also in the vault, so the next read finds it there and writes it back.
     */
    private fun localPrefs(): SharedPreferences = SecureStore.open(app, SECURE_PREFS)

    private fun plainPrefs(): SharedPreferences = app.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)

    companion object {

        private const val TAG = "UtilitiesSeal"

        /**
         * Both named outside the prefix the backup contributor sweeps.
         *
         * That is not tidiness — it is the whole promise. An archive that carried the sealed files
         * *and* the key that opens them would be an archive carrying the sealed files in plaintext.
         */
        const val SECURE_PREFS = "secure_utilities_seal_key"
        const val PLAIN_PREFS = "secure_utilities_seal_key_plain"

        private const val KEY_NAME = "store_key"

        /** Where the portable key lives in the vault. */
        val KEY_REF = SecretRef(app = AppId.UTILITIES.key, connection = "seal", name = "store-key")

        /**
         * Bound into every file's authentication tag, so a session file cannot be moved into another
         * app's store and opened there, and a peer record cannot be passed off as a ratchet.
         */
        private val FILE_CONTEXT = "utilities-seal-store-v1".toByteArray(Charsets.UTF_8)
    }
}
