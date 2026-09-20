package com.utilities.app.messages.pdu

import java.io.ByteArrayOutputStream

/** What a malformed PDU throws. Always caught at the edge — see [PduDecoder]. */
class PduException(message: String) : Exception(message)

/**
 * Reading WSP's primitives out of a byte array.
 *
 * Every method here is one production from WAP-230 §8.4.2, and the awkwardness is all in one place:
 * WSP has no type tags. Whether the next field is a number, a string or a length is decided by the
 * *value* of the next byte, against the boundaries in [Wsp]. Get that comparison wrong by one and a
 * message decodes into plausible nonsense rather than failing, which is why the boundaries are named
 * constants and why [PduDecoderTest] feeds this real byte sequences rather than mocks.
 *
 * Defensive by construction: a read past the end throws [PduException] rather than an
 * `ArrayIndexOutOfBoundsException`, because the input is a byte array that **arrived over the
 * radio** from a stranger and the decoder's contract is to fail rather than to crash the app that is
 * receiving somebody's holiday photograph.
 */
class PduReader(private val bytes: ByteArray, start: Int = 0) {

    var position: Int = start
        private set

    /** How many octets the whole buffer holds — what every claimed length is checked against. */
    val size: Int get() = bytes.size

    val remaining: Int get() = bytes.size - position

    fun hasMore(): Boolean = position < bytes.size

    /** The next byte's value without consuming it, or -1 at the end. */
    fun peek(): Int = if (position < bytes.size) bytes[position].toInt() and 0xFF else -1

    fun byte(): Int {
        if (position >= bytes.size) throw PduException("read past the end of the pdu")
        return bytes[position++].toInt() and 0xFF
    }

    fun skip(count: Int) {
        if (count < 0 || position + count > bytes.size) throw PduException("skip past the end")
        position += count
    }

    fun seek(to: Int) {
        if (to < 0 || to > bytes.size) throw PduException("seek outside the pdu")
        position = to
    }

    fun take(count: Int): ByteArray {
        if (count < 0 || position + count > bytes.size) throw PduException("take past the end")
        return bytes.copyOfRange(position, position + count).also { position += count }
    }

    /**
     * A variable-length unsigned integer: seven bits per byte, high bit set on every byte but the
     * last. This is how every length in a multipart body is written.
     */
    fun uintvar(): Int {
        var value = 0
        var read = 0
        while (true) {
            val b = byte()
            value = (value shl 7) or (b and 0x7F)
            read++
            if (b and 0x80 == 0) break
            // Five septets is 35 bits; anything longer is not a length, it is a corrupt stream.
            if (read > 5) throw PduException("uintvar too long")
        }
        return value
    }

    /** A value carried in the low seven bits of one byte, marked by the high bit. */
    fun shortInteger(): Int {
        val b = byte()
        if (b and 0x80 == 0) throw PduException("expected a short integer, got $b")
        return b and 0x7F
    }

    /** A length byte followed by that many big-endian bytes. Up to 30, per the spec. */
    fun longInteger(): Long {
        val length = byte()
        if (length > 30) throw PduException("long integer claims $length octets")
        var value = 0L
        repeat(length) { value = (value shl 8) or (byte().toLong() and 0xFF) }
        return value
    }

    /** Either form — which is decided, as always, by whether the high bit is set. */
    fun integer(): Long = if (peek() >= Wsp.SHORT_INTEGER) shortInteger().toLong() else longInteger()

    /**
     * How many octets the following value occupies.
     *
     * A byte under 31 is the length itself; 31 means the length is a uintvar that follows. There is
     * no third case, and a byte of 32 or more here means the caller guessed wrong about what field
     * it was looking at.
     */
    fun valueLength(): Int {
        val first = peek()
        if (first < 0) throw PduException("expected a value length, found the end")
        return when {
            first < Wsp.LENGTH_QUOTE -> byte()
            first == Wsp.LENGTH_QUOTE -> { byte(); uintvar() }
            else -> throw PduException("expected a value length, got $first")
        }
    }

    /**
     * A NUL-terminated string, optionally quoted with 0x7F when its first character would otherwise
     * look like a token.
     *
     * Decoded as UTF-8, which is a superset of the US-ASCII the spec calls for and is what phones
     * actually send. A byte sequence that is not valid UTF-8 comes back with replacement characters
     * rather than throwing: a subject line nobody can read is still a message worth showing.
     */
    fun textString(): String = String(rawTextBytes(), Charsets.UTF_8)

    /** The same, stopping at [limit] as well as at the terminator. */
    fun textString(limit: Int, charset: Int): String =
        String(rawTextBytes(limit), charset(charset))

    private fun rawTextBytes(limit: Int = bytes.size): ByteArray {
        if (peek() == Wsp.QUOTE) byte()
        val out = ByteArrayOutputStream()
        while (position < limit && position < bytes.size) {
            val b = byte()
            if (b == 0) return out.toByteArray()
            out.write(b)
        }
        // Ran out before the terminator. Tolerated rather than thrown: it is the last field of a
        // truncated download far more often than it is a hostile PDU, and the text so far is real.
        return out.toByteArray()
    }

    /** `"` then a NUL-terminated string. Used for parameter values that contain separators. */
    fun quotedString(): String {
        if (peek() == Wsp.QUOTED_STRING_PREFIX) byte()
        return textString()
    }

    /**
     * Text that may carry its own character set.
     *
     * Two forms: a bare text-string, or a length, a charset and then the text. The second is how a
     * subject line written in anything but ASCII survives, so it is not a rare path — it is most
     * messages from most of the world.
     */
    fun encodedString(): String {
        val first = peek()
        if (first == 0) { byte(); return "" }
        if (first < 0) return ""
        if (first >= Wsp.TEXT_MIN) return textString()

        val length = valueLength()
        val end = position + length
        if (end > bytes.size) throw PduException("encoded string claims $length octets")
        val charset = integer().toInt()
        val text = textString(limit = end, charset = charset)
        // Trust the declared length over the terminator: a string with an embedded NUL would
        // otherwise leave the cursor inside the field.
        position = end
        return text
    }

    /**
     * Who a message is from.
     *
     * The one field with a "leave this to the network" form: `insert-address-token` means the MMSC
     * fills in the sender, which is what a handset sends when it does not know its own number. It
     * decodes to null, and the caller says so rather than inventing an address.
     */
    fun fromValue(): String? {
        val length = valueLength()
        val end = position + length
        val token = byte()
        val address = if (token == ADDRESS_PRESENT) encodedString() else null
        position = end.coerceAtMost(bytes.size)
        return address
    }

    /**
     * A content type, with its parameters.
     *
     * Three forms again, and all three turn up: a one-byte well-known code, a bare string, or a
     * length wrapping a type and its parameters. The parameters are where a text part says what
     * charset it is in and where a picture part says what it is called, so they are parsed rather
     * than skipped.
     */
    fun contentType(): ContentType {
        val first = peek()
        if (first < 0) throw PduException("expected a content type, found the end")

        // The high-bit test comes first, and the order is the bug this comment exists to prevent:
        // a short integer is also greater than TEXT_MIN, so testing for text first reads `0x83`
        // — the one-byte code for text/plain — as the first character of a string.
        if (first >= Wsp.SHORT_INTEGER) {
            val code = shortInteger()
            return ContentType(Wsp.contentType(code) ?: UNKNOWN_TYPE)
        }
        if (first >= Wsp.TEXT_MIN) {
            return ContentType(textString())
        }

        val length = valueLength()
        val end = (position + length).coerceAtMost(bytes.size)
        val media = when {
            peek() >= Wsp.SHORT_INTEGER -> Wsp.contentType(shortInteger()) ?: UNKNOWN_TYPE
            else -> textString()
        }
        var charset = 0
        var name: String? = null
        var start: String? = null
        while (position < end) {
            val before = position
            val parameter = parameter(end)
            when (parameter.first) {
                Wsp.PARAM_CHARSET -> charset = (parameter.second as? Long)?.toInt() ?: charset
                Wsp.PARAM_NAME, Wsp.PARAM_NAME_DEPRECATED,
                Wsp.PARAM_FILENAME, Wsp.PARAM_FILENAME_DEPRECATED -> name = parameter.second as? String ?: name
                Wsp.PARAM_START, Wsp.PARAM_START_DEPRECATED -> start = parameter.second as? String ?: start
            }
            // A parameter this build cannot read must still advance the cursor, or the loop spins.
            if (position <= before) { position = end; break }
        }
        position = end
        return ContentType(type = media, charset = charset, name = name, start = start)
    }

    /** One content-type parameter: its code (or -1 for a named one) and whatever it carried. */
    private fun parameter(end: Int): Pair<Int, Any?> {
        val first = peek()
        if (first < 0) return -1 to null
        if (first < Wsp.SHORT_INTEGER) {
            // An untyped parameter: a name, then a value. Read both and keep neither.
            textString()
            skipUntypedValue(end)
            return -1 to null
        }
        return when (val code = shortInteger()) {
            Wsp.PARAM_CHARSET -> code to integer()
            Wsp.PARAM_NAME, Wsp.PARAM_FILENAME -> code to encodedString()
            Wsp.PARAM_NAME_DEPRECATED, Wsp.PARAM_FILENAME_DEPRECATED,
            Wsp.PARAM_START, Wsp.PARAM_START_DEPRECATED -> code to textString()
            Wsp.PARAM_TYPE_STRING, Wsp.PARAM_START_INFO, Wsp.PARAM_START_INFO_DEPRECATED -> code to textString()
            Wsp.PARAM_TYPE_CONSTRAINED -> code to integer()
            else -> { skipUntypedValue(end); code to null }
        }
    }

    /**
     * Step over a value whose shape this build does not know.
     *
     * `Untyped-value = Integer-value | Text-value`, so the same three-way test as everywhere else.
     * Getting this right is what lets a carrier add a parameter without every part after it in the
     * message becoming unreadable.
     */
    private fun skipUntypedValue(end: Int) {
        val first = peek()
        when {
            first < 0 -> return
            first >= Wsp.SHORT_INTEGER -> byte()
            first == 0 -> byte()
            first < Wsp.LENGTH_QUOTE -> longInteger()
            first == Wsp.LENGTH_QUOTE -> { byte(); val n = uintvar(); skip(n.coerceAtMost(end - position)) }
            else -> textString()
        }
    }

    private fun charset(mibEnum: Int): java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName(Wsp.charsetName(mibEnum)) }.getOrDefault(Charsets.UTF_8)

    companion object {
        const val ADDRESS_PRESENT = 0x80
        const val INSERT_ADDRESS = 0x81

        /** What a well-known code this build has no name for decodes to. */
        const val UNKNOWN_TYPE = "application/octet-stream"
    }
}

/**
 * A parsed content type: what the bytes are, and the three parameters that change how they are read.
 *
 * [charset] is 0 when nothing said — which for a text part means "guess", and the guess is UTF-8.
 * [name] is what a picture is called. [start] names the part a multipart/related body opens with,
 * which is how the SMIL layout part is identified without reading it.
 */
data class ContentType(
    val type: String,
    val charset: Int = 0,
    val name: String? = null,
    val start: String? = null
) {
    val isText: Boolean get() = type.startsWith("text/", ignoreCase = true)
    val isImage: Boolean get() = type.startsWith("image/", ignoreCase = true)
    val isSmil: Boolean get() = type.equals(Wsp.SMIL, ignoreCase = true) ||
        type.equals("application/smil", ignoreCase = true)
}
