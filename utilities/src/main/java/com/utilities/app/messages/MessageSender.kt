package com.utilities.app.messages

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import com.utilities.app.messages.logic.OutboxEntry

/**
 * Sending a text.
 *
 * ## Two paths, one method
 *
 * When Utilities holds the default-SMS role it sends *and records*: the message goes into the
 * platform's store as sent, which is what makes it appear in every other app's view of the thread
 * too. When it does not hold the role it may still send — `SEND_SMS` is an ordinary runtime
 * permission — but it may not record, so the message is kept as an echo until whichever app is the
 * default writes it down. [Outbox][com.utilities.app.messages.logic.Outbox] is the whole of that
 * argument; this class is where the two branches are chosen between.
 *
 * ## Long messages
 *
 * A text over 160 characters is several texts, and the radio needs them handed over as parts.
 * `divideMessage` does the splitting by the same rules the network does, so a long message arrives
 * as one message on the other phone rather than as three with words cut in half. A single part goes
 * through the single-part call, because the multipart one on some devices adds a header even to a
 * message that does not need one.
 *
 * ## What comes back
 *
 * A send is not finished when the call returns — it is finished when the radio says so, which is a
 * broadcast that can arrive seconds later or not at all. The result intent updates the stored row
 * (or the echo) to failed, so the thread shows a message that did not go rather than one that
 * silently did not arrive.
 */
class MessageSender(context: Context) {

    private val app = context.applicationContext
    private val store = MessageStore(app)
    private val outbox = OutboxStore(app)

    /**
     * Send [body] to [address].
     *
     * Returns the thread it went to, or null when the text could not be handed to the radio at all —
     * no permission, no SIM, a number the platform will not parse. The caller shows that; everything
     * after it is reported through the result broadcast.
     */
    fun send(address: String, body: String, isDefaultApp: Boolean): Long? {
        if (address.isBlank() || body.isEmpty()) return null
        val threadId = store.threadFor(address) ?: return null
        val at = System.currentTimeMillis()

        val stored: Uri? = if (isDefaultApp) store.storeSent(address, body, at) else null
        if (stored == null) {
            // Nothing recorded it, so this app remembers it until something does.
            outbox.add(threadId, OutboxEntry(address = address, body = body, at = at))
        }

        val manager = smsManager() ?: return null
        val sentIntent = resultIntent(threadId, body, stored)
        val handed = runCatching {
            val parts = manager.divideMessage(body)
            if (parts.size <= 1) {
                manager.sendTextMessage(address, null, body, sentIntent, null)
            } else {
                // Every part reports to the same intent. The platform's signature takes one per
                // part, and handling that by passing the intent once and nulls for the rest would
                // be relying on a null element in a list Kotlin types as non-null. Repeating it is
                // safe because the handler is idempotent: a success is ignored and a failure marks
                // a row that is already marked.
                val sent = ArrayList<PendingIntent>(parts.size)
                repeat(parts.size) { sent.add(sentIntent) }
                manager.sendMultipartTextMessage(address, null, parts, sent, null)
            }
            true
        }.getOrDefault(false)

        if (!handed) {
            fail(threadId, body, stored)
            return null
        }
        return threadId
    }

    private fun smsManager(): SmsManager? = runCatching {
        app.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
    }.getOrNull()

    private fun fail(threadId: Long, body: String, stored: Uri?) {
        if (stored != null) store.markFailed(stored) else outbox.markFailed(threadId, body)
    }

    private fun resultIntent(threadId: Long, body: String, stored: Uri?): PendingIntent {
        val intent = Intent(ACTION_SENT).apply {
            setPackage(app.packageName)
            putExtra(EXTRA_THREAD, threadId)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_STORED, stored?.toString())
        }
        return PendingIntent.getBroadcast(
            app,
            // Distinct per message, so two texts in flight do not overwrite each other's intent.
            (threadId.toInt() * 31) xor body.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_SENT = "com.utilities.app.messages.SMS_SENT"
        const val EXTRA_THREAD = "thread"
        const val EXTRA_BODY = "body"
        const val EXTRA_STORED = "stored"
    }
}

/**
 * What the radio says afterwards.
 *
 * Declared in the manifest rather than registered from a screen, and that is the whole reason it is
 * a separate class: a send is not finished when the call returns, it is finished when the radio
 * reports, which can be seconds later — long enough for somebody to have left the app. A receiver
 * that only existed while a thread was on screen would miss exactly the failures worth recording.
 *
 * Not exported, and reachable anyway: the `PendingIntent` that carries this action sets this app's
 * package, which both makes the broadcast explicit and exempts it from the implicit-broadcast
 * restriction that would otherwise stop a manifest receiver hearing it at all.
 *
 * A success is ignored. The row was written as sent when it was handed over, which is what it is,
 * and the only thing worth acting on is the case where it did not go.
 */
class SmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        if (intent?.action != MessageSender.ACTION_SENT) return
        if (resultCode == android.app.Activity.RESULT_OK) return

        val stored = intent.getStringExtra(MessageSender.EXTRA_STORED)
        if (stored != null) {
            MessageStore(app).markFailed(Uri.parse(stored))
            return
        }
        val threadId = intent.getLongExtra(MessageSender.EXTRA_THREAD, -1L)
        if (threadId >= 0) {
            OutboxStore(app).markFailed(threadId, intent.getStringExtra(MessageSender.EXTRA_BODY).orEmpty())
        }
    }
}
