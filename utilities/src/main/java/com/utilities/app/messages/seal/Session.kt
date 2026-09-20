package com.utilities.app.messages.seal

/**
 * How far one conversation has got, and what the lock on screen means.
 *
 * Three states, and the middle one is the honest part of this design. **Trust on first use** works
 * without anybody doing anything, which is what makes encryption-by-default possible at all — and
 * it cannot rule out an attacker who was present for the very first exchange. So it does not claim
 * to. A session nobody has verified says *encrypted, unverified*, and the way to promote it is on
 * the screen next to it.
 */
enum class Trust {
    /** No keys yet. Messages go in the clear and the thread says so. */
    NONE,

    /** Keys exchanged and messages sealed, but the first exchange has not been checked. */
    FIRST_USE,

    /** The safety number has been compared. */
    VERIFIED
}

/**
 * One conversation's cryptographic state.
 *
 * A thin wrapper over [Ratchet] holding the things the ratchet has no opinion about: whose keys
 * these are, whether anybody has verified them, and whether the first message has been sent yet.
 *
 * ## The identity-key change, which is the case that matters
 *
 * If a peer's identity key turns up different from the one on file, one of two things has happened:
 * they reinstalled, or somebody is in the middle. The protocol cannot tell them apart and neither
 * can this class — so it refuses to silently adopt the new key. The session goes to
 * [Trust.NONE], the thread says the keys changed, and starting again is a deliberate act. Signal
 * learned this the hard way and so has everybody who quietly re-pinned.
 */
class Session private constructor(
    val peerIdentityKey: ByteArray,
    private val ourIdentity: Identity,
    private var ratchet: Ratchet,
    private var trust: Trust,
    /** Set until the opening message has been sent, because it has to carry these. */
    private var openingEphemeral: ByteArray?
) {

    val trustLevel: Trust get() = trust

    /** Whether the other end has answered. Until then the session is ours alone. */
    val confirmed: Boolean get() = ratchet.established

    /** The sixty digits, for the verification screen. */
    fun safetyNumber(): String = SafetyNumber.of(ourIdentity.identityKey.publicKey, peerIdentityKey)

    /** Somebody compared the number and it matched. */
    fun markVerified() {
        trust = Trust.VERIFIED
    }

    /**
     * Seal a message.
     *
     * The first one out carries our identity and the ephemeral key the shared secret was built from;
     * everything after it is an ordinary envelope. The switch happens on the way out rather than by
     * counting messages, because "have they answered" is a fact the ratchet already knows and a
     * counter is a thing that can be wrong.
     */
    fun seal(plaintext: ByteArray): ByteArray {
        val associatedData = X3dh.associatedData(ourIdentity.identityKey.publicKey, peerIdentityKey)
        val message = ratchet.encrypt(plaintext, associatedData)
        val ephemeral = openingEphemeral
        return if (ephemeral != null) {
            Envelope.encodeOpening(
                identityKey = ourIdentity.identityKey.publicKey,
                ephemeralKey = ephemeral,
                message = message
            )
        } else {
            Envelope.encodeOrdinary(message)
        }
    }

    /**
     * Open a message.
     *
     * Once one opens, the opening keys stop being attached to ours: they were only ever there so the
     * other end could derive the secret, and they have plainly managed it.
     */
    fun open(envelope: Envelope.Parsed): ByteArray {
        val associatedData = X3dh.associatedData(ourIdentity.identityKey.publicKey, peerIdentityKey)
        val plaintext = ratchet.decrypt(
            header = envelope.header,
            nonce = envelope.nonce,
            ciphertext = envelope.ciphertext,
            associatedData = associatedData
        )
        openingEphemeral = null
        return plaintext
    }

    /** Everything that has to survive the process. */
    fun snapshot(): Snapshot = Snapshot(
        peerIdentityKey = peerIdentityKey.copyOf(),
        trust = trust,
        openingEphemeral = openingEphemeral?.copyOf(),
        ratchet = ratchet.snapshot()
    )

    data class Snapshot(
        val peerIdentityKey: ByteArray,
        val trust: Trust,
        val openingEphemeral: ByteArray?,
        val ratchet: Ratchet.Snapshot
    ) {
        override fun equals(other: Any?): Boolean = other is Snapshot &&
            peerIdentityKey.contentEquals(other.peerIdentityKey) && trust == other.trust

        override fun hashCode(): Int = 31 * peerIdentityKey.contentHashCode() + trust.ordinal
    }

    companion object {

        /**
         * Start one because we have their bundle and something to say.
         *
         * Nothing is transmitted by this. The session can encrypt immediately — that is the point of
         * a prekey — and the other end learns of it when the first message lands.
         */
        fun start(ourIdentity: Identity, theirBundle: PreKeyBundle): Session {
            val ephemeral = Curve25519.generateKeyPair()
            val secret = X3dh.initiate(
                ourIdentity = ourIdentity.identityKey,
                ephemeral = ephemeral,
                theirBundle = theirBundle
            )
            return Session(
                peerIdentityKey = theirBundle.identityKey.copyOf(),
                ourIdentity = ourIdentity,
                ratchet = Ratchet.initiate(secret, theirBundle.signedPreKey),
                trust = Trust.FIRST_USE,
                openingEphemeral = ephemeral.publicKey
            )
        }

        /**
         * Start one because a sealed message arrived from somebody we have no session with.
         *
         * Throws if the envelope is not an opening message — an ordinary one from a stranger cannot
         * be opened and cannot be turned into a session, which is the correct answer to somebody
         * replaying half a conversation.
         */
        fun accept(ourIdentity: Identity, envelope: Envelope.Parsed): Session {
            if (!envelope.opening) throw SealException("that message does not start a conversation")
            val theirIdentity = envelope.identityKey ?: throw SealException("that message has no identity")
            val theirEphemeral = envelope.ephemeralKey ?: throw SealException("that message has no ephemeral key")

            val secret = X3dh.respond(
                ourIdentity = ourIdentity.identityKey,
                ourSignedPreKey = ourIdentity.signedPreKey,
                theirIdentityKey = theirIdentity,
                theirEphemeralKey = theirEphemeral
            )
            return Session(
                peerIdentityKey = theirIdentity.copyOf(),
                ourIdentity = ourIdentity,
                ratchet = Ratchet.respond(secret, ourIdentity.signedPreKey),
                trust = Trust.FIRST_USE,
                openingEphemeral = null
            )
        }

        fun restore(ourIdentity: Identity, snapshot: Snapshot): Session = Session(
            peerIdentityKey = snapshot.peerIdentityKey.copyOf(),
            ourIdentity = ourIdentity,
            ratchet = Ratchet.restore(snapshot.ratchet),
            trust = snapshot.trust,
            openingEphemeral = snapshot.openingEphemeral?.copyOf()
        )
    }
}
