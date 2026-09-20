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
    val failed: Boolean = false
)

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
