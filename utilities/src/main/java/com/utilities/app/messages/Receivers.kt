package com.utilities.app.messages

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsMessage
import com.utilities.app.messages.mms.MmsStore
import com.utilities.app.messages.mms.MmsTransport
import com.utilities.app.messages.pdu.MmsHeaders
import com.utilities.app.messages.pdu.PduDecoder
import com.utilities.app.messages.pdu.PduEncoder

/**
 * The four components that make an app eligible to *be* the messenger.
 *
 * Android does not let an app volunteer for the default-SMS role by asking. It checks the manifest
 * for a specific set — a receiver for delivered texts, a receiver for the WAP push that carries
 * picture messages, a service that can reply to a call, and an activity that answers `sms:` links —
 * and an app missing any one of them is not offered in the role picker at all. Three of the four
 * are here; the activity is the app's own (see `MainActivity`).
 *
 * All four do their job. The WAP push receiver is the one with a subsystem behind it — see
 * [MmsDeliverReceiver] and the `mms/` package.
 */

/**
 * A text arrived, and this app is the default, so **nothing else is going to store it**.
 *
 * That is the part worth stating plainly, because it is the difference between the two rungs of the
 * takeover. An ordinary `SMS_RECEIVED` receiver is a spectator: the message is stored either way.
 * `SMS_DELIVER` is sent to exactly one app, the default one, and if that app does not write the
 * message into the provider then no app on the phone has it and the text is gone.
 *
 * So this does the minimum, in order, with nothing clever in between: reassemble the parts, write
 * it down, then notify. Notifying first would mean a notification that opens a thread the message
 * is not in yet.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        if (intent?.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val parts: Array<SmsMessage> = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        }.getOrNull() ?: return
        if (parts.isEmpty()) return

        // A long text arrives as several PDUs that are one message. The body is the concatenation
        // in the order the radio handed them over; the sender and the timestamp come from the first.
        val address = parts.first().displayOriginatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val at = parts.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (body.isEmpty()) return

        val store = MessageStore(app)
        store.storeIncoming(address, body, at)
        MessageNotifier(app).arrived(address = address, body = body, threadId = store.threadFor(address))
    }
}

/**
 * A picture message has been announced.
 *
 * ## What actually arrives
 *
 * Not the message. A WAP push carrying an `m-notification-ind`: a binary PDU with the sender, the
 * subject, the size, and — the field the whole feature stands on — a **URL on the carrier's own
 * MMSC** where the message is waiting. Fetching it needs a data connection to that server over the
 * right APN, which is the one part of MMS no app can do for itself and the one part the platform
 * will do: `SmsManager.downloadMultimediaMessage`. See [MmsTransport].
 *
 * Delivery reports and read receipts arrive down this same pipe. The decoder refuses to call one of
 * those a notification, which is how they are ignored rather than misread.
 *
 * ## Why a placeholder is written first
 *
 * Before anything is fetched, a row goes into the message store saying a picture message arrived,
 * from whom, and where it is waiting. Three things follow from that, and all three matter:
 *
 *  - a download that fails leaves something visible with a **button on it**, rather than nothing;
 *  - a household that has auto-download off gets the same thing on purpose;
 *  - the notification can say who it is from before a byte has been fetched.
 *
 * The placeholder is deleted by [MmsDownloadedReceiver] once the real message exists — in that
 * order, so there is never a moment with neither.
 */
class MmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        if (intent?.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        if (!MessagesRole.isDefault(app)) return

        val pdu = intent.getByteArrayExtra("data") ?: return
        val notification = PduDecoder.notification(pdu) ?: return

        val subscriptionId = intent.getIntExtra("subscription", -1)
        val sender = notification.from?.let(::stripPlmn)
        val store = MmsStore(app)

        // Every party to the message, which for a picture message may be several people.
        val parties = setOfNotNull(sender?.takeIf { it.isNotBlank() })
        val threadId = if (parties.isEmpty()) null else store.threadFor(parties)

        val placeholder = threadId?.let {
            store.storePending(
                threadId = it,
                from = sender,
                subject = notification.subject,
                contentLocation = notification.contentLocation,
                transactionId = notification.transactionId,
                size = notification.messageSize
            )
        }
        val placeholderId = placeholder?.let { runCatching { ContentUris.parseId(it) }.getOrNull() } ?: -1L

        MessageNotifier(app).arrived(
            address = sender ?: "",
            body = notification.subject?.takeIf { it.isNotBlank() } ?: "Sent a picture",
            threadId = threadId
        )

        if (!notification.fetchable) return

        val prefs = MessagePrefs(app)
        val transport = MmsTransport(app)
        val wanted = prefs.autoDownload &&
            transport.autoDownloadSensible(prefs.autoDownloadRoaming) &&
            // Advertising is the one class where an automatic fetch is somebody paying to receive
            // a leaflet. It is still announced, still fetchable, and still one tap away.
            !notification.isAdvertisement

        if (wanted) {
            transport.download(
                contentLocation = notification.contentLocation!!,
                transactionId = notification.transactionId,
                pendingRowId = placeholderId,
                subscriptionId = subscriptionId
            )
            return
        }

        // Declining is not the same as ignoring. Telling the network the message is deferred stops
        // it re-pushing the announcement, which the household would otherwise see four times.
        notification.transactionId?.let { transaction ->
            transport.respond(
                pdu = PduEncoder.notifyRespInd(transaction, MmsHeaders.STATUS_DEFERRED),
                seed = transaction,
                subscriptionId = subscriptionId
            )
        }
    }

    /**
     * A sender arrives as `+15550109999/TYPE=PLMN`.
     *
     * The suffix says the number is a phone number rather than an email address, which is a thing
     * the network needs and a thing nobody wants to read in a thread title.
     */
    private fun stripPlmn(address: String): String = address.substringBefore("/TYPE=").trim()
}

/**
 * "Reply with a message", from the call screen.
 *
 * The fourth component the role requires. The platform starts it with an `sms:` URI and the text
 * somebody picked from the list of canned replies, and expects the message to be sent without a
 * screen ever appearing — the person is on a call.
 *
 * It binds to nothing: `onBind` returning null is correct and is what every implementation of this
 * does. The work happens in `onStartCommand` and the service stops itself.
 */
class RespondViaMessageService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)?.toString()
        val address = intent?.data?.schemeSpecificPart?.substringBefore('?')?.trim()
        if (!body.isNullOrBlank() && !address.isNullOrBlank()) {
            MessageSender(this).send(addresses = listOf(address), body = body)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
