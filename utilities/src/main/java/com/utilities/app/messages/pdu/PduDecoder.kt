package com.utilities.app.messages.pdu

/**
 * Bytes off the radio, turned into something with names on it.
 *
 * ## The contract
 *
 * Every entry point returns null rather than throwing. The input arrived over a network from a
 * stranger, it is parsed in a broadcast receiver, and the only acceptable failure is "this was not a
 * message I could read" — not a crash in the app that is receiving somebody's holiday photograph.
 * [PduReader] throws on anything malformed and this is where that is caught, once, at the edge.
 *
 * ## What it is lenient about, and what it is not
 *
 * Lenient about **trailing rubbish, unknown headers and unknown parameters**: a carrier that adds a
 * field must not make the message unreadable, so an unrecognised header is stepped over by value
 * shape rather than being fatal. Lenient about a **truncated final string**, which is a partial
 * download far more often than it is an attack.
 *
 * Strict about **lengths**. A part that claims more bytes than the buffer holds is refused rather
 * than clamped: clamping turns a corrupt message into a plausible one, and a plausible picture
 * message assembled from whatever followed it in memory is worse than no picture message.
 */
object PduDecoder {

    /**
     * Read the announcement a WAP push carries.
     *
     * Returns null when the bytes are not an `m-notification-ind` at all — which they often are not.
     * Delivery reports and read receipts arrive down the same pipe, and the right thing to do with
     * one of those is nothing.
     */
    fun notification(bytes: ByteArray): NotificationInd? = runCatching {
        val reader = PduReader(bytes)
        val headers = readHeaders(reader) { it == MmsHeaders.CONTENT_TYPE }
        if (headers.messageType != MmsHeaders.TYPE_NOTIFICATION_IND) return null
        NotificationInd(
            transactionId = headers.transactionId,
            contentLocation = headers.contentLocation,
            from = headers.from,
            subject = headers.subject,
            messageSize = headers.messageSize,
            expiry = headers.expiry,
            messageClass = headers.messageClass
        )
    }.getOrNull()

    /** What kind of PDU this is, without reading the rest of it. */
    fun messageType(bytes: ByteArray): Int? = runCatching {
        val reader = PduReader(bytes)
        while (reader.hasMore()) {
            if (reader.byte() == MmsHeaders.MESSAGE_TYPE) return reader.byte()
        }
        null
    }.getOrNull()

    /**
     * Read a whole picture message — the answer to a download, or one this app built to send.
     *
     * Both are the same structure with a different message-type byte, so both parse here, which is
     * also what makes the encoder's round-trip test possible.
     */
    fun message(bytes: ByteArray): MmsMessage? = runCatching {
        val reader = PduReader(bytes)
        val headers = readHeaders(reader) { it == MmsHeaders.CONTENT_TYPE }
        val bodyType = headers.contentType?.type ?: Wsp.MULTIPART_RELATED
        val parts = if (reader.hasMore()) multipart(reader) else emptyList()
        MmsMessage(
            type = headers.messageType,
            from = headers.from,
            to = headers.to,
            cc = headers.cc,
            subject = headers.subject,
            messageId = headers.messageId,
            transactionId = headers.transactionId,
            date = headers.date,
            parts = parts,
            bodyType = bodyType
        )
    }.getOrNull()

    /**
     * The multipart body: a count, then that many entries.
     *
     * Each entry is two lengths, a content type and the data — with the first length covering the
     * content type *and* the headers after it, which is the one structural surprise in the format
     * and the reason the cursor is measured rather than assumed.
     */
    fun multipart(reader: PduReader): List<MmsPart> {
        val count = reader.uintvar()
        // A count far larger than the remaining bytes can hold is a corrupt stream, not a message
        // with a lot of parts. Two bytes is the smallest an entry can be.
        if (count < 0 || count > reader.remaining) throw PduException("$count parts in ${reader.remaining} bytes")
        val parts = ArrayList<MmsPart>(count)
        repeat(count) {
            if (!reader.hasMore()) return parts
            parts.add(entry(reader))
        }
        return parts
    }

    private fun entry(reader: PduReader): MmsPart {
        val headersLength = reader.uintvar()
        val dataLength = reader.uintvar()
        if (headersLength < 0 || dataLength < 0) throw PduException("negative length in a part")

        val headersStart = reader.position
        val headersEnd = headersStart + headersLength
        if (headersEnd > reader.size) throw PduException("part headers claim $headersLength bytes")
        val type = reader.contentType()

        var contentId: String? = null
        var contentLocation: String? = null
        while (reader.position < headersEnd && reader.hasMore()) {
            val before = reader.position
            val field = reader.peek()
            if (field >= Wsp.SHORT_INTEGER) {
                when (reader.shortInteger()) {
                    Wsp.HEADER_CONTENT_ID -> contentId = reader.quotedString().trim('<', '>')
                    Wsp.HEADER_CONTENT_LOCATION -> contentLocation = reader.textString()
                    else -> skipHeaderValue(reader, headersEnd)
                }
            } else {
                // A header named by string rather than by code. Read the name, drop the value.
                reader.textString()
                skipHeaderValue(reader, headersEnd)
            }
            if (reader.position <= before) break
        }
        reader.seek(headersEnd)

        if (dataLength > reader.remaining) throw PduException("a part claims $dataLength bytes")
        val data = reader.take(dataLength)

        return MmsPart(
            contentType = type.type,
            data = data,
            name = type.name ?: contentLocation,
            contentId = contentId,
            contentLocation = contentLocation ?: type.name,
            charset = type.charset
        )
    }

    // -----------------------------------------------------------------------------------------
    // Headers
    // -----------------------------------------------------------------------------------------

    /**
     * Everything before the body.
     *
     * [stopAfter] names the header that ends the header block — `Content-Type` is always the last
     * one in a message with a body, and what follows it is not a header at all but the multipart
     * count. Without that rule the parser reads the body's first byte as a field code.
     */
    private fun readHeaders(reader: PduReader, stopAfter: (Int) -> Boolean): Headers {
        val headers = Headers()
        while (reader.hasMore()) {
            val before = reader.position
            val field = reader.peek()
            if (field < Wsp.SHORT_INTEGER) {
                // Not a field code. Either a header named by string, or the body has begun and the
                // Content-Type rule above failed to fire. Stop rather than guess.
                break
            }
            reader.byte()
            readHeader(reader, field, headers)
            if (stopAfter(field)) break
            if (reader.position <= before) break
        }
        return headers
    }

    private fun readHeader(reader: PduReader, field: Int, headers: Headers) {
        when (field) {
            MmsHeaders.MESSAGE_TYPE -> headers.messageType = reader.byte()
            MmsHeaders.TRANSACTION_ID -> headers.transactionId = reader.textString()
            MmsHeaders.MESSAGE_ID -> headers.messageId = reader.textString()
            MmsHeaders.MMS_VERSION -> reader.byte()
            MmsHeaders.CONTENT_LOCATION -> headers.contentLocation = reader.textString()
            MmsHeaders.FROM -> headers.from = reader.fromValue()
            MmsHeaders.TO -> headers.to.add(reader.encodedString())
            MmsHeaders.CC -> headers.cc.add(reader.encodedString())
            MmsHeaders.BCC -> reader.encodedString()
            MmsHeaders.SUBJECT -> headers.subject = reader.encodedString()
            // The wire carries seconds; everything above this package speaks millis, so the
            // conversion happens here and exactly once.
            MmsHeaders.DATE -> headers.date = reader.longInteger() * 1000L
            MmsHeaders.MESSAGE_SIZE -> headers.messageSize = reader.longInteger()
            MmsHeaders.MESSAGE_CLASS -> headers.messageClass = reader.byte()
            MmsHeaders.EXPIRY, MmsHeaders.DELIVERY_TIME -> headers.expiry = timeValue(reader)
            MmsHeaders.CONTENT_TYPE -> headers.contentType = reader.contentType()
            MmsHeaders.PRIORITY, MmsHeaders.DELIVERY_REPORT, MmsHeaders.READ_REPORT,
            MmsHeaders.REPORT_ALLOWED, MmsHeaders.SENDER_VISIBILITY, MmsHeaders.STATUS,
            MmsHeaders.READ_STATUS, MmsHeaders.RETRIEVE_STATUS -> reader.byte()
            MmsHeaders.RESPONSE_STATUS -> reader.byte()
            MmsHeaders.RESPONSE_TEXT, MmsHeaders.RETRIEVE_TEXT -> reader.encodedString()
            else -> skipHeaderValue(reader, reader.position + reader.remaining)
        }
    }

    /**
     * `Expiry` and friends: a length, a token saying absolute or relative, then a number.
     *
     * Relative is seconds from now, which is only meaningful against a clock, so it is resolved
     * here against the caller's — the alternative is storing "3600" and having no idea from when.
     */
    private fun timeValue(reader: PduReader, now: Long = System.currentTimeMillis()): Long {
        val length = reader.valueLength()
        val end = reader.position + length
        val token = reader.byte()
        val value = reader.longInteger()
        reader.seek(end.coerceAtMost(reader.size))
        return when (token) {
            MmsHeaders.TIME_ABSOLUTE -> value * 1000L
            MmsHeaders.TIME_RELATIVE -> now + value * 1000L
            else -> 0L
        }
    }

    /**
     * Step over the value of a header this build does not know.
     *
     * The same three-way shape test as everywhere else, and the reason a carrier can add a field
     * without every header after it becoming unreadable. A header whose value cannot be shaped at
     * all stops the parse rather than desynchronising it.
     */
    private fun skipHeaderValue(reader: PduReader, limit: Int) {
        val first = reader.peek()
        when {
            first < 0 -> return
            first >= Wsp.SHORT_INTEGER -> reader.byte()
            first == 0 -> reader.byte()
            first < Wsp.LENGTH_QUOTE -> {
                val length = reader.valueLength()
                reader.skip(length.coerceAtMost((limit - reader.position).coerceAtLeast(0)))
            }
            first == Wsp.LENGTH_QUOTE -> {
                reader.byte()
                val length = reader.uintvar()
                reader.skip(length.coerceAtMost((limit - reader.position).coerceAtLeast(0)))
            }
            else -> reader.textString()
        }
    }

    /** Mutable while the headers are being read; never escapes this file. */
    private class Headers {
        var messageType: Int = 0
        var transactionId: String? = null
        var messageId: String? = null
        var contentLocation: String? = null
        var from: String? = null
        val to: MutableList<String> = ArrayList()
        val cc: MutableList<String> = ArrayList()
        var subject: String? = null
        var date: Long = 0
        var messageSize: Long = 0
        var expiry: Long = 0
        var messageClass: Int = MmsHeaders.MESSAGE_CLASS_PERSONAL
        var contentType: ContentType? = null
    }
}
