package com.utilities.app.messages

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import com.utilities.app.messages.logic.Addresses
import com.utilities.app.messages.logic.ChatMessage
import com.utilities.app.messages.logic.ChatThread

/**
 * The phone's own message store, read.
 *
 * ## The store is not ours, and that is the feature
 *
 * Every other app in this suite owns its data: a Room database it wrote, in a shape it chose. This
 * one deliberately owns none. The texts are in Android's `Telephony` provider, put there by whatever
 * app has been delivering them for years, and they stay there. What Utilities supplies is the
 * window.
 *
 * That is what makes the takeover *reversible*, which is the property that makes it safe to try.
 * Switching back to the carrier's app loses nothing, because nothing was moved. It is also what
 * makes the lower rung of the takeover possible at all — reading and replying while somebody else
 * still delivers — and it is why this app's backup slice carries an appearance file and no messages:
 * the texts are the platform's, and the platform's own backup already has them.
 *
 * ## Two things the platform will refuse
 *
 * Reading needs `READ_SMS`, which is a runtime permission and can be declined; **writing** — marking
 * a thread read, recording a sent message — is allowed only for the app holding the default-SMS
 * role, and there is no permission to ask for. Both refusals arrive as a `SecurityException` from
 * the content resolver rather than as a check anybody can make in advance, so every call here is
 * wrapped and every one of them degrades rather than throwing: a thread that cannot be marked read
 * stays unread, and the app keeps working.
 */
class MessageStore(context: Context) {

    private val app = context.applicationContext
    private val resolver = app.contentResolver

    // -------------------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------------------

    /**
     * Every conversation, newest first.
     *
     * Read from the provider's `conversations` view rather than by grouping the messages, because
     * that view is what the platform keeps up to date across SMS *and* MMS — a thread whose most
     * recent message is a picture still sorts correctly here, even though this app cannot show that
     * message yet.
     */
    fun threads(limit: Int = 200): List<ChatThread> {
        val names = ContactNames(app)
        val addresses = canonicalAddresses()
        return query(
            uri = CONVERSATIONS,
            projection = arrayOf("_id", "date", "message_count", "recipient_ids", "snippet"),
            sortOrder = "date DESC"
        ) { cursor ->
            val rows = ArrayList<ChatThread>()
            while (cursor.moveToNext() && rows.size < limit) {
                val id = cursor.getLong(0)
                val recipients = cursor.getStringOrNull(3)
                    .orEmpty()
                    .split(' ')
                    .mapNotNull { it.trim().toLongOrNull() }
                    .mapNotNull { addresses[it] }
                if (recipients.isEmpty()) continue
                rows.add(
                    ChatThread(
                        id = id,
                        addresses = recipients,
                        title = names.title(recipients),
                        snippet = cursor.getStringOrNull(4).orEmpty(),
                        at = cursor.getLong(1),
                        unread = 0
                    )
                )
            }
            rows
        }.orEmpty().withUnreadCounts()
    }

    /** One thread's texts, oldest first. */
    fun messages(threadId: Long, limit: Int = 500): List<ChatMessage> = query(
        uri = Telephony.Sms.CONTENT_URI,
        projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ
        ),
        selection = "${Telephony.Sms.THREAD_ID} = ?",
        selectionArgs = arrayOf(threadId.toString()),
        sortOrder = "${Telephony.Sms.DATE} DESC"
    ) { cursor ->
        val rows = ArrayList<ChatMessage>()
        while (cursor.moveToNext() && rows.size < limit) {
            val type = cursor.getInt(4)
            rows.add(
                ChatMessage(
                    id = cursor.getLong(0),
                    threadId = threadId,
                    address = cursor.getStringOrNull(1).orEmpty(),
                    body = cursor.getStringOrNull(2).orEmpty(),
                    at = cursor.getLong(3),
                    outgoing = type != Telephony.Sms.MESSAGE_TYPE_INBOX,
                    read = cursor.getInt(5) != 0,
                    failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED
                )
            )
        }
        rows.reversed()
    }.orEmpty()

    /** How many texts are unread across every thread — the badge, and the notification's count. */
    fun unreadCount(): Int = query(
        uri = Telephony.Sms.Inbox.CONTENT_URI,
        projection = arrayOf(Telephony.Sms._ID),
        selection = "${Telephony.Sms.READ} = 0"
    ) { it.count }.orZero()

    /** The thread a number belongs to, creating it if the provider has never seen the number. */
    fun threadFor(address: String): Long? = runCatching {
        Telephony.Threads.getOrCreateThreadId(app, address)
    }.getOrNull()

    /** A contact's name for a number, or the number formatted. Public because the UI titles with it. */
    fun displayName(address: String): String = ContactNames(app).of(address)

    // -------------------------------------------------------------------------------------
    // Writing — default SMS app only
    // -------------------------------------------------------------------------------------

    /**
     * Mark a thread's incoming texts as read and seen.
     *
     * Both columns, because they mean different things and both are wrong if only one is set:
     * `read` is "you have looked at it", `seen` is "you have been told about it", and a thread
     * marked read but not seen comes back as a notification.
     */
    fun markRead(threadId: Long): Boolean = runCatching {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        resolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(threadId.toString())
        )
        true
    }.getOrDefault(false)

    /** Record a text this app received. Only the default SMS app may; nothing else stores it. */
    fun storeIncoming(address: String, body: String, at: Long, subscriptionId: Int = -1): Uri? = runCatching {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, at)
            put(Telephony.Sms.DATE_SENT, at)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            if (subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
        }
        resolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
    }.getOrNull()

    /** Record a text this app sent. Returns null when this app is not the default and may not. */
    fun storeSent(address: String, body: String, at: Long): Uri? = runCatching {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, at)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        resolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
    }.getOrNull()

    /** Move a stored message into the failed state, so the thread can say so. */
    fun markFailed(uri: Uri): Boolean = runCatching {
        val values = ContentValues().apply {
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
        }
        resolver.update(uri, values, null, null) > 0
    }.getOrDefault(false)

    fun delete(messageId: Long): Boolean = runCatching {
        resolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, messageId), null, null) > 0
    }.getOrDefault(false)

    // -------------------------------------------------------------------------------------
    // The plumbing
    // -------------------------------------------------------------------------------------

    /**
     * The provider's number table: `recipient_ids` on a conversation row are keys into it rather
     * than numbers. Read once per listing and passed down, because a thread list of fifty rows
     * would otherwise be fifty more queries.
     */
    private fun canonicalAddresses(): Map<Long, String> = query(
        uri = CANONICAL_ADDRESSES,
        projection = arrayOf("_id", "address")
    ) { cursor ->
        val map = HashMap<Long, String>()
        while (cursor.moveToNext()) {
            val address = cursor.getStringOrNull(1)
            if (!address.isNullOrBlank()) map[cursor.getLong(0)] = address
        }
        map
    }.orEmpty()

    /**
     * Fill in each thread's unread count in one query rather than one per thread.
     *
     * Grouping in SQL would be neater and is not available: the provider does not honour a GROUP BY
     * smuggled into the selection, and the versions that did were a well-known injection hole that
     * was closed. So the unread rows are read — there are rarely many — and counted here.
     */
    private fun List<ChatThread>.withUnreadCounts(): List<ChatThread> {
        val counts = query(
            uri = Telephony.Sms.Inbox.CONTENT_URI,
            projection = arrayOf(Telephony.Sms.THREAD_ID),
            selection = "${Telephony.Sms.READ} = 0"
        ) { cursor ->
            val map = HashMap<Long, Int>()
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                map[id] = (map[id] ?: 0) + 1
            }
            map
        }.orEmpty()
        if (counts.isEmpty()) return this
        return map { thread -> thread.copy(unread = counts[thread.id] ?: 0) }
    }

    private fun <T> query(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = null,
        read: (Cursor) -> T
    ): T? = runCatching {
        resolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use(read)
    }.getOrNull()

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun Int?.orZero(): Int = this ?: 0

    private companion object {
        /**
         * `simple=true` asks the provider for the thread summary rows rather than the union of every
         * message in every thread — the difference between one query and one per conversation.
         */
        val CONVERSATIONS: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
        val CANONICAL_ADDRESSES: Uri = Uri.parse("content://mms-sms/canonical-addresses")
    }
}

/**
 * Numbers turned into names, when contacts are readable.
 *
 * A tiny cache, built per listing and thrown away: a thread list asks about the same handful of
 * numbers repeatedly, and `PhoneLookup` is a real query each time. Declining the contacts permission
 * is not an error — the thread is titled with the formatted number, which is what somebody who
 * declined it asked for.
 */
private class ContactNames(private val context: Context) {

    private val cache = HashMap<String, String>()

    fun of(address: String): String = cache.getOrPut(Addresses.key(address)) {
        lookup(address) ?: Addresses.display(address)
    }

    /** A group thread is named by its members, in the order the provider listed them. */
    fun title(addresses: List<String>): String = addresses.joinToString(", ") { of(it) }

    private fun lookup(address: String): String? = runCatching {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()
}
