package com.utilities.app.messages.pdu

/**
 * The numbers WAP binary encoding is made of.
 *
 * ## Why any of this exists
 *
 * A text message arrives as text. A picture message arrives as a **binary push** telling the phone
 * that something is waiting on the carrier's own server, and everything after that — what is
 * waiting, where, how to ask for it, and what comes back — is WAP: a binary encoding designed in
 * 1999 for phones with 4KB of RAM, in which every header name is a single byte and every string may
 * or may not be prefixed with its own length. There is no JSON anywhere near it and no library in
 * the platform that will parse it for an app. `SmsManager` will do the *network* half — it knows the
 * carrier's MMSC address and how to reach it over the right APN — and hands back bytes. The bytes
 * are ours.
 *
 * Which is, in the end, good news: it makes the whole of MMS **decidable without a phone**. What is
 * in this package is a codec over byte arrays. It is unit-tested on the JVM, including a full
 * encode-then-decode round trip, and the Android half (`messages/mms/`) does nothing but move the
 * results between here and the platform's provider.
 *
 * ## The two specifications
 *
 * - **WSP** (WAP-230) supplies the primitives: variable-length integers, the length-prefix rules,
 *   the tables that turn `text/plain` into the single byte `0x83`. That is [PduReader] and
 *   [PduWriter].
 * - **MMS Encapsulation** (WAP-209) supplies the message: which header codes mean what, and which
 *   headers each kind of message must carry. That is [MmsHeaders], [PduDecoder] and [PduEncoder].
 *
 * Everything here is a constant from one of those two documents. They are named rather than inlined
 * because a magic `0x8C` in a parser is unreadable and unverifiable, and because the failure mode of
 * getting one wrong is a picture message that silently never arrives.
 */
object Wsp {

    /**
     * Where a well-known value stops being a length and starts being a token.
     *
     * The single most important number in WSP and the one every primitive keys off: a byte under 32
     * is a **length**, a byte from 32 to 127 is the first character of a **text string**, and a byte
     * with the high bit set is a **short integer** carrying its value in the low seven bits. Nearly
     * every "is this field a string or a number?" question is answered by comparing against these.
     */
    const val TEXT_MIN = 32
    const val TEXT_MAX = 127

    /** A length of 31 means "the real length follows, as a uintvar". */
    const val LENGTH_QUOTE = 31

    /** A text string whose first character would collide with a token is prefixed with this. */
    const val QUOTE = 0x7F

    const val QUOTED_STRING_PREFIX = 0x22

    /** The high bit, which marks a short integer. */
    const val SHORT_INTEGER = 0x80

    // --- Content types ---------------------------------------------------------------------
    //
    // WSP's assigned-numbers table, so `image/jpeg` travels as one byte. Only the entries that
    // actually turn up in a picture message are here, plus the multipart types the body itself is
    // wrapped in. Anything not in the table is perfectly legal to send as a plain string — see
    // [contentTypeCode] — so this is an optimisation table rather than a vocabulary, and a carrier
    // sending something exotic gets decoded rather than dropped.

    private val CONTENT_TYPES: Map<Int, String> = mapOf(
        0x00 to "*/*",
        0x01 to "text/*",
        0x02 to "text/html",
        0x03 to "text/plain",
        0x06 to "text/x-vCalendar",
        0x07 to "text/x-vCard",
        0x0B to "multipart/*",
        0x0C to "multipart/mixed",
        0x0F to "multipart/alternative",
        0x10 to "application/*",
        0x1C to "image/*",
        0x1D to "image/gif",
        0x1E to "image/jpeg",
        0x1F to "image/tiff",
        0x20 to "image/png",
        0x21 to "image/vnd.wap.wbmp",
        0x22 to "application/vnd.wap.multipart.*",
        0x23 to "application/vnd.wap.multipart.mixed",
        0x26 to "application/vnd.wap.multipart.alternative",
        0x27 to "application/xml",
        0x28 to "text/xml",
        0x33 to "application/vnd.wap.multipart.related",
        0x3E to "application/vnd.wap.mms-message",
        0x4F to "audio/*",
        0x50 to "video/*"
    )

    private val CONTENT_TYPE_CODES: Map<String, Int> = CONTENT_TYPES.entries.associate { (k, v) -> v to k }

    /** The type a well-known code names, or null when this build has never heard of it. */
    fun contentType(code: Int): String? = CONTENT_TYPES[code]

    /** The one-byte code for [type], or null — in which case it is written out as a string. */
    fun contentTypeCode(type: String): Int? = CONTENT_TYPE_CODES[type.lowercase()]

    /** What a picture message's body is wrapped in when its parts refer to each other (SMIL does). */
    const val MULTIPART_RELATED = "application/vnd.wap.multipart.related"

    /** …and when they do not. */
    const val MULTIPART_MIXED = "application/vnd.wap.multipart.mixed"

    /**
     * The layout language every MMS client writes and almost none reads.
     *
     * A picture message is supposed to carry a SMIL part saying which picture goes on which slide
     * and for how long. In practice every receiving client lays the parts out itself, and a message
     * without one is shown perfectly — but a message *sent* without one is rejected by enough
     * gateways to be worth the forty bytes. See `PduEncoder.smil`.
     */
    const val SMIL = "application/smil"

    // --- Character sets --------------------------------------------------------------------
    //
    // IANA MIBenum numbers, which is what WSP carries. Only the ones a text part is realistically
    // encoded in; anything else decodes as UTF-8, which is both the common case and the forgiving
    // one — a mis-guessed Latin-1 byte comes out as a replacement character rather than an
    // exception.

    const val CHARSET_ASCII = 0x03
    const val CHARSET_LATIN_1 = 0x04
    const val CHARSET_UTF_8 = 0x6A
    const val CHARSET_UTF_16 = 0x03F7
    const val CHARSET_UCS2 = 0x03E8

    private val CHARSETS: Map<Int, String> = mapOf(
        CHARSET_ASCII to "US-ASCII",
        CHARSET_LATIN_1 to "ISO-8859-1",
        CHARSET_UTF_8 to "UTF-8",
        CHARSET_UTF_16 to "UTF-16",
        CHARSET_UCS2 to "UTF-16BE"
    )

    fun charsetName(mibEnum: Int): String = CHARSETS[mibEnum] ?: "UTF-8"

    // --- Content-type parameters -------------------------------------------------------------

    const val PARAM_CHARSET = 0x01
    const val PARAM_TYPE_STRING = 0x03
    const val PARAM_NAME_DEPRECATED = 0x05
    const val PARAM_FILENAME_DEPRECATED = 0x06
    const val PARAM_TYPE_CONSTRAINED = 0x09
    const val PARAM_START_DEPRECATED = 0x0A
    const val PARAM_START_INFO_DEPRECATED = 0x0B
    const val PARAM_NAME = 0x17
    const val PARAM_FILENAME = 0x18
    const val PARAM_START = 0x19
    const val PARAM_START_INFO = 0x1A

    // --- Part headers --------------------------------------------------------------------
    //
    // WSP's header-name table, used inside a multipart entry. Two of them matter: a part says what
    // it is called (Content-Location) and what other parts refer to it by (Content-ID). A SMIL part
    // points at a picture by its Content-ID, which is the only reason this app writes one.

    const val HEADER_CONTENT_LOCATION = 0x0E
    const val HEADER_CONTENT_ID = 0x40
    const val HEADER_CONTENT_DISPOSITION = 0x2E
    const val HEADER_CONTENT_DISPOSITION_NEW = 0x45
}
