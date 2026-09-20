package com.utilities.app.messages

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import com.utilities.app.messages.logic.OutboxEntry
import com.utilities.app.messages.logic.Routing
import com.utilities.app.messages.logic.SendPlan
import com.utilities.app.messages.logic.SendRefusal
import com.utilities.app.messages.logic.SendRoute
import com.utilities.app.messages.mms.MmsImages
import com.utilities.app.messages.mms.MmsStore
import com.utilities.app.messages.mms.MmsTransport
import com.utilities.app.messages.pdu.MmsBudget
import com.utilities.app.messages.pdu.MmsHeaders
import com.utilities.app.messages.pdu.MmsMessage
import com.utilities.app.messages.pdu.MmsPart
import com.utilities.app.messages.pdu.PduEncoder
import com.utilities.app.messages.seal.KeyExchange
import com.utilities.app.messages.seal.Sealing

/**
 * Sending — a text, or a picture message, or a refusal with a reason.
 *
 * ## Which one
 *
 * [Routing] decides, from four facts, and it is pure so that the decision is tested rather than
 * inferred from reading a `when`. This class carries out whichever answer it gets.
 *
 * ## The text path
 *
 * When Utilities holds the default-SMS role it sends *and records*. When it does not it may still
 * send — `SEND_SMS` is an ordinary runtime permission — but may not record, so the message is kept
 * as an echo until whichever app is the default writes it down. See [com.utilities.app.messages.logic.Outbox].
 *
 * ## The picture path
 *
 * Needs the role, and says so rather than failing quietly: only the default app may write a sent
 * MMS into the store, and there is no echo for a photograph. The message is written to the outbox
 * *before* the radio is asked, so the thread shows it immediately and a failure has a row to be
 * marked against; [com.utilities.app.messages.mms.MmsSentReceiver] moves it when the network answers.
 *
 * Every picture is re-encoded to fit the carrier's cap first — see [MmsBudget] and [MmsImages] —
 * because a message over the cap is accepted by the radio and dropped by the network, with no error
 * anywhere.
 */
class MessageSender(context: Context) {

    private val app = context.applicationContext
    private val store = MessageStore(app)
    private val outbox = OutboxStore(app)
    private val mms = MmsStore(app)
    private val transport = MmsTransport(app)
    private val sealing = Sealing(app)

    /** What happened. A failure carries the sentence the composer shows. */
    sealed interface Outcome {
        data class Sent(val threadId: Long) : Outcome
        data class Refused(val reason: String) : Outcome
    }

    /**
     * Send [body] and [attachments] to [addresses].
     *
     * One entry point for both protocols, because the composer should not have to know which one it
     * is asking for — it has a draft and a list of pictures, and which of the two it becomes is a
     * property of those and of the phone.
     */
    fun send(
        addresses: List<String>,
        body: String,
        attachments: List<Uri> = emptyList(),
        subject: String? = null,
        isDefaultApp: Boolean = MessagesRole.isDefault(app),
        subscriptionId: Int = -1
    ): Outcome {
        val recipients = addresses.filter { it.isNotBlank() }
        val prefs = MessagePrefs(app)
        val plan: SendPlan = Routing.plan(
            recipients = recipients.size,
            attachments = attachments.size,
            hasBody = body.isNotBlank(),
            hasSubject = !subject.isNullOrBlank(),
            groupAsPicture = prefs.groupAsMms,
            canSend = MessagesRole.canSend(app),
            isDefaultApp = isDefaultApp
        )

        return when (plan.route) {
            SendRoute.REFUSE -> Outcome.Refused((plan.refusal ?: SendRefusal.EMPTY).reason)
            SendRoute.TEXT -> text(recipients, body, isDefaultApp)
            SendRoute.PICTURE -> picture(recipients, body, attachments, subject, prefs, subscriptionId)
        }
    }

    // -------------------------------------------------------------------------------------
    // Text
    // -------------------------------------------------------------------------------------

    /**
     * A text, to one person or to each of several.
     *
     * Several recipients here means the household turned group messaging off, or Utilities is not
     * the default app — in both cases the right behaviour is one text each, which is what every
     * phone did before MMS existed.
     */
    private fun text(addresses: List<String>, body: String, isDefaultApp: Boolean): Outcome {
        if (body.isEmpty()) return Outcome.Refused(SendRefusal.EMPTY.reason)
        var threadId: Long? = null
        var sentAny = false
        addresses.forEach { address ->
            val thread = sendOneText(address, body, isDefaultApp)
            if (thread != null) {
                sentAny = true
                if (threadId == null) threadId = thread
            }
        }
        // For a group sent as separate texts, the thread the composer returns to is the group's,
        // not the first recipient's — otherwise replying lands in a one-to-one conversation.
        val landing = if (addresses.size > 1) store.threadFor(addresses.first()) ?: threadId else threadId
        return if (sentAny && landing != null) Outcome.Sent(landing)
        else Outcome.Refused("That message could not be sent.")
    }

    /**
     * One text, to one person.
     *
     * ## The plaintext and the wire are different strings
     *
     * This is the only place in the app where that is true and it is the whole of how sealing works
     * from the outside. What is **stored** — in our own sent box, and in the outbox echo — is what
     * the household typed, because a sent-messages list they cannot read would be absurd. What is
     * **handed to the radio** is the sealed form when the other end can open it. The two differ only
     * in transit, which is exactly the property being claimed.
     *
     * Sealing is attempted per message rather than per conversation, because whether it is possible
     * can change between one message and the next: the other end's keys may have arrived in between.
     */
    private fun sendOneText(address: String, body: String, isDefaultApp: Boolean): Long? {
        val threadId = store.threadFor(address) ?: return null
        val at = System.currentTimeMillis()

        // Offer our keys if we have never spoken. Silent, rate-limited to once a day per contact,
        // and a no-op when the setting is off — see KeyExchange, which owns all of those rules.
        KeyExchange.announce(app, address)

        val wire = sealing.seal(address, body) ?: body

        val stored: Uri? = if (isDefaultApp) store.storeSent(address, body, at) else null
        if (stored == null) outbox.add(threadId, OutboxEntry(address = address, body = body, at = at))

        val manager = smsManager() ?: return null
        val sentIntent = textResultIntent(threadId, body, stored)
        val handed = runCatching {
            val parts = manager.divideMessage(wire)
            if (parts.size <= 1) {
                manager.sendTextMessage(address, null, wire, sentIntent, null)
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
            if (stored != null) store.markFailed(stored) else outbox.markFailed(threadId, body)
            return null
        }
        return threadId
    }

    // -------------------------------------------------------------------------------------
    // Pictures
    // -------------------------------------------------------------------------------------

    /**
     * A picture message.
     *
     * Four steps, in this order and for a reason each: work out the budget, squeeze the pictures
     * into it, write the message to the outbox so the thread shows it, and only then hand it to the
     * radio. Asking the radio first would mean a send in flight with nothing on screen and nothing
     * to mark when it fails.
     */
    private fun picture(
        addresses: List<String>,
        body: String,
        attachments: List<Uri>,
        subject: String?,
        prefs: MessagePrefs,
        subscriptionId: Int
    ): Outcome {
        val threadId = mms.threadFor(addresses.toSet()) ?: return Outcome.Refused("That thread could not be opened.")

        val textPart = body.takeIf { it.isNotBlank() }?.let { MmsImages.textPart(it) }
        val budget = MmsBudget.plan(
            maxBytes = transport.maxMessageBytes(subscriptionId),
            textBytes = textPart?.data?.size ?: 0,
            attachments = attachments.size
        )
        if (attachments.isNotEmpty() && budget == null) {
            return Outcome.Refused(
                "That will not fit in a picture message, even shrunk. Try sending fewer at once."
            )
        }

        val pictures = attachments.mapIndexedNotNull { index, uri ->
            MmsImages.attach(app, uri, budget ?: 0, index)
        }
        if (pictures.size != attachments.size) {
            return Outcome.Refused("One of those could not be prepared for sending.")
        }

        val content = listOfNotNull(textPart) + pictures
        if (content.isEmpty()) return Outcome.Refused(SendRefusal.EMPTY.reason)

        val parts: List<MmsPart> = content + PduEncoder.smil(content)
        val transactionId = PduEncoder.newTransactionId()

        val message = MmsMessage(
            type = MmsHeaders.TYPE_SEND_REQ,
            to = addresses,
            subject = subject?.takeIf { it.isNotBlank() },
            transactionId = transactionId,
            date = System.currentTimeMillis(),
            parts = parts
        )

        val row = mms.storeOutgoing(message, threadId, subscriptionId)

        val pdu = runCatching {
            PduEncoder.sendReq(
                to = addresses,
                parts = parts,
                subject = message.subject,
                transactionId = transactionId,
                deliveryReport = prefs.deliveryReports,
                date = message.date
            )
        }.getOrNull() ?: return Outcome.Refused("That message could not be built.")

        val handed = transport.send(pdu = pdu, storedRow = row, threadId = threadId, subscriptionId = subscriptionId)
        if (!handed) {
            row?.let { mms.markSent(it, sent = false) }
            return Outcome.Refused("That picture message could not be handed to the network.")
        }
        return Outcome.Sent(threadId)
    }

    /** Fetch a picture message that was announced and not downloaded. */
    fun download(mmsKey: Long): Boolean {
        val messageId = MmsStore.messageIdOf(mmsKey) ?: return false
        val pending = mms.pendingLocation(messageId) ?: return false
        val location = pending.contentLocation?.takeIf { it.isNotBlank() } ?: return false
        return transport.download(
            contentLocation = location,
            transactionId = pending.transactionId,
            pendingRowId = messageId
        )
    }

    // -------------------------------------------------------------------------------------

    private fun smsManager(): SmsManager? = runCatching {
        app.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
    }.getOrNull()

    private fun textResultIntent(threadId: Long, body: String, stored: Uri?): PendingIntent {
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
 * What the radio says about a **text** afterwards.
 *
 * Declared in the manifest rather than registered from a screen: a send is not finished when the
 * call returns, it is finished when the radio reports, which can be seconds later — long enough for
 * somebody to have left the app. A receiver that only existed while a thread was on screen would
 * miss exactly the failures worth recording.
 *
 * Picture messages report to their own receiver
 * ([com.utilities.app.messages.mms.MmsSentReceiver]), because what has to be updated is a row in a
 * different table.
 *
 * A success is ignored: the row was written as sent when it was handed over, which is what it is.
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
