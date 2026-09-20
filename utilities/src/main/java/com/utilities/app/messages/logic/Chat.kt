package com.utilities.app.messages.logic

/**
 * A text, as this app holds one.
 *
 * Deliberately not the platform's row. `Telephony.Sms` has thirty columns, four of which anybody
 * needs, and half the app would end up passing a `Cursor` around. What is here is what a bubble
 * draws plus what the list needs to sort and count.
 */
data class ChatMessage(
    /** The provider's row id, or a negative number for one this app is still sending. */
    val id: Long,
    val threadId: Long,
    /** The other party. On an outgoing message, who it went to. */
    val address: String,
    val body: String,
    val at: Long,
    val outgoing: Boolean,
    val read: Boolean = true,
    /** Sent by this app and not yet in the provider's store. See [Outbox]. */
    val pending: Boolean = false,
    /** The carrier refused it, or the radio was off. */
    val failed: Boolean = false,

    /**
     * The pictures, when this is a picture message.
     *
     * A reference rather than the bytes. A thread of two hundred messages holding its photographs in
     * memory is a thread that runs a phone out of it, so what travels is the platform's own URI for
     * the part and the screen decodes what it is about to draw.
     */
    val attachments: List<ChatAttachment> = emptyList(),

    /**
     * A picture message that was announced and never fetched.
     *
     * Its own state rather than an empty message, because there is something to *do* about it: the
     * row carries the URL it is waiting at, and the thread offers a button. It happens when
     * auto-download is off, when the phone was roaming, and when a fetch failed.
     */
    val awaitingDownload: Boolean = false,

    /** Whether this came from the picture-message side of the store. */
    val multimedia: Boolean = false,

    /** A picture message may have one. A text may not. */
    val subject: String? = null
) {
    /** Whether there is anything to draw beyond the words. */
    val hasAttachments: Boolean get() = attachments.isNotEmpty()

    /** A picture message with no words in it is normal, and must not render as a blank bubble. */
    val empty: Boolean get() = body.isBlank() && attachments.isEmpty() && !awaitingDownload
}

/**
 * One piece of a picture message, as the screen refers to it.
 *
 * [uri] is the platform's own address for the part — `content://mms/part/...` — which is what makes
 * this cheap: the bytes stay where the provider put them, and nothing is copied to show a thread.
 */
data class ChatAttachment(
    val uri: String,
    val contentType: String,
    val name: String? = null
) {
    val isImage: Boolean get() = contentType.startsWith("image/", ignoreCase = true)
    val isVideo: Boolean get() = contentType.startsWith("video/", ignoreCase = true)
    val isAudio: Boolean get() = contentType.startsWith("audio/", ignoreCase = true)

    /**
     * What to call it on screen when it cannot be drawn.
     *
     * A filename if the sender gave one, otherwise the kind of thing it is — "a video", not
     * "video/3gpp", which is a MIME type and not a sentence.
     */
    fun label(): String = name?.takeIf { it.isNotBlank() } ?: when {
        isImage -> "a picture"
        isVideo -> "a video"
        isAudio -> "a sound"
        contentType.startsWith("text/x-vCard", ignoreCase = true) -> "a contact card"
        else -> "an attachment"
    }
}

/**
 * A conversation, as the list draws one.
 *
 * [title] is resolved at read time — a contact's name if contacts are readable and one matches,
 * otherwise the number, formatted. It is a field rather than something the UI works out because the
 * list is sorted and searched by it.
 */
data class ChatThread(
    val id: Long,
    val addresses: List<String>,
    val title: String,
    val snippet: String,
    val at: Long,
    val unread: Int
) {
    val group: Boolean get() = addresses.size > 1
}

/**
 * Comparing phone numbers, which is the one piece of this app that is quietly hard.
 *
 * The same person appears as `+1 (555) 010-9999`, `5550109999` and `555-010-9999` depending on
 * whether they texted you, you texted them, or the number came out of a contact card. Two rules
 * settle nearly all of it and neither needs a library: strip everything that is not a digit, then
 * compare the last [SIGNIFICANT_DIGITS] — which is what makes a country code and a leading zero
 * stop mattering without pretending to know which country anybody is in.
 *
 * Short codes (a bank's 5-digit sender) are shorter than that and compare whole, which is correct:
 * two different short codes are two different senders, and there is no prefix to discard.
 */
object Addresses {

    /**
     * Enough digits to identify a subscriber nearly anywhere without the country code.
     *
     * Ten is the North American subscriber number and is long enough that a false match needs two
     * people whose numbers differ only in their country code — rare, and a wrong thread title is
     * the whole cost when it happens.
     */
    const val SIGNIFICANT_DIGITS = 10

    /** The comparable form of an address: digits only, and only the ones that distinguish. */
    fun key(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return raw.trim().lowercase()
        return if (digits.length > SIGNIFICANT_DIGITS) digits.takeLast(SIGNIFICANT_DIGITS) else digits
    }

    /** Whether two addresses are the same person. */
    fun same(a: String, b: String): Boolean = key(a) == key(b)

    /**
     * A number as it is shown when there is no contact to name it.
     *
     * Only the ten-digit case is formatted, because it is the only one whose grouping is not a
     * guess. Everything else — a short code, an international number, an alphanumeric sender like
     * `VERIZON` — is shown exactly as it arrived, which is both honest and what somebody trying to
     * check a number against a letter from their bank actually wants.
     */
    fun display(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        val plus = raw.trimStart().startsWith("+")
        return when {
            digits.length == SIGNIFICANT_DIGITS && !plus ->
                "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"

            digits.length == 11 && digits.startsWith("1") ->
                "(${digits.substring(1, 4)}) ${digits.substring(4, 7)}-${digits.substring(7)}"

            else -> raw.trim()
        }
    }
}
