package com.utilities.app.messages.pdu

/**
 * The MMS header codes, from WAP-209 §7.3.
 *
 * Each is the field's assigned number with the high bit set, because that is how it appears on the
 * wire. Named rather than inlined for the reason the whole package is: `0x8C` in a parser is
 * unreadable, and the cost of getting one wrong is a picture message that never arrives with no
 * error anywhere.
 */
object MmsHeaders {

    const val BCC = 0x81
    const val CC = 0x82
    const val CONTENT_LOCATION = 0x83
    const val CONTENT_TYPE = 0x84
    const val DATE = 0x85
    const val DELIVERY_REPORT = 0x86
    const val DELIVERY_TIME = 0x87
    const val EXPIRY = 0x88
    const val FROM = 0x89
    const val MESSAGE_CLASS = 0x8A
    const val MESSAGE_ID = 0x8B
    const val MESSAGE_TYPE = 0x8C
    const val MMS_VERSION = 0x8D
    const val MESSAGE_SIZE = 0x8E
    const val PRIORITY = 0x8F
    const val READ_REPORT = 0x90
    const val REPORT_ALLOWED = 0x91
    const val RESPONSE_STATUS = 0x92
    const val RESPONSE_TEXT = 0x93
    const val SENDER_VISIBILITY = 0x94
    const val STATUS = 0x95
    const val SUBJECT = 0x96
    const val TO = 0x97
    const val TRANSACTION_ID = 0x98
    const val RETRIEVE_STATUS = 0x99
    const val RETRIEVE_TEXT = 0x9A
    const val READ_STATUS = 0x9B

    // --- What kind of message this is (the value of MESSAGE_TYPE) --------------------------

    /** Handset to network: here is a picture message, please deliver it. */
    const val TYPE_SEND_REQ = 0x80

    /** Network to handset: I took it, and here is the id I filed it under. */
    const val TYPE_SEND_CONF = 0x81

    /** Network to handset: something is waiting, here is where. This is what a WAP push carries. */
    const val TYPE_NOTIFICATION_IND = 0x82

    /** Handset to network: I have seen the notification. */
    const val TYPE_NOTIFYRESP_IND = 0x83

    /** The message itself, in answer to a download. This is the one with the pictures in it. */
    const val TYPE_RETRIEVE_CONF = 0x84

    /** Handset to network: I have it. */
    const val TYPE_ACKNOWLEDGE_IND = 0x85

    /** Network to handset: your message reached (or did not reach) its recipient. */
    const val TYPE_DELIVERY_IND = 0x86

    const val TYPE_READ_REC_IND = 0x87
    const val TYPE_READ_ORIG_IND = 0x88

    /** MMS 1.2 — major in the high nibble, minor in the low, high bit set. */
    const val VERSION_1_2 = 0x92
    const val VERSION_1_3 = 0x93

    const val MESSAGE_CLASS_PERSONAL = 0x80
    const val MESSAGE_CLASS_ADVERTISEMENT = 0x81
    const val MESSAGE_CLASS_INFORMATIONAL = 0x82
    const val MESSAGE_CLASS_AUTO = 0x83

    const val STATUS_RETRIEVED = 0x81
    const val STATUS_DEFERRED = 0x83
    const val STATUS_UNRECOGNISED = 0x84

    const val YES = 0x80
    const val NO = 0x81

    /** Expiry and delivery time both come in these two flavours. */
    const val TIME_ABSOLUTE = 0x80
    const val TIME_RELATIVE = 0x81

    /** How a message's addresses are filed in the platform's provider, and in [MmsMessage]. */
    const val ADDRESS_FROM = 0x89
    const val ADDRESS_TO = 0x97
    const val ADDRESS_CC = 0x82
    const val ADDRESS_BCC = 0x81
}

/**
 * One piece of a picture message.
 *
 * A "multimedia message" is a multipart body, and the parts are the actual content: usually one
 * text part, one or more pictures, and a SMIL part describing a slideshow that nothing displays.
 * [data] is the bytes exactly as they travelled; nothing here decodes an image.
 */
data class MmsPart(
    val contentType: String,
    val data: ByteArray,
    /** What the part is called — a filename, when it has one. */
    val name: String? = null,
    /** What other parts refer to it by. A SMIL layout points at a picture with this. */
    val contentId: String? = null,
    /** Where it claims to have come from. Often the same as [name]. */
    val contentLocation: String? = null,
    /** The MIBenum a text part is written in; 0 when nothing said, which means guess UTF-8. */
    val charset: Int = 0
) {

    val isText: Boolean get() = contentType.startsWith("text/", ignoreCase = true)

    val isImage: Boolean get() = contentType.startsWith("image/", ignoreCase = true)

    /** The layout part, which is written on the way out and ignored on the way in. */
    val isSmil: Boolean get() = contentType.equals(Wsp.SMIL, ignoreCase = true)

    /** A text part's words, in whatever it says it is written in. */
    fun text(): String? {
        if (!isText) return null
        val charsetName = if (charset == 0) "UTF-8" else Wsp.charsetName(charset)
        return runCatching { String(data, java.nio.charset.Charset.forName(charsetName)) }
            .getOrElse { String(data, Charsets.UTF_8) }
    }

    // Data classes over a ByteArray need these written out, or two parts with identical bytes are
    // unequal — which would quietly break every round-trip assertion in the test suite.

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MmsPart) return false
        return contentType == other.contentType &&
            data.contentEquals(other.data) &&
            name == other.name &&
            contentId == other.contentId &&
            contentLocation == other.contentLocation &&
            charset == other.charset
    }

    override fun hashCode(): Int {
        var result = contentType.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (contentId?.hashCode() ?: 0)
        result = 31 * result + (contentLocation?.hashCode() ?: 0)
        result = 31 * result + charset
        return result
    }
}

/**
 * "Something is waiting for you" — the PDU a WAP push actually carries.
 *
 * It is not the message. It is an announcement with a URL in it, and the whole of the download
 * machinery exists to turn one of these into a [MmsMessage]. [contentLocation] is the field the
 * feature stands on; without it there is nothing to fetch and the notification can only be shown.
 */
data class NotificationInd(
    val transactionId: String?,
    val contentLocation: String?,
    val from: String?,
    val subject: String?,
    /** Bytes, as the sender declared them. Worth knowing before fetching over a metered network. */
    val messageSize: Long = 0,
    /** When the MMSC stops holding it, as epoch millis, or 0 when it said nothing useful. */
    val expiry: Long = 0,
    val messageClass: Int = MmsHeaders.MESSAGE_CLASS_PERSONAL
) {
    /** Whether there is anything to fetch. A notification without one is only ever a notice. */
    val fetchable: Boolean get() = !contentLocation.isNullOrBlank()

    /**
     * Advertising, sent by a machine.
     *
     * Worth distinguishing because it is the one class where auto-downloading on a metered
     * connection is somebody paying to receive a leaflet.
     */
    val isAdvertisement: Boolean get() = messageClass == MmsHeaders.MESSAGE_CLASS_ADVERTISEMENT
}

/**
 * A picture message, decoded: who, when, what about, and the parts.
 *
 * The same shape is used for one that arrived (`m-retrieve-conf`) and one being sent
 * (`m-send-req`), because they differ in three header fields and nothing else that matters here.
 */
data class MmsMessage(
    val type: Int,
    val from: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String? = null,
    val messageId: String? = null,
    val transactionId: String? = null,
    /** Epoch millis. The wire carries seconds; the conversion happens in the codec, once. */
    val date: Long = 0,
    val parts: List<MmsPart> = emptyList(),
    /** What the multipart body was wrapped in — related when the parts refer to each other. */
    val bodyType: String = Wsp.MULTIPART_RELATED
) {

    /** Every word in the message, as the thread shows it. */
    fun text(): String = parts.filter { it.isText && !it.isSmil }.mapNotNull { it.text() }
        .filter { it.isNotBlank() }
        .joinToString("\n")

    /** The parts worth showing: everything that is not the layout nobody reads. */
    fun attachments(): List<MmsPart> = parts.filterNot { it.isSmil || it.isText }
}
