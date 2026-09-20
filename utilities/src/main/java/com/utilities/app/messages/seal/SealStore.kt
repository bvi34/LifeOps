package com.utilities.app.messages.seal

import android.content.Context
import com.operations.backupkit.AppId
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretRef
import com.operations.vaultkit.SecretsAccess
import com.utilities.app.messages.logic.Addresses
import java.io.File

/**
 * Where the keys live.
 *
 * ## Three kinds of thing, three different answers about backup
 *
 * **The identity.** One long-term keypair per install, and the thing a safety number is computed
 * over. It goes into the **vault** — `SecretsAccess`, the same seam Finance mirrors its bank tokens
 * through — and is deliberately kept *out* of the archive. That is the suite's existing answer to
 * "a credential that must survive a new phone but must not sit in a zip", and it is exactly right
 * here: an identity that changed on every restore would make every contact see *the keys changed*,
 * which is the one alarm that must mean something.
 *
 * **The peers' bundles.** Public keys somebody sent us. Not secret, and not backed up either —
 * because restoring a bundle without the session that goes with it produces a contact the app thinks
 * it can seal to and cannot.
 *
 * **The sessions.** Ratchet state, and the one thing here that must **never** be restored from a
 * backup. A ratchet is a counter that only goes forward; put yesterday's copy back and the phone
 * will re-derive message keys it has already used, which is the failure mode AES-GCM has no defence
 * against. So sessions live in a directory the backup contributor explicitly skips, and a restored
 * phone starts its conversations again — which costs one round trip per contact and is the correct
 * price.
 *
 * ## Why the sessions are not encrypted at rest
 *
 * They are in this app's private directory, which is already unreadable by other apps. Encrypting
 * them would need a key, and the only keys available are the Keystore (dies with the phone, which is
 * fine here) or the vault (which would mean no sealed message could be read until somebody unlocked
 * Secrets — a lock screen in front of the messaging app). The honest position is that a phone whose
 * private storage is readable has already lost, and that is the same position every messaging app
 * takes.
 */
class SealStore private constructor(context: Context) {

    private val app = context.applicationContext

    private val prefs = app.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val sessionDir = File(File(app.filesDir, PARENT_DIR), SESSION_DIR).apply { mkdirs() }

    /** This install's own keys, made on first use and never again. */
    @Volatile
    private var cached: Identity? = null

    /**
     * The identity, created if there is not one.
     *
     * Read from the vault first and from the local file second, which is the order that makes a
     * restore work: the archive did not carry the file, the vault did carry the key, and the first
     * thing that asks for an identity on the new phone puts the file back.
     */
    @Synchronized
    fun identity(): Identity {
        cached?.let { return it }

        val fromVault = SecretsAccess.read(IDENTITY_REF)?.let { decodeIdentity(it) }
        if (fromVault != null) {
            writeLocal(fromVault)
            cached = fromVault
            return fromVault
        }

        val local = prefs.getString(KEY_IDENTITY, null)?.let { decodeIdentity(it) }
        if (local != null) {
            // Filed into the vault every time it is read from the file rather than only when it is
            // made: a household that created their vault after installing this app would otherwise
            // never get the key mirrored at all.
            mirror(local)
            cached = local
            return local
        }

        val made = Identity.generate()
        writeLocal(made)
        mirror(made)
        cached = made
        return made
    }

    /** Whether this install has ever made an identity. For the settings screen, which says so. */
    fun hasIdentity(): Boolean = cached != null || prefs.contains(KEY_IDENTITY)

    /**
     * Start again with new keys.
     *
     * Everything goes: the identity, the bundles, and every session. Offered because the one
     * sensible answer to "I think this phone was compromised" is a new identity, and because a
     * feature that cannot be reset is a feature nobody can recover from.
     */
    @Synchronized
    fun reset() {
        cached = null
        prefs.edit().clear().apply()
        runCatching { sessionDir.listFiles()?.forEach { it.delete() } }
        SecretsAccess.forget(IDENTITY_REF)
    }

    // -------------------------------------------------------------------------------------
    // What we know about other people
    // -------------------------------------------------------------------------------------

    /** The bundle somebody advertised, or null if they never have. */
    fun bundleFor(address: String): PreKeyBundle? {
        val stored = prefs.getString(bundleKey(address), null) ?: return null
        return runCatching { PreKeyBundle.decode(Bytes.decode(stored)) }.getOrNull()
    }

    /**
     * Remember a bundle somebody sent.
     *
     * Returns false and changes nothing when the identity key differs from one already on file. That
     * is the case that matters: a changed identity is either a reinstall or somebody in the middle,
     * the protocol cannot tell them apart, and silently adopting the new key would make the
     * distinction unobservable. Starting again is [forget], which is a deliberate act.
     */
    fun rememberBundle(address: String, bundle: PreKeyBundle): Boolean {
        val existing = bundleFor(address)
        if (existing != null && !existing.identityKey.contentEquals(bundle.identityKey)) return false
        prefs.edit().putString(bundleKey(address), Bytes.encode(bundle.encode())).apply()
        return true
    }

    /** Whether a bundle on file disagrees with one just received — what the thread warns about. */
    fun identityChanged(address: String, bundle: PreKeyBundle): Boolean {
        val existing = bundleFor(address) ?: return false
        return !existing.identityKey.contentEquals(bundle.identityKey)
    }

    /** Throw away everything about one contact, so the next exchange starts clean. */
    fun forget(address: String) {
        prefs.edit().remove(bundleKey(address)).apply()
        runCatching { sessionFile(address).delete() }
    }

    // -------------------------------------------------------------------------------------
    // Sessions
    // -------------------------------------------------------------------------------------

    fun session(address: String): Session? {
        val stored = runCatching { sessionFile(address).takeIf { it.exists() }?.readText() }.getOrNull()
            ?: return null
        val snapshot = runCatching { SessionCodec.decode(stored) }.getOrNull() ?: return null
        return runCatching { Session.restore(identity(), snapshot) }.getOrNull()
    }

    /**
     * Write a session back.
     *
     * Every single send and receive ends here, because the ratchet has moved and a state that is not
     * saved is a message that cannot be opened next time. Written to a temporary file and renamed:
     * a phone that dies mid-write would otherwise come back with half a ratchet, and half a ratchet
     * is a conversation nobody can read.
     */
    fun save(address: String, session: Session) {
        runCatching {
            val file = sessionFile(address)
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(SessionCodec.encode(session.snapshot()))
            if (!temp.renameTo(file)) {
                file.writeText(SessionCodec.encode(session.snapshot()))
                temp.delete()
            }
        }
    }

    /** How many conversations are sealed. For the settings screen. */
    fun sessionCount(): Int = runCatching { sessionDir.listFiles()?.count { it.isFile && !it.name.endsWith(".tmp") } }
        .getOrNull() ?: 0

    /**
     * A file name for an address.
     *
     * The comparable form of the number, hashed — so the directory listing is not a list of everyone
     * the household messages privately, readable by anything that can see a file name.
     */
    private fun sessionFile(address: String): File =
        File(sessionDir, Bytes.hex(Kdf.hmac(FILE_NAME.toByteArray(), Addresses.key(address).toByteArray())).take(32))

    private fun bundleKey(address: String): String = "bundle_${Addresses.key(address)}"

    // -------------------------------------------------------------------------------------

    private fun writeLocal(identity: Identity) {
        prefs.edit().putString(KEY_IDENTITY, encodeIdentity(identity)).apply()
    }

    private fun mirror(identity: Identity) {
        SecretsAccess.remember(
            ref = IDENTITY_REF,
            value = encodeIdentity(identity),
            label = "Utilities — sealed messaging key",
            owner = SecretOwner.of(AppId.UTILITIES)
        )
    }

    private fun encodeIdentity(identity: Identity): String = listOf(
        identity.identityKey.privateKey,
        identity.identityKey.publicKey,
        identity.signedPreKey.privateKey,
        identity.signedPreKey.publicKey
    ).joinToString(".") { Bytes.encode(it) }

    private fun decodeIdentity(stored: String): Identity? = runCatching {
        val parts = stored.split(".")
        if (parts.size != 4) return null
        Identity(
            identityKey = Curve25519.KeyPair(Bytes.decode(parts[0]), Bytes.decode(parts[1])),
            signedPreKey = Curve25519.KeyPair(Bytes.decode(parts[2]), Bytes.decode(parts[3]))
        )
    }.getOrNull()

    companion object {

        /**
         * Named so the backup contributor does **not** carry it.
         *
         * The prefix that decides is `utilities`, and this does not start with it. The identity
         * travels through the vault instead — see the class note — which is the same bargain Finance
         * strikes with its bank tokens.
         */
        const val FILE_NAME = "secure_utilities_seal"

        /** Inside this app's own directory, and skipped by the sweep. See the class note. */
        const val PARENT_DIR = "utilities"
        const val SESSION_DIR = "seal"

        private const val KEY_IDENTITY = "identity"

        /** Where the identity is filed in the vault. */
        val IDENTITY_REF = SecretRef(app = AppId.UTILITIES.key, connection = "seal", name = "identity")

        @Volatile
        private var instance: SealStore? = null

        fun get(context: Context): SealStore =
            instance ?: synchronized(this) {
                instance ?: SealStore(context).also { instance = it }
            }

        fun peek(): SealStore? = instance
    }
}
