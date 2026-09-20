package com.utilities.app.messages.seal

import android.content.Context
import com.utilities.app.messages.MessagePrefs

/**
 * The one door between the messaging app and the cryptography.
 *
 * Everything in this package below this file is pure and knows nothing about phones; everything
 * above it knows nothing about ratchets. This is where a body becomes a sealed body and back, and
 * where the rule "save the session after **every** operation" is enforced in one place rather than
 * at six call sites, one of which would eventually forget.
 *
 * ## What is protected, and what is not
 *
 * This is worth stating exactly, because the honest scope is narrower than "encrypted messaging"
 * sounds and the app says so on screen too.
 *
 * **Protected: the message in transit.** What leaves the phone is ciphertext. The carrier, anyone
 * with access to the carrier's systems, and anyone intercepting the air interface see a sealed blob
 * and a base64 string. That is the threat SMS has always been wide open to and it is the one this
 * closes.
 *
 * **Not protected: the message on the phone.** A received message is decrypted and stored, in
 * plaintext, in Android's own message database — where every message has always been, and where any
 * app the household has granted SMS access to can read it.
 *
 * That is not an oversight; it follows from the decision this whole app is built on. Utilities owns
 * no data: the store is the platform's, which is what makes every takeover reversible and what means
 * switching back to another messaging app loses nothing. Keeping sealed messages out of it would
 * mean a private store, a second copy of the household's history, and a takeover that could no
 * longer be undone. And it could not be done by keeping the ciphertext either — forward secrecy
 * means the key that opened a message is destroyed as it is used, so a stored ciphertext would be
 * unreadable forever after.
 *
 * **Not protected: who, when, and how much.** The carrier still records that these two numbers
 * exchanged a message of about this length at this time. No amount of encryption over SMS changes
 * that.
 *
 * ## Conversations heal themselves
 *
 * A ratchet can legitimately go missing at either end — the other person reinstalled, or somebody
 * restored a backup, which throws the chain state away deliberately (`SealStore` argues why). Both
 * cases arrive here as a message that will not open with the session on file, and both are answered
 * the same way: if it is an *opening* message, accept a new session. Without that, one reinstall
 * would leave two people unable to write to each other with no way to tell why.
 */
class Sealing(context: Context) {

    private val app = context.applicationContext
    private val store = SealStore.get(app)

    /**
     * What the lock in the thread header says.
     *
     * Read from the **peer record**, not from the session: a ratchet that has just been torn down
     * and rebuilt — after a restore, after the other end reinstalled — has not changed anything a
     * person needs to know, and showing "not encrypted" for the second it takes to re-handshake
     * would be a lock that flickers.
     *
     * The setting is consulted last, because a household that turned sealing off wants the strip to
     * say so rather than to describe keys it is not using.
     */
    fun trustFor(address: String): Trust {
        if (!MessagePrefs(app).sealMessages) return Trust.NONE
        val peer = store.peer(address) ?: return Trust.NONE
        return if (peer.verified) Trust.VERIFIED else Trust.FIRST_USE
    }

    /** Whether a message to this address would go out sealed. */
    fun canSeal(address: String): Boolean =
        MessagePrefs(app).sealMessages && store.bundleFor(address) != null

    /**
     * Seal a message body for the wire, or return null when it cannot be.
     *
     * Null is the ordinary answer, not an error: most people do not have this app. The caller sends
     * the plaintext instead, and the thread says the conversation is not encrypted.
     *
     * A session is created here if we have their keys and have never spoken — which is what makes
     * "encrypted by default" true rather than aspirational.
     */
    fun seal(address: String, body: String): String? {
        if (!MessagePrefs(app).sealMessages) return null
        if (body.isEmpty()) return null
        return runCatching {
            val session = store.session(address)
                ?: store.bundleFor(address)?.let { Session.start(store.identity(), it) }
                ?: return null
            val payload = session.seal(body.toByteArray(Charsets.UTF_8))
            store.save(address, session)
            SealedText.wrap(payload)
        }.getOrNull()
    }

    /**
     * Open a body that arrived, or say what went wrong with it.
     *
     * [Opened.Plain] for anything that was never sealed, which is nearly everything — so that path
     * is the cheapest one and costs a substring search.
     */
    fun open(address: String, body: String): Opened {
        if (!SealedText.looksSealed(body)) return Opened.Plain(body)
        val payload = SealedText.unwrap(body) ?: return Opened.Unreadable(body)

        val envelope = runCatching { Envelope.decode(payload) }.getOrNull()
            ?: return Opened.Unreadable(body)

        // An opening message whose identity disagrees with the one on file is either a reinstall or
        // somebody in the middle. Nothing here can tell them apart, so it is refused and said out
        // loud rather than quietly adopted.
        val claimed = envelope.identityKey
        if (claimed != null && store.identityChanged(address, PreKeyBundle(claimed, claimed))) {
            return Opened.IdentityChanged(body)
        }

        // The session we already have, if it can open this. Nothing is written back unless it
        // succeeds, so a failed attempt leaves the stored ratchet exactly as it was — which is what
        // makes the fallback below safe to try.
        store.session(address)?.let { existing ->
            runCatching { String(existing.open(envelope), Charsets.UTF_8) }.getOrNull()?.let { plaintext ->
                store.save(address, existing)
                return Opened.Sealed(plaintext, trustFor(address))
            }
        }

        // It would not open with what we have. If this is an **opening** message, the other end has
        // started the conversation again — they reinstalled, or they restored a backup, which throws
        // the ratchet away on purpose (see `SealStore`). Accepting a fresh session here is what makes
        // both of those heal by themselves instead of leaving a conversation permanently dead.
        if (!envelope.opening) return Opened.Unreadable(body)

        return runCatching {
            val fresh = Session.accept(store.identity(), envelope)
            val plaintext = String(fresh.open(envelope), Charsets.UTF_8)
            store.save(address, fresh)

            // Their keys, learned from the message itself, so the *reply* can be sealed without
            // waiting for another exchange. The prekey is not in an opening envelope, so the
            // identity key stands in until a proper advert arrives — it is only ever used to start a
            // session we are not going to start, because this one already exists.
            if (claimed != null) store.rememberBundle(address, PreKeyBundle(claimed, claimed))

            Opened.Sealed(plaintext, trustFor(address))
        }.getOrElse { Opened.Unreadable(body) }
    }

    /** The sixty digits for a conversation, or null when there is nothing to compare yet. */
    fun safetyNumber(address: String): String? {
        val peer = store.bundleFor(address)?.identityKey ?: return null
        return SafetyNumber.of(store.identity().identityKey.publicKey, peer)
    }

    /** What a QR code for this conversation contains: our own identity key. */
    fun ourIdentityKey(): ByteArray = store.identity().identityKey.publicKey

    /**
     * Somebody compared the number, or scanned the code, and it matched.
     *
     * Recorded against the person rather than the conversation, so it survives every time the
     * plumbing restarts underneath it. See [Trust].
     */
    fun markVerified(address: String) = store.markVerified(address)

    /** Throw away everything about one contact, so the next exchange starts clean. */
    fun forget(address: String) = store.forget(address)

    /** How many people this install can message privately. For the settings screen. */
    fun peerCount(): Int = store.peerCount()

    /**
     * Start again with new keys.
     *
     * Everything goes: the identity, every peer record, every ratchet, and the key the store was
     * encrypted with. Offered because the only sensible answer to "I think this phone was
     * compromised" is a new identity, and because a feature nobody can reset is a feature nobody can
     * recover from. Every contact will see the keys change, which is correct — they did.
     */
    fun startAgain() = store.reset()

    /** What came out of a body that arrived. */
    sealed interface Opened {
        /** Never sealed. Nearly everything. */
        data class Plain(val body: String) : Opened

        /** Sealed, and opened. */
        data class Sealed(val body: String, val trust: Trust) : Opened

        /**
         * Sealed by somebody whose identity key is not the one on file.
         *
         * Kept distinct from [Unreadable] because it is the one failure with a cause worth naming
         * and an action attached: they reinstalled, or somebody is in the middle.
         */
        data class IdentityChanged(val body: String) : Opened

        /** Sealed and would not open: a wrong key, a replay, a truncated message, a newer version. */
        data class Unreadable(val body: String) : Opened
    }
}
