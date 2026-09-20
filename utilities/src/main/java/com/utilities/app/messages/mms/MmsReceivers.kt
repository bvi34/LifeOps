package com.utilities.app.messages.mms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.utilities.app.messages.MessageNotifier
import com.utilities.app.messages.MessagesRole
import com.utilities.app.messages.logic.Addresses
import com.utilities.app.messages.pdu.MmsMessage
import com.utilities.app.messages.pdu.PduDecoder

/**
 * What the network says, afterwards.
 *
 * Both of these are **manifest** receivers, and that is the whole reason they are here rather than
 * registered from a screen: an MMS transfer takes seconds when it is quick and minutes when it is
 * not, over a data connection the platform brings up for the purpose, and the app is very often not
 * on screen — or not running — when the answer comes. A receiver that existed only while a thread
 * was open would miss most of them.
 *
 * Neither is exported. What reaches them is the `PendingIntent` the transport made, which names this
 * package, and that both makes the broadcast explicit and exempts it from the restriction that would
 * otherwise stop a manifest receiver hearing it at all.
 */

/**
 * A picture message finished downloading — or failed to.
 *
 * The order here is the order that matters. Parse first, and only then touch the store: a
 * placeholder deleted before the real message is filed is a message that has disappeared. The
 * placeholder goes last, after the message it is standing in for exists.
 */
class MmsDownloadedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        if (intent?.action != MmsTransport.ACTION_DOWNLOADED) return

        val name = intent.getStringExtra(MmsTransport.EXTRA_FILE)
        val uri = intent.getStringExtra(MmsTransport.EXTRA_URI)?.let(Uri::parse)
        val pendingRow = intent.getLongExtra(MmsTransport.EXTRA_ROW, -1L)
        val subscriptionId = intent.getIntExtra(MmsTransport.EXTRA_SUBSCRIPTION, -1)

        // Whatever happened, the grant and the buffer go. Both are cheap to lose and expensive to
        // leave behind — one is a standing read permission, the other is somebody's photograph in
        // a cache directory.
        try {
            if (resultCode != Activity.RESULT_OK) {
                // The placeholder stays exactly where it is: the thread keeps showing the message
                // as waiting, with a button, which is the truthful state.
                return
            }

            val bytes = name?.let { runCatching { MmsFileProvider.file(app, it).readBytes() }.getOrNull() }
            if (bytes == null || bytes.isEmpty()) return

            val message = PduDecoder.message(bytes) ?: return
            file(app, message, pendingRow, subscriptionId)

            // The "I have it" acknowledgement is not sent from here. The platform's own MMS service
            // sends `m-acknowledge-ind` as part of a successful `downloadMultimediaMessage`, and a
            // second one from this app would be a duplicate the MMSC has to reconcile. The one
            // acknowledgement this app *does* send itself is the deferral, when it declines to
            // download at all — see `MmsTransport.respond`.
        } finally {
            uri?.let { MmsTransport.revoke(app, it) }
            name?.let { MmsFileProvider.delete(app, it) }
        }
    }

    /** Put the message in the store, take the placeholder out, and say so. */
    private fun file(app: Context, message: MmsMessage, pendingRow: Long, subscriptionId: Int) {
        if (!MessagesRole.isDefault(app)) return
        val store = MmsStore(app)

        val parties = buildSet {
            message.from?.takeIf { it.isNotBlank() && it != MmsStore.INSERT_ADDRESS_TOKEN }?.let(::add)
            addAll(message.to.filter { it.isNotBlank() })
        }
        if (parties.isEmpty()) return
        val threadId = store.threadFor(parties) ?: return

        val stored = store.storeIncoming(message, threadId, subscriptionId)
        // Only now: the message it stood in for exists.
        if (stored != null && pendingRow >= 0) store.deleteMessage(pendingRow)

        if (stored == null) return

        val sender = message.from ?: parties.first()
        MessageNotifier(app).arrived(
            address = Addresses.display(sender),
            body = summary(message),
            threadId = threadId
        )
    }

    /** What the notification says a picture message contains. */
    private fun summary(message: MmsMessage): String {
        val words = message.text().takeIf { it.isNotBlank() }
        if (words != null) return words
        return when (val count = message.attachments().size) {
            0 -> "Sent a message"
            1 -> "Sent a picture"
            else -> "Sent $count pictures"
        }
    }
}

/**
 * A picture message finished sending — or failed to.
 *
 * Moves the outbox row to sent or to failed, which is what the bubble reads. A success is not
 * silent here the way a text message's is: an MMS is written to the outbox *before* the radio is
 * asked, so something has to move it.
 */
class MmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        if (intent?.action != MmsTransport.ACTION_SENT) return

        val name = intent.getStringExtra(MmsTransport.EXTRA_FILE)
        val uri = intent.getStringExtra(MmsTransport.EXTRA_URI)?.let(Uri::parse)
        val row = intent.getStringExtra(MmsTransport.EXTRA_ROW_URI)?.let(Uri::parse)
        val sent = resultCode == Activity.RESULT_OK

        try {
            if (row != null) MmsStore(app).markSent(row, sent)
        } finally {
            uri?.let { MmsTransport.revoke(app, it) }
            name?.let { MmsFileProvider.delete(app, it) }
        }
    }
}
