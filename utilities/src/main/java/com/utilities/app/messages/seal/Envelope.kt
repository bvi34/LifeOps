package com.utilities.app.messages.seal

/**
 * What a sealed message looks like on the wire.
 *
 * ```
 *   0  version        1 byte   always 1
 *   1  kind           1 byte   1 = opening, 2 = ordinary
 *   2  identity      32 bytes  } only on an opening message
 *  34  ephemeral     32 bytes  }
 *      ratchet key   32 bytes  \
 *      previous       4 bytes   > the ratchet header
 *      number         4 bytes  /
 *      nonce         12 bytes
 *      ciphertext     n bytes  with its authentication tag
 * ```
 *
 * ## Why the opening keys are not in the authenticated data
 *
 * They look unprotected and are not, which is worth explaining because the obvious "fix" is to add
 * them to the AEAD's associated data and it would be redundant.
 *
 * Both keys are **inputs to the shared secret**. Substitute either and the responder derives a
 * different secret, every key that falls out of the ratchet is different, and the message does not
 * open. They are bound by the arithmetic rather than by the tag — and the identity keys are in the
 * associated data as well, through [X3dh.associatedData], because that binding has a second job:
 * stopping a message being lifted out of one conversation and replayed into another.
 *
 * ## Overhead
 *
 * Eighty-nine bytes on an ordinary message, a hundred and fifty-three on the first. Base64 makes
 * that about 120 and 204 characters — which is why a sealed message of any length costs one extra
 * SMS segment, and why the opening one costs two. That is the price of the feature and it is
 * cheaper than the alternative, which is a picture message per text.
 */
object Envelope {

    const val VERSION = 1

    /** The first message of a session, carrying what the other end needs to derive the secret. */
    const val KIND_OPENING = 1

    /** Every message after it. */
    const val KIND_ORDINARY = 2

    private const val HEADER_OFFSET_ORDINARY = 2
    private const val HEADER_OFFSET_OPENING = 2 + Curve25519.KEY_BYTES * 2

    /** A decoded envelope, before anything has been opened. */
    data class Parsed(
        val kind: Int,
        val identityKey: ByteArray?,
        val ephemeralKey: ByteArray?,
        val header: Ratchet.Header,
        val nonce: ByteArray,
        val ciphertext: ByteArray
    ) {
        val opening: Boolean get() = kind == KIND_OPENING

        override fun equals(other: Any?): Boolean = other is Parsed &&
            kind == other.kind && header == other.header &&
            nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)

        override fun hashCode(): Int = 31 * kind + header.hashCode()
    }

    fun encodeOpening(
        identityKey: ByteArray,
        ephemeralKey: ByteArray,
        message: Ratchet.Outgoing
    ): ByteArray = Bytes.concat(
        byteArrayOf(VERSION.toByte(), KIND_OPENING.toByte()),
        identityKey,
        ephemeralKey,
        message.header.encode(),
        message.nonce,
        message.ciphertext
    )

    fun encodeOrdinary(message: Ratchet.Outgoing): ByteArray = Bytes.concat(
        byteArrayOf(VERSION.toByte(), KIND_ORDINARY.toByte()),
        message.header.encode(),
        message.nonce,
        message.ciphertext
    )

    /**
     * Read one.
     *
     * Every length is checked against what is actually there, because this is the first thing that
     * touches bytes somebody else wrote. A version this build does not know is refused with a
     * sentence rather than parsed hopefully — a future format read as this one would decrypt to
     * nothing and look like tampering.
     */
    fun decode(bytes: ByteArray): Parsed {
        if (bytes.size < 4) throw SealException("that is too short to be a sealed message")
        val version = bytes[0].toInt() and 0xFF
        if (version != VERSION) throw SealException("that sealed message was written by a newer version")

        val kind = bytes[1].toInt() and 0xFF
        if (kind != KIND_OPENING && kind != KIND_ORDINARY) throw SealException("unknown sealed message kind")

        val opening = kind == KIND_OPENING
        val headerAt = if (opening) HEADER_OFFSET_OPENING else HEADER_OFFSET_ORDINARY
        val minimum = headerAt + Ratchet.Header.BYTES + Aead.NONCE_BYTES + Aead.TAG_BYTES
        if (bytes.size < minimum) throw SealException("that sealed message is truncated")

        val identityKey = if (opening) Bytes.slice(bytes, 2, Curve25519.KEY_BYTES) else null
        val ephemeralKey =
            if (opening) Bytes.slice(bytes, 2 + Curve25519.KEY_BYTES, Curve25519.KEY_BYTES) else null

        val header = Ratchet.Header.decode(bytes, headerAt)
        val nonceAt = headerAt + Ratchet.Header.BYTES
        val nonce = Bytes.slice(bytes, nonceAt, Aead.NONCE_BYTES)
        val cipherAt = nonceAt + Aead.NONCE_BYTES

        return Parsed(
            kind = kind,
            identityKey = identityKey,
            ephemeralKey = ephemeralKey,
            header = header,
            nonce = nonce,
            ciphertext = Bytes.slice(bytes, cipherAt, bytes.size - cipherAt)
        )
    }
}

/**
 * A sealed message, as it travels inside an ordinary text.
 *
 * SMS carries characters, not bytes, so a sealed payload is base64 behind a marker. The marker is
 * doing three jobs and each shaped it:
 *
 *  - **the receiving app has to recognise one** without a header to put a flag in, so it is a
 *    literal prefix and a version digit;
 *  - **somebody without the app has to be told what they are looking at**, or a line of base64 from
 *    a friend reads as a compromised phone. Hence the sentence;
 *  - **it has to survive the transport**, so it is plain ASCII with no characters a gateway
 *    transliterates.
 *
 * The sentence costs about half an SMS segment on every sealed message, which is a real price paid
 * for the one person in the conversation who cannot read it.
 */
object SealedText {

    const val MARKER = "[sealed:1]"

    private const val EXPLANATION =
        "This message is encrypted. Open it with Utilities."

    fun wrap(payload: ByteArray): String = "$EXPLANATION\n$MARKER${Bytes.encode(payload)}"

    /** Whether a message body is one of ours. Cheap, because it runs on every text that arrives. */
    fun looksSealed(body: String): Boolean = body.contains(MARKER)

    /**
     * The payload out of a body, or null when there is not one.
     *
     * Tolerant about what surrounds the marker on purpose: a body that has been through a gateway
     * may have had whitespace changed, and some clients append their own footer. What is taken is
     * the run of base64 characters after the marker and nothing else.
     */
    fun unwrap(body: String): ByteArray? {
        val at = body.indexOf(MARKER)
        if (at < 0) return null
        val payload = body.substring(at + MARKER.length).trimStart()
        val end = payload.indexOfFirst { !isBase64Char(it) }
        val encoded = if (end < 0) payload else payload.substring(0, end)
        if (encoded.isEmpty()) return null
        return runCatching { Bytes.decode(encoded) }.getOrNull()
    }

    private fun isBase64Char(c: Char): Boolean =
        c.isLetterOrDigit() || c == '-' || c == '_' || c == '=' || c == '+' || c == '/'
}
