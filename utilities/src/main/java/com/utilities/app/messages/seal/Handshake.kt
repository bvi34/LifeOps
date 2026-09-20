package com.utilities.app.messages.seal

/**
 * What one install publishes about itself so another can start talking to it privately.
 *
 * Two public keys and nothing else: a long-term **identity key**, which is what a safety number is
 * computed over and what verification pins, and a medium-term **signed prekey**, which exists so
 * that the first message can be encrypted without the recipient being online to negotiate.
 *
 * ## Why the prekey carries no signature
 *
 * X3DH as specified signs the prekey with the identity key, and this does not. That is a deliberate
 * subtraction rather than an omission, and the argument is about what the signature would buy here:
 *
 * The signature exists so a bundle fetched from a **server** can be trusted to belong to the
 * identity key it claims. There is no server. A bundle arrives over the air, from the same place the
 * identity key arrived, in the same message — an attacker who can substitute the prekey can
 * substitute the identity key beside it, and a signature by the substituted identity key would
 * verify perfectly. It defends against a threat this design does not have.
 *
 * What *does* defend against that attacker is the thing the household can do: compare the safety
 * number, which covers both identity keys ([SafetyNumber]). Until they have, the session is
 * trust-on-first-use and the app says so rather than showing a lock.
 *
 * The cost of the subtraction is an entire signature scheme — XEdDSA over Curve25519 — that would
 * otherwise have to be written, tested and got right.
 *
 * A consequence worth naming: a prekey is never rotated for an existing session. The three DHs below
 * are only ever used once, to start one; afterwards the ratchet has taken over and the prekey does
 * nothing.
 */
data class PreKeyBundle(
    val identityKey: ByteArray,
    val signedPreKey: ByteArray
) {
    init {
        if (identityKey.size != Curve25519.KEY_BYTES) throw SealException("an identity key is the wrong size")
        if (signedPreKey.size != Curve25519.KEY_BYTES) throw SealException("a prekey is the wrong size")
    }

    fun encode(): ByteArray = Bytes.concat(identityKey, signedPreKey)

    override fun equals(other: Any?): Boolean =
        other is PreKeyBundle &&
            identityKey.contentEquals(other.identityKey) &&
            signedPreKey.contentEquals(other.signedPreKey)

    override fun hashCode(): Int = 31 * identityKey.contentHashCode() + signedPreKey.contentHashCode()

    companion object {
        const val BYTES = Curve25519.KEY_BYTES * 2

        fun decode(bytes: ByteArray): PreKeyBundle {
            if (bytes.size != BYTES) throw SealException("a bundle is ${bytes.size} bytes, not $BYTES")
            return PreKeyBundle(
                identityKey = Bytes.slice(bytes, 0, Curve25519.KEY_BYTES),
                signedPreKey = Bytes.slice(bytes, Curve25519.KEY_BYTES, Curve25519.KEY_BYTES)
            )
        }
    }
}

/** This install's own long-term keys. The private halves never leave the device. */
data class Identity(
    val identityKey: Curve25519.KeyPair,
    val signedPreKey: Curve25519.KeyPair
) {
    fun bundle(): PreKeyBundle = PreKeyBundle(
        identityKey = identityKey.publicKey,
        signedPreKey = signedPreKey.publicKey
    )

    companion object {
        fun generate(): Identity = Identity(
            identityKey = Curve25519.generateKeyPair(),
            signedPreKey = Curve25519.generateKeyPair()
        )
    }
}

/**
 * X3DH: turning two bundles and an ephemeral key into one shared secret neither side transmitted.
 *
 * Three Diffie-Hellmans, and each one is there for a different reason — which is the whole design
 * and is worth spelling out, because a reader who does not know why will eventually "simplify" it
 * to one:
 *
 *  - **DH1 = identity × their prekey** authenticates *us* to them. Only the holder of our identity
 *    private key could compute it.
 *  - **DH2 = ephemeral × their identity** authenticates *them* to us, for the same reason in
 *    reverse.
 *  - **DH3 = ephemeral × their prekey** supplies the freshness. Without it, two sessions between
 *    the same pair of long-term keys would derive the same secret forever.
 *
 * The 32 leading `0xFF` bytes are from the specification. They exist so that the input to the KDF
 * can never be confused with a raw curve point on a curve where the encoding is ambiguous, and are
 * kept because interoperating with the spec's test expectations is cheaper than arguing about it.
 */
object X3dh {

    private const val SECRET_BYTES = 32

    /** The domain separator. Changing this string makes every existing session unopenable. */
    private val INFO = "Utilities sealed messages v1".toByteArray(Charsets.UTF_8)

    private val PREFIX = ByteArray(32) { 0xFF.toByte() }

    /**
     * The initiator's half: we have their bundle and are about to send the first message.
     *
     * [ephemeral] is generated per session and its public half travels in that first message, which
     * is what lets the responder compute the same secret without ever having spoken to us.
     */
    fun initiate(
        ourIdentity: Curve25519.KeyPair,
        ephemeral: Curve25519.KeyPair,
        theirBundle: PreKeyBundle
    ): ByteArray {
        val dh1 = Curve25519.agree(ourIdentity.privateKey, theirBundle.signedPreKey)
        val dh2 = Curve25519.agree(ephemeral.privateKey, theirBundle.identityKey)
        val dh3 = Curve25519.agree(ephemeral.privateKey, theirBundle.signedPreKey)
        return derive(dh1, dh2, dh3)
    }

    /**
     * The responder's half: a first message arrived carrying their identity and ephemeral keys.
     *
     * The same three agreements from the other side, in the same order — which is the part that is
     * easy to get wrong, because the *roles* of the keys swap while the order must not.
     */
    fun respond(
        ourIdentity: Curve25519.KeyPair,
        ourSignedPreKey: Curve25519.KeyPair,
        theirIdentityKey: ByteArray,
        theirEphemeralKey: ByteArray
    ): ByteArray {
        val dh1 = Curve25519.agree(ourSignedPreKey.privateKey, theirIdentityKey)
        val dh2 = Curve25519.agree(ourIdentity.privateKey, theirEphemeralKey)
        val dh3 = Curve25519.agree(ourSignedPreKey.privateKey, theirEphemeralKey)
        return derive(dh1, dh2, dh3)
    }

    private fun derive(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray): ByteArray =
        Kdf.derive(
            salt = ByteArray(Kdf.HASH_BYTES),
            keyMaterial = Bytes.concat(PREFIX, dh1, dh2, dh3),
            info = INFO,
            length = SECRET_BYTES
        )

    /**
     * What every message in a session is authenticated against: both identity keys, in a fixed
     * order.
     *
     * Fixed by **sorting**, not by who spoke first, so the two ends compute the same bytes without
     * having to agree on who the initiator was. Binding the identities into the AEAD is what stops a
     * message being lifted out of one conversation and replayed into another.
     */
    fun associatedData(ourIdentityKey: ByteArray, theirIdentityKey: ByteArray): ByteArray {
        val ours = Bytes.hex(ourIdentityKey)
        val theirs = Bytes.hex(theirIdentityKey)
        return if (ours <= theirs) Bytes.concat(ourIdentityKey, theirIdentityKey)
        else Bytes.concat(theirIdentityKey, ourIdentityKey)
    }
}
