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
 * ## Everything here is encrypted, and travels
 *
 * Every file in `filesDir/utilities/seal` is written under a portable key that is kept locally
 * behind the Android Keystore *and* mirrored into the suite's vault — see [SessionCipher]. That is
 * what makes the archive safe to carry them in: the sealed store in a backup **does not open until
 * Secrets does**, on the same terms as the vault's own payload, while day-to-day reading needs
 * nothing unlocked.
 *
 * Two kinds of file, distinguished by a filename prefix because that is the mechanism the rest of
 * this app already uses to decide what a backup carries:
 *
 * **`peer-…` — what is known about a person.** Their identity key, their prekey, and whether
 * anybody has verified them. Carried by the archive and **restored**, because this is the part with
 * durable value: verification cost a human being a phone call, and losing it on every restore would
 * mean being asked to verify Ada again for reasons nobody can see.
 *
 * **`ratchet-…` — the live chain state.** Deliberately **not carried**, and this is the one exclusion
 * in the app that is about correctness rather than tidiness.
 *
 * ## Why a ratchet is not resumed
 *
 * Not squeamishness about key hygiene — it would break the conversation, permanently.
 *
 * A Double Ratchet is a counter that only goes forward. Restore last week's copy and the sending
 * chain is rewound: the next message out is encrypted with a key the other end consumed days ago and
 * has no way back to, so they cannot open it. Their replies have moved the root key on, so the chain
 * this phone derives for *receiving* is not the one they are sending under either. Nothing about
 * that self-heals — it is a conversation that is silently dead in both directions, which is very
 * much worse than one round trip of setup. It would also rewind the used-key store, quietly
 * returning replay protection for every message in the window.
 *
 * Carrying it and then refusing to put it back would be worse than leaving it out: it would break
 * the suite's own rule that everything in an archive comes back byte for byte, which
 * `BackupCoverageTest` enforces. So it is left out, by filename prefix, with the reason on the
 * census's excluded list where a reader will find it.
 *
 * Nothing visible is lost. The first message after a restore starts a fresh handshake, which takes
 * one message and no interaction, and the peer record it starts from came back intact — so the
 * conversation is still encrypted, still verified, and the household notices nothing. Both ends heal
 * without being told to: see `Sealing`, which accepts a new session when an opening message arrives
 * for one it cannot open.
 *
 * ## The identity
 *
 * One long-term keypair per install, and the thing a safety number is computed over. It is **not**
 * in `seal/`: it lives in a preferences file named outside the swept prefix and is carried by the
 * vault instead, on the same terms Finance's bank tokens are. An identity that changed on every
 * restore would make every contact see *the keys changed*, which is the one alarm in this app that
 * has to mean something.
 */
class SealStore private constructor(context: Context) {

    private val app = context.applicationContext

    private val prefs = app.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val cipher = SessionCipher(app)

    private val sealDir = File(File(app.filesDir, PARENT_DIR), SESSION_DIR).apply { mkdirs() }

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
        // The store key is made at the same moment, so a household that has a vault gets both
        // mirrored in one go rather than the second one waiting for a message to be sent.
        cipher.ensureMirrored()
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
        runCatching { sealDir.listFiles()?.forEach { it.delete() } }
        SecretsAccess.forget(IDENTITY_REF)
        cipher.forget()
    }

    // -------------------------------------------------------------------------------------
    // What we know about other people
    // -------------------------------------------------------------------------------------

    /**
     * What is known about one person: their keys, and whether anybody has checked them.
     *
     * Verification lives here rather than on a session because ratchets are torn down and rebuilt
     * routinely and verification is the one thing in this app that cost a human being something.
     */
    data class Peer(val bundle: PreKeyBundle, val verified: Boolean) {
        fun encode(): String = "${Bytes.encode(bundle.encode())}\n${if (verified) "verified" else "unverified"}"

        companion object {
            fun decode(text: String): Peer? = runCatching {
                val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
                Peer(
                    bundle = PreKeyBundle.decode(Bytes.decode(lines[0])),
                    verified = lines.getOrNull(1) == "verified"
                )
            }.getOrNull()
        }
    }

    fun peer(address: String): Peer? =
        readEncrypted(peerFile(address))?.let { Peer.decode(String(it, Charsets.UTF_8)) }

    /** The bundle somebody advertised, or null if they never have. */
    fun bundleFor(address: String): PreKeyBundle? = peer(address)?.bundle

    /** Whether anybody has compared the safety number with this contact. */
    fun isVerified(address: String): Boolean = peer(address)?.verified == true

    /**
     * Remember a bundle somebody sent.
     *
     * Returns false and changes nothing when the identity key differs from one already on file. That
     * is the case that matters: a changed identity is either a reinstall or somebody in the middle,
     * the protocol cannot tell them apart, and silently adopting the new key would make the
     * distinction unobservable. Starting again is [forget], which is a deliberate act.
     */
    fun rememberBundle(address: String, bundle: PreKeyBundle): Boolean {
        val existing = peer(address)
        if (existing != null && !existing.bundle.identityKey.contentEquals(bundle.identityKey)) return false
        // Verification carries across a prekey rotation from the same identity, because it was the
        // identity that was verified.
        writeEncrypted(peerFile(address), Peer(bundle, existing?.verified == true).encode().toByteArray())
        return true
    }

    /** Somebody compared the safety number and it matched. */
    fun markVerified(address: String) {
        val existing = peer(address) ?: return
        writeEncrypted(peerFile(address), existing.copy(verified = true).encode().toByteArray())
    }

    /** Whether a bundle on file disagrees with one just received — what the thread warns about. */
    fun identityChanged(address: String, bundle: PreKeyBundle): Boolean {
        val existing = peer(address) ?: return false
        return !existing.bundle.identityKey.contentEquals(bundle.identityKey)
    }

    /** Throw away everything about one contact, so the next exchange starts clean. */
    fun forget(address: String) {
        runCatching { peerFile(address).delete() }
        runCatching { ratchetFile(address).delete() }
    }

    // -------------------------------------------------------------------------------------
    // Sessions
    // -------------------------------------------------------------------------------------

    fun session(address: String): Session? {
        val stored = readEncrypted(ratchetFile(address)) ?: return null
        val snapshot = runCatching { SessionCodec.decode(String(stored, Charsets.UTF_8)) }.getOrNull() ?: return null
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
        writeEncrypted(ratchetFile(address), SessionCodec.encode(session.snapshot()).toByteArray())
    }

    /** Throw away one conversation's chain state, keeping what is known about the person. */
    fun resetRatchet(address: String) {
        runCatching { ratchetFile(address).delete() }
    }

    /** How many people this install can message privately. For the settings screen. */
    fun peerCount(): Int =
        runCatching { sealDir.listFiles()?.count { it.isFile && it.name.startsWith(PEER_PREFIX) } }.getOrNull() ?: 0

    // -------------------------------------------------------------------------------------
    // The files
    // -------------------------------------------------------------------------------------

    private fun peerFile(address: String): File = File(sealDir, PEER_PREFIX + handle(address))

    private fun ratchetFile(address: String): File = File(sealDir, RATCHET_PREFIX + handle(address))

    /**
     * A file name for an address.
     *
     * The comparable form of the number, keyed-hashed — so a directory listing is not a list of
     * everyone the household messages privately, readable by anything that can see a file name.
     */
    private fun handle(address: String): String =
        Bytes.hex(Kdf.hmac(FILE_NAME.toByteArray(), Addresses.key(address).toByteArray())).take(32)

    private fun readEncrypted(file: File): ByteArray? = runCatching {
        if (!file.exists()) return null
        cipher.decrypt(file.readBytes())
    }.getOrNull()

    /**
     * Encrypt and write, through a temporary file and a rename.
     *
     * A phone that dies mid-write would otherwise come back with half a ratchet, and half a ratchet
     * is a conversation nobody can read.
     */
    private fun writeEncrypted(file: File, plaintext: ByteArray) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeBytes(cipher.encrypt(plaintext))
            if (!temp.renameTo(file)) {
                file.writeBytes(cipher.encrypt(plaintext))
                temp.delete()
            }
        }
    }

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

        /** Inside this app's own directory. Carried by the archive, encrypted. See the class note. */
        const val PARENT_DIR = "utilities"
        const val SESSION_DIR = "seal"

        /** What is known about a person: restored from a backup. */
        const val PEER_PREFIX = "peer-"

        /** A live chain: carried, and deliberately not restored. See the class note. */
        const val RATCHET_PREFIX = "ratchet-"

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
