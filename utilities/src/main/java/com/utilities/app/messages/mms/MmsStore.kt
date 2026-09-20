package com.utilities.app.messages.mms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import com.utilities.app.messages.logic.ChatAttachment
import com.utilities.app.messages.logic.ChatMessage
import com.utilities.app.messages.pdu.MmsHeaders
import com.utilities.app.messages.pdu.MmsMessage
import com.utilities.app.messages.pdu.MmsPart
import com.utilities.app.messages.pdu.Wsp

/**
 * Picture messages, in the platform's own store.
 *
 * The same bargain the text half strikes: Android owns the data, this app owns the window. An MMS
 * lives in three tables — the message, its addresses, and its parts — and every one of them belongs
 * to `content://mms`, put there by whatever app has been the messenger. Utilities reads them, and
 * writes them only while it holds the role.
 *
 * ## Three tables, and why
 *
 * A text message is one row because it is one string. A picture message is a **message** (when, from
 * whom, what about), a set of **addresses** (a group message has several), and a set of **parts**
 * (the words, the pictures, and the layout nobody displays). Reading one means three queries, and
 * the parts query is the expensive one — so it is done once per thread rather than once per message
 * ([partsByMessage]).
 *
 * ## Seconds and millis
 *
 * `content://mms` stores its dates in **seconds**; `content://sms` stores them in **milliseconds**.
 * That is not a mistake anybody can fix, and the two halves of a thread appear in the wrong order
 * the moment somebody forgets it. The conversion happens here, at the edge, and everything above
 * this file is in millis.
 */
class MmsStore(context: Context) {

    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /**
     * Every picture message in a thread, as the bubbles show them.
     *
     * A row whose type is `notification-ind` is one that was announced and never fetched: it comes
     * back marked [ChatMessage.awaitingDownload] and the thread offers a button rather than
     * pretending there is nothing there.
     */
    fun messages(threadId: Long, limit: Int = 200): List<ChatMessage> {
        val rows = query(
            uri = Telephony.Mms.CONTENT_URI,
            projection = arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.DATE,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.READ,
                Telephony.Mms.SUBJECT,
                Telephony.Mms.MESSAGE_TYPE
            ),
            selection = "${Telephony.Mms.THREAD_ID} = ?",
            selectionArgs = arrayOf(threadId.toString()),
            sortOrder = "${Telephony.Mms.DATE} DESC"
        ) { cursor ->
            val found = ArrayList<Row>()
            while (cursor.moveToNext() && found.size < limit) {
                found.add(
                    Row(
                        id = cursor.getLong(0),
                        // Seconds here, millis everywhere above. See the class note.
                        at = cursor.getLong(1) * 1000L,
                        box = cursor.getInt(2),
                        read = cursor.getInt(3) != 0,
                        subject = cursor.getStringOrNull(4),
                        type = cursor.getInt(5)
                    )
                )
            }
            found
        }.orEmpty()

        if (rows.isEmpty()) return emptyList()

        val parts = partsByMessage(rows.map { it.id })
        val senders = sendersByMessage(rows.map { it.id })

        return rows.map { row ->
            val own = parts[row.id].orEmpty()
            val awaiting = row.type == MmsHeaders.TYPE_NOTIFICATION_IND
            ChatMessage(
                // Negative, and offset, so an MMS row and an SMS row with the same id can sit in
                // one list without colliding — they are different tables with their own counters.
                id = mmsKey(row.id),
                threadId = threadId,
                address = senders[row.id].orEmpty(),
                body = own.filter { it.isText }.joinToString("\n") { it.text }.trim(),
                at = row.at,
                outgoing = row.box == Telephony.Mms.MESSAGE_BOX_SENT || row.box == Telephony.Mms.MESSAGE_BOX_OUTBOX,
                read = row.read,
                pending = row.box == Telephony.Mms.MESSAGE_BOX_OUTBOX,
                failed = row.box == MESSAGE_BOX_FAILED,
                attachments = own.filter { !it.isText }.map {
                    ChatAttachment(uri = it.uri, contentType = it.contentType, name = it.name)
                },
                awaitingDownload = awaiting,
                multimedia = true,
                subject = row.subject?.takeIf { it.isNotBlank() }
            )
        }
    }

    /** Unread picture messages, by thread — folded into the list's counts alongside the texts. */
    fun unreadByThread(): Map<Long, Int> = query(
        uri = Telephony.Mms.CONTENT_URI,
        projection = arrayOf(Telephony.Mms.THREAD_ID),
        selection = "${Telephony.Mms.READ} = 0 AND ${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX}"
    ) { cursor ->
        val counts = HashMap<Long, Int>()
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            counts[id] = (counts[id] ?: 0) + 1
        }
        counts
    }.orEmpty()

    /** Mark a thread's picture messages read and seen. Only the default app may. */
    fun markRead(threadId: Long): Boolean = runCatching {
        val values = ContentValues().apply {
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
        }
        resolver.update(
            Telephony.Mms.CONTENT_URI,
            values,
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0",
            arrayOf(threadId.toString())
        )
        true
    }.getOrDefault(false)

    // -------------------------------------------------------------------------------------
    // Writing — default SMS app only
    // -------------------------------------------------------------------------------------

    /**
     * File a picture message that has just been downloaded.
     *
     * Returns the row's URI, or null when this app may not write — which is the ordinary answer on
     * the reading rung and is not an error. The order is deliberate: the message row first (the
     * parts need its id), then the addresses, then the parts, so a reader that arrives mid-write
     * sees a message with too few parts rather than parts belonging to nothing.
     */
    fun storeIncoming(message: MmsMessage, threadId: Long, subscriptionId: Int = -1): Uri? = runCatching {
        val values = ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, threadId)
            put(Telephony.Mms.DATE, (message.date.takeIf { it > 0 } ?: System.currentTimeMillis()) / 1000L)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, 0)
            put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.MESSAGE_TYPE, MmsHeaders.TYPE_RETRIEVE_CONF)
            put(Telephony.Mms.MMS_VERSION, MMS_VERSION_STORED)
            put(Telephony.Mms.CONTENT_TYPE, message.bodyType)
            put(Telephony.Mms.LOCKED, 0)
            message.messageId?.let { put(Telephony.Mms.MESSAGE_ID, it) }
            message.transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, it) }
            message.subject?.takeIf { it.isNotBlank() }?.let {
                put(Telephony.Mms.SUBJECT, it)
                put(Telephony.Mms.SUBJECT_CHARSET, Wsp.CHARSET_UTF_8)
            }
            if (subscriptionId >= 0) put(Telephony.Mms.SUBSCRIPTION_ID, subscriptionId)
        }
        val uri = resolver.insert(Telephony.Mms.CONTENT_URI, values) ?: return null
        val id = ContentUris.parseId(uri)

        message.from?.let { writeAddress(id, it, MmsHeaders.ADDRESS_FROM) }
        message.to.forEach { writeAddress(id, it, MmsHeaders.ADDRESS_TO) }
        message.parts.forEachIndexed { index, part -> writePart(id, index, part) }
        uri
    }.getOrNull()

    /**
     * File a picture message this app is about to send, in the outbox.
     *
     * It goes in *before* the radio is asked, so the thread shows it immediately and so a send that
     * fails has a row to be marked against. [markSent] moves it when the network answers.
     */
    fun storeOutgoing(message: MmsMessage, threadId: Long, subscriptionId: Int = -1): Uri? = runCatching {
        val values = ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, threadId)
            put(Telephony.Mms.DATE, (message.date.takeIf { it > 0 } ?: System.currentTimeMillis()) / 1000L)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_TYPE, MmsHeaders.TYPE_SEND_REQ)
            put(Telephony.Mms.MMS_VERSION, MMS_VERSION_STORED)
            put(Telephony.Mms.CONTENT_TYPE, message.bodyType)
            put(Telephony.Mms.LOCKED, 0)
            message.transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, it) }
            message.subject?.takeIf { it.isNotBlank() }?.let {
                put(Telephony.Mms.SUBJECT, it)
                put(Telephony.Mms.SUBJECT_CHARSET, Wsp.CHARSET_UTF_8)
            }
            if (subscriptionId >= 0) put(Telephony.Mms.SUBSCRIPTION_ID, subscriptionId)
        }
        val uri = resolver.insert(Telephony.Mms.CONTENT_URI, values) ?: return null
        val id = ContentUris.parseId(uri)
        message.to.forEach { writeAddress(id, it, MmsHeaders.ADDRESS_TO) }
        message.parts.forEachIndexed { index, part -> writePart(id, index, part) }
        uri
    }.getOrNull()

    /** Move an outbox row into sent, or into failed. */
    fun markSent(uri: Uri, sent: Boolean): Boolean = runCatching {
        val values = ContentValues().apply {
            put(
                Telephony.Mms.MESSAGE_BOX,
                if (sent) Telephony.Mms.MESSAGE_BOX_SENT else MESSAGE_BOX_FAILED
            )
        }
        resolver.update(uri, values, null, null) > 0
    }.getOrDefault(false)

    /**
     * Record that a picture message was announced and not fetched.
     *
     * A row of its own rather than a notification and nothing else, so it is still there tomorrow:
     * the thread shows it with a Download button, and the URL it needs travels in the row's content
     * location. This is the path taken when auto-download is off, when the phone is roaming, or
     * when the fetch failed.
     */
    fun storePending(
        threadId: Long,
        from: String?,
        subject: String?,
        contentLocation: String?,
        transactionId: String?,
        size: Long,
        at: Long = System.currentTimeMillis()
    ): Uri? = runCatching {
        val values = ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, threadId)
            put(Telephony.Mms.DATE, at / 1000L)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, 0)
            put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.MESSAGE_TYPE, MmsHeaders.TYPE_NOTIFICATION_IND)
            put(Telephony.Mms.MMS_VERSION, MMS_VERSION_STORED)
            put(Telephony.Mms.MESSAGE_SIZE, size)
            contentLocation?.let { put(Telephony.Mms.CONTENT_LOCATION, it) }
            transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, it) }
            subject?.takeIf { it.isNotBlank() }?.let {
                put(Telephony.Mms.SUBJECT, it)
                put(Telephony.Mms.SUBJECT_CHARSET, Wsp.CHARSET_UTF_8)
            }
        }
        val uri = resolver.insert(Telephony.Mms.CONTENT_URI, values) ?: return null
        from?.let { writeAddress(ContentUris.parseId(uri), it, MmsHeaders.ADDRESS_FROM) }
        uri
    }.getOrNull()

    /** Where a pending row says its message is waiting, so the Download button has somewhere to go. */
    fun pendingLocation(messageId: Long): PendingFetch? = query(
        uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, messageId),
        projection = arrayOf(Telephony.Mms.CONTENT_LOCATION, Telephony.Mms.TRANSACTION_ID, Telephony.Mms.THREAD_ID)
    ) { cursor ->
        if (!cursor.moveToFirst()) null
        else PendingFetch(
            messageId = messageId,
            contentLocation = cursor.getStringOrNull(0),
            transactionId = cursor.getStringOrNull(1),
            threadId = cursor.getLong(2)
        )
    }

    /** Throw away the placeholder once the real message has been filed in its place. */
    fun deleteMessage(messageId: Long): Boolean = runCatching {
        resolver.delete(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, messageId), null, null) > 0
    }.getOrDefault(false)

    /** The thread a set of addresses belongs to, created if the provider has not seen them. */
    fun threadFor(addresses: Set<String>): Long? = runCatching {
        Telephony.Threads.getOrCreateThreadId(app, addresses)
    }.getOrNull()

    // -------------------------------------------------------------------------------------
    // The three tables
    // -------------------------------------------------------------------------------------

    private fun writeAddress(messageId: Long, address: String, type: Int) {
        runCatching {
            val values = ContentValues().apply {
                put("address", address)
                put("type", type)
                put("charset", Wsp.CHARSET_UTF_8)
            }
            resolver.insert(Uri.parse("content://mms/$messageId/addr"), values)
        }
    }

    /**
     * One part.
     *
     * Text goes in the `text` column, where every other messaging app on the phone will find it;
     * everything else is written through a stream to the row's own file. The layout part is written
     * too rather than dropped — it is part of the message as it arrived, and an app that re-sends or
     * exports the thread later should not find it silently edited.
     */
    private fun writePart(messageId: Long, sequence: Int, part: MmsPart) {
        runCatching {
            val values = ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, messageId)
                put(Telephony.Mms.Part.SEQ, if (part.isSmil) -1 else sequence)
                put(Telephony.Mms.Part.CONTENT_TYPE, part.contentType)
                part.name?.let { put(Telephony.Mms.Part.NAME, it) }
                part.contentId?.let { put(Telephony.Mms.Part.CONTENT_ID, "<$it>") }
                part.contentLocation?.let { put(Telephony.Mms.Part.CONTENT_LOCATION, it) }
                if (part.isText) {
                    put(Telephony.Mms.Part.CHARSET, Wsp.CHARSET_UTF_8)
                    put(Telephony.Mms.Part.TEXT, part.text().orEmpty())
                }
            }
            val uri = resolver.insert(Uri.parse("content://mms/$messageId/part"), values) ?: return
            if (!part.isText) {
                resolver.openOutputStream(uri)?.use { it.write(part.data) }
            }
        }
    }

    /**
     * Every part of every message in one query.
     *
     * One query rather than one per message, because a thread of two hundred picture messages would
     * otherwise be two hundred round trips into a provider that is not fast.
     */
    private fun partsByMessage(messageIds: List<Long>): Map<Long, List<StoredPart>> {
        if (messageIds.isEmpty()) return emptyMap()
        val placeholders = messageIds.joinToString(",") { "?" }
        return query(
            uri = Telephony.Mms.Part.CONTENT_URI,
            projection = arrayOf(
                Telephony.Mms.Part._ID,
                Telephony.Mms.Part.MSG_ID,
                Telephony.Mms.Part.CONTENT_TYPE,
                Telephony.Mms.Part.NAME,
                Telephony.Mms.Part.TEXT,
                Telephony.Mms.Part.CHARSET
            ),
            selection = "${Telephony.Mms.Part.MSG_ID} IN ($placeholders)",
            selectionArgs = messageIds.map { it.toString() }.toTypedArray(),
            sortOrder = Telephony.Mms.Part.SEQ
        ) { cursor ->
            val byMessage = HashMap<Long, MutableList<StoredPart>>()
            while (cursor.moveToNext()) {
                val partId = cursor.getLong(0)
                val messageId = cursor.getLong(1)
                val type = cursor.getStringOrNull(2).orEmpty()
                // The layout part is stored and never shown. See PduEncoder.smil.
                if (type.equals(Wsp.SMIL, ignoreCase = true)) continue
                val isText = type.startsWith("text/", ignoreCase = true)
                byMessage.getOrPut(messageId) { ArrayList() }.add(
                    StoredPart(
                        uri = ContentUris.withAppendedId(Telephony.Mms.Part.CONTENT_URI, partId).toString(),
                        contentType = type,
                        name = cursor.getStringOrNull(3),
                        text = if (isText) cursor.getStringOrNull(4).orEmpty() else "",
                        isText = isText
                    )
                )
            }
            byMessage
        }.orEmpty()
    }

    /**
     * Who each message came from.
     *
     * The address table holds every party to the message; the one with type `from` is the sender,
     * and on an outgoing message there is not one — which is why the fallback is the first address
     * rather than an empty string.
     */
    private fun sendersByMessage(messageIds: List<Long>): Map<Long, String> {
        val senders = HashMap<Long, String>()
        messageIds.forEach { id ->
            val found = query(
                uri = Uri.parse("content://mms/$id/addr"),
                projection = arrayOf("address", "type")
            ) { cursor ->
                var from: String? = null
                var any: String? = null
                while (cursor.moveToNext()) {
                    val address = cursor.getStringOrNull(0)?.takeIf { it.isNotBlank() } ?: continue
                    if (address == INSERT_ADDRESS_TOKEN) continue
                    if (cursor.getInt(1) == MmsHeaders.ADDRESS_FROM && from == null) from = address
                    if (any == null) any = address
                }
                from ?: any
            }
            if (found != null) senders[id] = found
        }
        return senders
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

    private class Row(
        val id: Long,
        val at: Long,
        val box: Int,
        val read: Boolean,
        val subject: String?,
        val type: Int
    )

    private class StoredPart(
        val uri: String,
        val contentType: String,
        val name: String?,
        val text: String,
        val isText: Boolean
    )

    /** What a Download button needs to know. */
    data class PendingFetch(
        val messageId: Long,
        val contentLocation: String?,
        val transactionId: String?,
        val threadId: Long
    )

    companion object {

        /**
         * What the provider writes when the sender asked the network to fill its address in.
         *
         * A literal that turns up as an address and is not one. Shown as a sender it would read as
         * a message from somebody called "insert-address-token".
         */
        const val INSERT_ADDRESS_TOKEN = "insert-address-token"

        /** MMS 1.2, as the provider stores it: the version nibbles without the high bit. */
        const val MMS_VERSION_STORED = 0x12

        /**
         * A send that did not go.
         *
         * Written out rather than taken from `Telephony`, which names the other four boxes and has
         * been inconsistent about this one across releases — and a constant that resolves on the
         * build machine and not on somebody's phone is the worst kind.
         */
        const val MESSAGE_BOX_FAILED = 5

        /**
         * MMS row ids and SMS row ids are two counters in two tables, and the thread shows both in
         * one list. Offsetting the MMS side keeps them apart without either store knowing.
         */
        fun mmsKey(id: Long): Long = MMS_ID_BASE + id

        /** The message id behind a key, or null when the key belongs to a text message. */
        fun messageIdOf(key: Long): Long? = if (key >= MMS_ID_BASE) key - MMS_ID_BASE else null

        private const val MMS_ID_BASE = 1_000_000_000_000L
    }
}
