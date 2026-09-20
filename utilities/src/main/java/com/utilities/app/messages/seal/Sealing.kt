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
 */
class Sealing(context: Context) {

    private val app = context.applicationContext
    private val store = SealStore.get(app)

    /** What the lock in the thread header says. */
    fun trustFor(address: String): Trust {
        val session = store.session(address)
        if (session != null) return session.trustLevel
        return if (store.bundleFor(address) != null) Trust.FIRST_USE else Trust.NONE
    }

    /** Whether a message to this address would go out sealed. */
    fun canSeal(address: String): Boolean =
        MessagePrefs(app).sealMessages && (store.session(address) != null || store.bundleFor(address) != null)

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

        return runCatching {
            val envelope = Envelope.decode(payload)

            // An opening message whose identity disagrees with the one on file is either a reinstall
            // or somebody in the middle. Nothing here can tell them apart, so it is refused and said
            // out loud rather than quietly adopted.
            val claimed = envelope.identityKey
            if (claimed != null) {
                val known = store.bundleFor(address)
                if (known != null && !known.identityKey.contentEquals(claimed)) {
                    return Opened.IdentityChanged(body)
                }
            }

            val session = store.session(address)
                ?: Session.accept(store.identity(), envelope)

            val plaintext = String(session.open(envelope), Charsets.UTF_8)
            store.save(address, session)

            // A session that arrived from a stranger implies their bundle; remembering it is what
            // lets the *reply* be sealed without waiting for another exchange.
            if (claimed != null && store.bundleFor(address) == null) {
                store.rememberBundle(address, PreKeyBundle(claimed, claimed))
            }
            Opened.Sealed(plaintext, session.trustLevel)
        }.getOrElse { Opened.Unreadable(body) }
    }

    /** The sixty digits for a conversation, or null when there is nothing to compare yet. */
    fun safetyNumber(address: String): String? {
        val peer = store.session(address)?.peerIdentityKey
            ?: store.bundleFor(address)?.identityKey
            ?: return null
        return SafetyNumber.of(store.identity().identityKey.publicKey, peer)
    }

    /** What a QR code for this conversation contains: our own identity key. */
    fun ourIdentityKey(): ByteArray = store.identity().identityKey.publicKey

    /** Somebody compared the number, or scanned the code, and it matched. */
    fun markVerified(address: String) {
        val session = store.session(address) ?: return
        session.markVerified()
        store.save(address, session)
    }

    /** Throw away everything about one contact, so the next exchange starts clean. */
    fun forget(address: String) = store.forget(address)

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
