package com.utilities.app.messages.seal

/**
 * The Double Ratchet.
 *
 * ## What the two ratchets are for
 *
 * **The symmetric ratchet** steps a chain key forward once per message and derives a message key
 * from it. Because the step is one-way, a key recovered today says nothing about yesterday's
 * messages. That is *forward secrecy*, and it is the property that matters when a phone is lost.
 *
 * **The Diffie-Hellman ratchet** replaces the chain entirely whenever the other side sends a new
 * ratchet key, mixing a fresh agreement into the root key. Because an attacker who stole the state
 * cannot compute the next agreement without also stealing the new private key, the session *heals*:
 * they are locked out again after one round trip. That is *post-compromise recovery*, and it is the
 * property that matters when a phone was lost and got back.
 *
 * Neither is much code. What they need is care about ordering, which is where the awkwardness lives.
 *
 * ## Messages arrive out of order, and this is a text messaging app
 *
 * SMS is not a stream. Segments are reordered by the network routinely, a message can be delayed for
 * hours, and a delivery can be duplicated. So a ratchet here cannot simply refuse anything that is
 * not next:
 *
 *  - **skipped keys are kept.** When message 5 arrives before message 3, the keys for 3 and 4 are
 *    derived and stored so those messages open when they turn up;
 *  - **the store is bounded.** [MAX_SKIP] per chain, [MAX_STORED_KEYS] overall, oldest evicted
 *    first. Without a bound, a single message claiming to be number two billion would ask this to
 *    derive two billion keys, which is a denial of service that costs the sender one text;
 *  - **a key is used once.** It is removed from the store the moment it opens something, so a
 *    replayed message does not open twice.
 *
 * ## What is deliberately not here
 *
 * Header encryption. Signal's header-encrypted variant hides the message counters from anyone
 * watching the wire — and on this transport the carrier already knows who messaged whom, when, and
 * how long it was. Hiding the counter while the metadata is in the billing record would be effort
 * spent on the wrong end of the problem.
 */
class Ratchet private constructor(
    private var rootKey: ByteArray,
    private var sending: Curve25519.KeyPair,
    private var receivingPublicKey: ByteArray?,
    private var sendingChain: ByteArray?,
    private var receivingChain: ByteArray?,
    private var sent: Int,
    private var received: Int,
    private var previousChainLength: Int,
    private val skipped: LinkedHashMap<SkippedKey, ByteArray>
) {

    /** Where a skipped message key is filed: whose chain it belongs to, and which message. */
    data class SkippedKey(val ratchetKey: String, val number: Int)

    /** What a message carries in the clear so the other end knows how to open it. */
    data class Header(
        val ratchetKey: ByteArray,
        val previousChainLength: Int,
        val number: Int
    ) {
        fun encode(): ByteArray = Bytes.concat(ratchetKey, Bytes.int(previousChainLength), Bytes.int(number))

        override fun equals(other: Any?): Boolean =
            other is Header &&
                ratchetKey.contentEquals(other.ratchetKey) &&
                previousChainLength == other.previousChainLength &&
                number == other.number

        override fun hashCode(): Int =
            31 * (31 * ratchetKey.contentHashCode() + previousChainLength) + number

        companion object {
            const val BYTES = Curve25519.KEY_BYTES + 8

            fun decode(bytes: ByteArray, offset: Int = 0): Header {
                if (offset + BYTES > bytes.size) throw SealException("a header is truncated")
                return Header(
                    ratchetKey = Bytes.slice(bytes, offset, Curve25519.KEY_BYTES),
                    previousChainLength = Bytes.readInt(bytes, offset + Curve25519.KEY_BYTES),
                    number = Bytes.readInt(bytes, offset + Curve25519.KEY_BYTES + 4)
                )
            }
        }
    }

    /** A message, ready to travel. */
    data class Outgoing(val header: Header, val nonce: ByteArray, val ciphertext: ByteArray)

    // -----------------------------------------------------------------------------------------
    // Sending
    // -----------------------------------------------------------------------------------------

    /**
     * Step the sending chain and encrypt under the key that falls out of it.
     *
     * [associatedData] is the pair's identity keys ([X3dh.associatedData]); the header is appended
     * to it, so a header edited in flight makes the message fail to open rather than open as a
     * different message in the sequence.
     */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): Outgoing {
        val chain = sendingChain ?: throw SealException("this session cannot send yet")
        val (nextChain, messageKey) = stepChain(chain)
        sendingChain = nextChain

        val header = Header(
            ratchetKey = sending.publicKey,
            previousChainLength = previousChainLength,
            number = sent
        )
        sent++

        val sealed = Aead.seal(messageKey, plaintext, Bytes.concat(associatedData, header.encode()))
        Bytes.wipe(messageKey)
        return Outgoing(header = header, nonce = sealed.nonce, ciphertext = sealed.ciphertext)
    }

    // -----------------------------------------------------------------------------------------
    // Receiving
    // -----------------------------------------------------------------------------------------

    /**
     * Open a message, turning the ratchet as far as its header says to.
     *
     * The order is the specification's and each step depends on the one before: a stored key first
     * (this may be an old message finally arriving), then the DH ratchet if the sender has moved on,
     * then the skips within the current chain, then the message itself.
     */
    fun decrypt(header: Header, nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        val fullAd = Bytes.concat(associatedData, header.encode())

        storedKey(header)?.let { key ->
            val plaintext = Aead.open(key, nonce, ciphertext, fullAd)
            // Used once and gone, so a replay of the same message does not open a second time.
            skipped.remove(SkippedKey(Bytes.hex(header.ratchetKey), header.number))
            Bytes.wipe(key)
            return plaintext
        }

        val current = receivingPublicKey
        if (current == null || !current.contentEquals(header.ratchetKey)) {
            skipInChain(header.previousChainLength)
            turn(header.ratchetKey)
        }
        skipInChain(header.number)

        val chain = receivingChain ?: throw SealException("this session cannot receive yet")
        val (nextChain, messageKey) = stepChain(chain)
        receivingChain = nextChain
        received++

        val plaintext = Aead.open(messageKey, nonce, ciphertext, fullAd)
        Bytes.wipe(messageKey)
        return plaintext
    }

    /**
     * The other side sent a new ratchet key: replace both chains through the root.
     *
     * Two agreements and two root steps, in this order. The first gives the chain that opens what
     * they are sending now; the second, after generating our own new key, gives the chain we will
     * send on — so our next message already carries the ratchet forward and they can heal too.
     */
    private fun turn(theirRatchetKey: ByteArray) {
        previousChainLength = sent
        sent = 0
        received = 0
        receivingPublicKey = theirRatchetKey

        val (rootAfterReceive, receiveChain) = stepRoot(rootKey, Curve25519.agree(sending.privateKey, theirRatchetKey))
        rootKey = rootAfterReceive
        receivingChain = receiveChain

        sending = Curve25519.generateKeyPair()
        val (rootAfterSend, sendChain) = stepRoot(rootKey, Curve25519.agree(sending.privateKey, theirRatchetKey))
        rootKey = rootAfterSend
        sendingChain = sendChain
    }

    /**
     * Derive and store the keys for messages we have not seen yet in the current chain.
     *
     * The bound is the important part. `until` comes from a header somebody else wrote, and without
     * a limit a single message claiming to be number two billion would ask for two billion HMACs.
     */
    private fun skipInChain(until: Int) {
        val chain = receivingChain ?: return
        val key = receivingPublicKey ?: return
        if (until < received) return
        if (until - received > MAX_SKIP) {
            throw SealException("that message skips too far ahead to be real")
        }
        var working = chain
        var index = received
        while (index < until) {
            val (nextChain, messageKey) = stepChain(working)
            working = nextChain
            skipped[SkippedKey(Bytes.hex(key), index)] = messageKey
            index++
        }
        receivingChain = working
        received = index
        evictOldSkipped()
    }

    private fun storedKey(header: Header): ByteArray? =
        skipped[SkippedKey(Bytes.hex(header.ratchetKey), header.number)]

    /** Oldest first, which is what a `LinkedHashMap` iterates in. */
    private fun evictOldSkipped() {
        while (skipped.size > MAX_STORED_KEYS) {
            val oldest = skipped.keys.firstOrNull() ?: return
            skipped.remove(oldest)?.let { Bytes.wipe(it) }
        }
    }

    // -----------------------------------------------------------------------------------------
    // The two key derivations
    // -----------------------------------------------------------------------------------------

    /** The chain step: two HMACs under the same chain key, distinguished by one byte. */
    private fun stepChain(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val messageKey = Kdf.hmac(chainKey, byteArrayOf(0x01))
        val nextChain = Kdf.hmac(chainKey, byteArrayOf(0x02))
        return nextChain to messageKey
    }

    /** The root step: a fresh agreement mixed into the root gives a new root and a new chain. */
    private fun stepRoot(root: ByteArray, agreement: ByteArray): Pair<ByteArray, ByteArray> {
        val derived = Kdf.derive(salt = root, keyMaterial = agreement, info = ROOT_INFO, length = 64)
        return Bytes.slice(derived, 0, 32) to Bytes.slice(derived, 32, 32)
    }

    // -----------------------------------------------------------------------------------------
    // State, for the store
    // -----------------------------------------------------------------------------------------

    /** Everything that has to survive the process, which is all of it. */
    fun snapshot(): Snapshot = Snapshot(
        rootKey = rootKey.copyOf(),
        sendingPrivateKey = sending.privateKey.copyOf(),
        sendingPublicKey = sending.publicKey.copyOf(),
        receivingPublicKey = receivingPublicKey?.copyOf(),
        sendingChain = sendingChain?.copyOf(),
        receivingChain = receivingChain?.copyOf(),
        sent = sent,
        received = received,
        previousChainLength = previousChainLength,
        skipped = skipped.mapValues { it.value.copyOf() }
    )

    data class Snapshot(
        val rootKey: ByteArray,
        val sendingPrivateKey: ByteArray,
        val sendingPublicKey: ByteArray,
        val receivingPublicKey: ByteArray?,
        val sendingChain: ByteArray?,
        val receivingChain: ByteArray?,
        val sent: Int,
        val received: Int,
        val previousChainLength: Int,
        val skipped: Map<SkippedKey, ByteArray>
    ) {
        override fun equals(other: Any?): Boolean = other is Snapshot &&
            rootKey.contentEquals(other.rootKey) &&
            sendingPrivateKey.contentEquals(other.sendingPrivateKey) &&
            sent == other.sent && received == other.received &&
            previousChainLength == other.previousChainLength

        override fun hashCode(): Int = rootKey.contentHashCode()
    }

    /** Our own ratchet public key, which is what the other end will reply against. */
    val ratchetPublicKey: ByteArray get() = sending.publicKey

    /**
     * Whether the other end has actually sent us something.
     *
     * The test is the **receiving chain**, not the receiving key, and the difference is not
     * cosmetic: an initiator is given the peer's prekey as a starting ratchet key before a word has
     * been exchanged, so keying off the key would report a conversation as confirmed with somebody
     * who may not have the app at all. A receiving chain only exists once a message has turned the
     * ratchet, which is exactly the fact the lock on screen is claiming.
     */
    val established: Boolean get() = receivingChain != null

    companion object {

        private val ROOT_INFO = "Utilities ratchet v1".toByteArray(Charsets.UTF_8)

        /**
         * How far ahead one message may claim to be.
         *
         * A thousand is far more than a reordered SMS ever needs and far less than a number somebody
         * can weaponise: the work is one HMAC each, so the worst a hostile header buys is a
         * millisecond.
         */
        const val MAX_SKIP = 1000

        /** How many skipped keys are kept across all chains before the oldest are dropped. */
        const val MAX_STORED_KEYS = 2000

        /**
         * The initiator: we have their prekey and are about to send first.
         *
         * Their signed prekey stands in as the first ratchet key, which is what lets the very first
         * message be encrypted before they have said anything at all.
         */
        fun initiate(sharedSecret: ByteArray, theirSignedPreKey: ByteArray): Ratchet {
            val sending = Curve25519.generateKeyPair()
            val ratchet = Ratchet(
                rootKey = sharedSecret.copyOf(),
                sending = sending,
                receivingPublicKey = theirSignedPreKey.copyOf(),
                sendingChain = null,
                receivingChain = null,
                sent = 0,
                received = 0,
                previousChainLength = 0,
                skipped = LinkedHashMap()
            )
            val (root, chain) = ratchet.stepRoot(
                ratchet.rootKey,
                Curve25519.agree(sending.privateKey, theirSignedPreKey)
            )
            ratchet.rootKey = root
            ratchet.sendingChain = chain
            return ratchet
        }

        /**
         * The responder: a first message arrived and we derived the same secret.
         *
         * Our own signed prekey is the ratchet keypair, because that is the key the initiator
         * already agreed against. Nothing can be *sent* until their first message turns the ratchet,
         * which is correct — there is nowhere to send it to yet.
         */
        fun respond(sharedSecret: ByteArray, ourSignedPreKey: Curve25519.KeyPair): Ratchet = Ratchet(
            rootKey = sharedSecret.copyOf(),
            sending = ourSignedPreKey,
            receivingPublicKey = null,
            sendingChain = null,
            receivingChain = null,
            sent = 0,
            received = 0,
            previousChainLength = 0,
            skipped = LinkedHashMap()
        )

        /** Rebuild from a stored snapshot. */
        fun restore(snapshot: Snapshot): Ratchet = Ratchet(
            rootKey = snapshot.rootKey.copyOf(),
            sending = Curve25519.KeyPair(
                privateKey = snapshot.sendingPrivateKey.copyOf(),
                publicKey = snapshot.sendingPublicKey.copyOf()
            ),
            receivingPublicKey = snapshot.receivingPublicKey?.copyOf(),
            sendingChain = snapshot.sendingChain?.copyOf(),
            receivingChain = snapshot.receivingChain?.copyOf(),
            sent = snapshot.sent,
            received = snapshot.received,
            previousChainLength = snapshot.previousChainLength,
            skipped = LinkedHashMap(snapshot.skipped.mapValues { it.value.copyOf() })
        )
    }
}
