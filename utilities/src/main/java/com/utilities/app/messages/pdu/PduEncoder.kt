package com.utilities.app.messages.pdu

/**
 * Building the three PDUs this app ever sends.
 *
 * **`m-send-req`** is the message itself — recipients, subject, and the multipart body. It is handed
 * to `SmsManager`, which knows the carrier's MMSC address and the APN to reach it on; this object
 * supplies the bytes and nothing else.
 *
 * **`m-notifyresp-ind`** and **`m-acknowledge-ind`** are the two "yes, I got it" PDUs. Neither is
 * strictly required to *receive* a message, and both are sent anyway, because a network that never
 * hears them keeps re-pushing the notification — which the household experiences as the same
 * picture message arriving four times.
 *
 * ## Header order is not decoration
 *
 * WAP-209 fixes the order of the first three fields — message type, transaction id, version — and a
 * surprising number of gateways enforce it. `Content-Type` must come last, because everything after
 * it is the body. Between those, order is free; it is kept stable here so the round-trip test
 * compares like with like.
 */
object PduEncoder {

    /**
     * A picture message, ready to hand to the radio.
     *
     * [from] is null on purpose in the normal case: `insert-address-token` asks the MMSC to fill in
     * the sender, which is both correct (the handset frequently does not know its own number) and
     * the only way this works on a SIM that has never been told what its number is.
     */
    fun sendReq(
        to: List<String>,
        parts: List<MmsPart>,
        subject: String? = null,
        transactionId: String = newTransactionId(),
        from: String? = null,
        deliveryReport: Boolean = false,
        readReport: Boolean = false,
        date: Long = System.currentTimeMillis()
    ): ByteArray {
        if (to.isEmpty()) throw PduException("a message with no recipients")
        val body = multipart(parts)
        val writer = PduWriter()

        writer.byte(MmsHeaders.MESSAGE_TYPE).byte(MmsHeaders.TYPE_SEND_REQ)
        writer.byte(MmsHeaders.TRANSACTION_ID).textString(transactionId)
        writer.byte(MmsHeaders.MMS_VERSION).byte(MmsHeaders.VERSION_1_2)
        writer.byte(MmsHeaders.DATE).longInteger(date / 1000L)
        writer.byte(MmsHeaders.FROM).fromValue(from)
        to.forEach { writer.byte(MmsHeaders.TO).encodedString(it) }
        if (!subject.isNullOrBlank()) writer.byte(MmsHeaders.SUBJECT).encodedString(subject)
        writer.byte(MmsHeaders.MESSAGE_CLASS).byte(MmsHeaders.MESSAGE_CLASS_PERSONAL)
        writer.byte(MmsHeaders.DELIVERY_REPORT).byte(if (deliveryReport) MmsHeaders.YES else MmsHeaders.NO)
        writer.byte(MmsHeaders.READ_REPORT).byte(if (readReport) MmsHeaders.YES else MmsHeaders.NO)

        // Last, always: what follows it is the body rather than another header.
        writer.byte(MmsHeaders.CONTENT_TYPE).contentType(bodyContentType(parts))
        writer.bytes(body)
        return writer.toByteArray()
    }

    /** "I have seen your notification." Sent whether or not the download then succeeds. */
    fun notifyRespInd(transactionId: String, status: Int = MmsHeaders.STATUS_RETRIEVED): ByteArray =
        PduWriter()
            .byte(MmsHeaders.MESSAGE_TYPE).byte(MmsHeaders.TYPE_NOTIFYRESP_IND)
            .byte(MmsHeaders.TRANSACTION_ID).textString(transactionId)
            .byte(MmsHeaders.MMS_VERSION).byte(MmsHeaders.VERSION_1_2)
            .byte(MmsHeaders.STATUS).byte(status)
            // No, the sender may not be told this was read. A read receipt is a thing the household
            // opts into, and there is nowhere in this app that turns it on.
            .byte(MmsHeaders.REPORT_ALLOWED).byte(MmsHeaders.NO)
            .toByteArray()

    /** "I have the message." Sent after a successful download. */
    fun acknowledgeInd(transactionId: String): ByteArray =
        PduWriter()
            .byte(MmsHeaders.MESSAGE_TYPE).byte(MmsHeaders.TYPE_ACKNOWLEDGE_IND)
            .byte(MmsHeaders.TRANSACTION_ID).textString(transactionId)
            .byte(MmsHeaders.MMS_VERSION).byte(MmsHeaders.VERSION_1_2)
            .byte(MmsHeaders.REPORT_ALLOWED).byte(MmsHeaders.NO)
            .toByteArray()

    /**
     * The multipart body: a count, then an entry per part.
     *
     * Each entry's first length covers the content type **and** the headers after it, which is the
     * one structural surprise in the format — so the header block is built into a buffer and
     * measured rather than counted by hand.
     */
    fun multipart(parts: List<MmsPart>): ByteArray {
        val writer = PduWriter()
        writer.uintvar(parts.size)
        parts.forEach { part ->
            val headers = PduWriter()
            headers.contentType(
                ContentType(
                    type = part.contentType,
                    charset = if (part.isText) (part.charset.takeIf { it != 0 } ?: Wsp.CHARSET_UTF_8) else 0,
                    name = part.name
                )
            )
            part.contentId?.let {
                // Angle brackets, because a SMIL layout refers to a part as `cid:<foo>` and a
                // receiver that strips them and a receiver that does not both have to find it.
                headers.shortInteger(Wsp.HEADER_CONTENT_ID).byte(Wsp.QUOTED_STRING_PREFIX)
                headers.bytes("<$it>".toByteArray(Charsets.UTF_8)).byte(0)
            }
            part.contentLocation?.let {
                headers.shortInteger(Wsp.HEADER_CONTENT_LOCATION).textString(it)
            }
            val headerBytes = headers.toByteArray()
            writer.uintvar(headerBytes.size)
            writer.uintvar(part.data.size)
            writer.bytes(headerBytes)
            writer.bytes(part.data)
        }
        return writer.toByteArray()
    }

    /**
     * What the body is wrapped in.
     *
     * `related` when there is a layout part pointing at the others, `mixed` when there is not.
     * Gateways do check: a body declared related with nothing to relate is rejected by some, and a
     * SMIL part inside a mixed body is ignored by most.
     */
    fun bodyContentType(parts: List<MmsPart>): ContentType {
        val smil = parts.firstOrNull { it.isSmil }
        return if (smil != null) {
            ContentType(
                type = Wsp.MULTIPART_RELATED,
                start = smil.contentId?.let { "<$it>" },
                name = null
            )
        } else {
            ContentType(type = Wsp.MULTIPART_MIXED)
        }
    }

    /**
     * The layout part: a slideshow of one slide per attachment.
     *
     * Forty bytes of XML that nearly nothing displays, written because a message *without* one is
     * rejected by enough gateways to matter. The text goes on the first slide with the first
     * picture, which is what every phone does and what somebody sending a photograph with a caption
     * means.
     */
    fun smil(parts: List<MmsPart>): MmsPart {
        val slides = StringBuilder()
        val images = parts.filter { it.isImage || (!it.isText && !it.isSmil) }
        val text = parts.firstOrNull { it.isText && !it.isSmil }
        if (images.isEmpty() && text != null) {
            slides.append(slide(text = text.contentLocation, image = null))
        } else {
            images.forEachIndexed { index, image ->
                slides.append(slide(text = if (index == 0) text?.contentLocation else null, image = image.contentLocation))
            }
        }
        val document = """
            <smil><head><layout>
            <root-layout width="320px" height="480px"/>
            <region id="Image" top="0" left="0" height="80%" width="100%" fit="meet"/>
            <region id="Text" top="80%" left="0" height="20%" width="100%"/>
            </layout></head><body>$slides</body></smil>
        """.trimIndent().replace("\n", "")

        return MmsPart(
            contentType = Wsp.SMIL,
            data = document.toByteArray(Charsets.UTF_8),
            name = SMIL_NAME,
            contentId = SMIL_NAME,
            contentLocation = SMIL_NAME
        )
    }

    private fun slide(text: String?, image: String?): String {
        val parts = buildString {
            if (image != null) append("""<img src="$image" region="Image" dur="5000ms"/>""")
            if (text != null) append("""<text src="$text" region="Text" dur="5000ms"/>""")
        }
        return "<par dur=\"5000ms\">$parts</par>"
    }

    /**
     * A transaction id, which is how the network matches a message to its acknowledgement.
     *
     * Only has to be unique against this handset's own outstanding messages, so the clock plus a
     * counter is enough — and is deliberately not anything derived from the phone, the SIM or the
     * recipient, because a transaction id travels to the MMSC in clear.
     */
    fun newTransactionId(now: Long = System.currentTimeMillis()): String =
        "T${now.toString(16)}${(counter++ and 0xFFF).toString(16)}"

    @Volatile
    private var counter: Int = 0

    /** What the layout part is called. Referred to by the body's `start` parameter. */
    const val SMIL_NAME = "smil.xml"
}
