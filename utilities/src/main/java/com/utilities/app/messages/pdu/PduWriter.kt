package com.utilities.app.messages.pdu

import java.io.ByteArrayOutputStream

/**
 * Writing WSP's primitives.
 *
 * The mirror of [PduReader], and deliberately written as its mirror: every method here has a
 * counterpart there, and `PduEncoderTest` proves it by encoding a message and decoding it back. That
 * round trip is the only test of an encoder worth having — a byte-for-byte comparison against a
 * hand-written expectation tells you the encoder still does what it did yesterday, not that what it
 * does is correct.
 *
 * The one rule that is easy to get wrong: a value's **length prefix counts the bytes after it**, so
 * anything with parameters has to be built into a buffer and measured before it can be written. That
 * is what [block] is for.
 */
class PduWriter {

    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun toByteArray(): ByteArray = out.toByteArray()

    fun byte(value: Int): PduWriter = apply { out.write(value and 0xFF) }

    fun bytes(values: ByteArray): PduWriter = apply { out.write(values, 0, values.size) }

    /** Seven bits per byte, high bit set on all but the last. */
    fun uintvar(value: Int): PduWriter = apply {
        if (value < 0) throw PduException("a length cannot be negative")
        val septets = ArrayList<Int>(5)
        var v = value
        do {
            septets.add(v and 0x7F)
            v = v ushr 7
        } while (v != 0)
        for (i in septets.indices.reversed()) {
            val last = i == 0
            out.write(if (last) septets[i] else septets[i] or 0x80)
        }
    }

    /** A value of 0..127 in one byte, with the high bit marking it as a number. */
    fun shortInteger(value: Int): PduWriter = apply {
        if (value !in 0..0x7F) throw PduException("$value does not fit in a short integer")
        out.write(value or Wsp.SHORT_INTEGER)
    }

    /** A length byte then the big-endian bytes, with leading zeroes dropped. */
    fun longInteger(value: Long): PduWriter = apply {
        if (value < 0) throw PduException("a long integer cannot be negative")
        val octets = ArrayList<Int>(8)
        var v = value
        do {
            octets.add((v and 0xFF).toInt())
            v = v ushr 8
        } while (v != 0L)
        out.write(octets.size)
        for (i in octets.indices.reversed()) out.write(octets[i])
    }

    /** Whichever form fits. */
    fun integer(value: Long): PduWriter =
        if (value in 0..0x7F) shortInteger(value.toInt()) else longInteger(value)

    /** A length, in the short form when it fits and the quoted form when it does not. */
    fun valueLength(length: Int): PduWriter = apply {
        if (length < Wsp.LENGTH_QUOTE) out.write(length)
        else { out.write(Wsp.LENGTH_QUOTE); uintvar(length) }
    }

    /**
     * A NUL-terminated string, quoted when its first character would be mistaken for a token.
     *
     * The quote is not decoration: a `From` of `+15550109999` starts with `+` (0x2B) and is fine,
     * but a filename beginning with a high byte would be read as a length, and the message after it
     * as rubble.
     */
    fun textString(value: String): PduWriter = apply {
        val encoded = value.toByteArray(Charsets.UTF_8)
        if (encoded.isNotEmpty() && (encoded[0].toInt() and 0xFF) >= Wsp.SHORT_INTEGER) out.write(Wsp.QUOTE)
        out.write(encoded, 0, encoded.size)
        out.write(0)
    }

    /**
     * Text with its character set declared.
     *
     * Always the long form with UTF-8 named explicitly, even for text that happens to be ASCII. The
     * short form is legal and means "US-ASCII", and a subject line that was pure ASCII yesterday and
     * has an em-dash in it today would otherwise change encoding silently — which is exactly the
     * bug that produces one gateway in five rejecting a message.
     */
    fun encodedString(value: String): PduWriter = apply {
        val encoded = value.toByteArray(Charsets.UTF_8)
        val body = PduWriter()
            .integer(Wsp.CHARSET_UTF_8.toLong())
            .bytes(encoded)
            .byte(0)
            .toByteArray()
        valueLength(body.size)
        bytes(body)
    }

    /** `From`, in the form that names an address rather than asking the network to fill one in. */
    fun fromValue(address: String?): PduWriter = apply {
        if (address == null) {
            valueLength(1)
            byte(PduReader.INSERT_ADDRESS)
            return@apply
        }
        val body = PduWriter()
            .byte(PduReader.ADDRESS_PRESENT)
            .encodedString(address)
            .toByteArray()
        valueLength(body.size)
        bytes(body)
    }

    /**
     * A content type and its parameters.
     *
     * The bare well-known code is used only when there is nothing else to say; the moment a charset
     * or a name is involved it has to be the long form, because parameters have nowhere else to go.
     */
    fun contentType(type: ContentType): PduWriter = apply {
        val code = Wsp.contentTypeCode(type.type)
        val hasParameters = type.charset != 0 || type.name != null || type.start != null
        if (!hasParameters) {
            if (code != null) shortInteger(code) else textString(type.type)
            return@apply
        }

        val body = PduWriter()
        if (code != null) body.shortInteger(code) else body.textString(type.type)
        if (type.charset != 0) {
            body.shortInteger(Wsp.PARAM_CHARSET).integer(type.charset.toLong())
        }
        if (type.name != null) {
            // The deprecated code rather than the current one, and on purpose: it takes a plain
            // text-string, every implementation ever shipped reads it, and the "current" one has
            // been current since 2001 without becoming universal.
            body.shortInteger(Wsp.PARAM_NAME_DEPRECATED).textString(type.name)
        }
        if (type.start != null) {
            body.shortInteger(Wsp.PARAM_START_DEPRECATED).textString(type.start)
        }
        val encoded = body.toByteArray()
        valueLength(encoded.size)
        bytes(encoded)
    }

    /** One MMS header: its code with the high bit set, then whatever the caller writes. */
    fun header(code: Int, value: PduWriter.() -> Unit): PduWriter = apply {
        byte(code)
        value()
    }

    /** Build a nested chunk so its length can be measured before it is written. */
    fun block(body: PduWriter.() -> Unit): ByteArray = PduWriter().apply(body).toByteArray()
}
