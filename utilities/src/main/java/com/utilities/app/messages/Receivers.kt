package com.utilities.app.messages

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsMessage

/**
 * The four components that make an app eligible to *be* the messenger.
 *
 * Android does not let an app volunteer for the default-SMS role by asking. It checks the manifest
 * for a specific set — a receiver for delivered texts, a receiver for the WAP push that carries
 * picture messages, a service that can reply to a call, and an activity that answers `sms:` links —
 * and an app missing any one of them is not offered in the role picker at all. Three of the four
 * are here; the activity is the app's own (see `MainActivity`).
 *
 * That "all four or nothing" rule is why this file contains something it would otherwise not: a
 * WAP push receiver that cannot yet do its job. See [MmsDeliverReceiver], which is the most
 * important comment in this module.
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
 * A picture message arrived, and this app cannot fetch it.
 *
 * ## Read this before making Utilities the default
 *
 * MMS is not a bigger SMS. The text arrives over the radio; a picture message arrives as a *pointer*
 * — a WAP push telling the phone there is something waiting at a URL on the carrier's own MMSC,
 * reachable only over the carrier's data channel — and fetching it means parsing a binary PDU for
 * that URL, handing it to `SmsManager.downloadMultimediaMessage`, parsing the reply PDU into parts,
 * and writing those parts into a second provider. That is a subsystem, and it is not written.
 *
 * Which leaves this receiver with one honest job: **record that it happened**, so the household
 * sees that something arrived and was not fetched, rather than never learning there was a
 * photograph. It does not pretend to have handled it and it does not drop it silently.
 *
 * The shelf says all of this in a sentence before the role picker opens — see
 * [com.utilities.app.shelf.Takeovers.mmsWarning] — and switching the role back to the carrier's
 * app makes picture messages work again immediately, because nothing was moved or deleted.
 */
class MmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        if (intent?.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        MessageNotifier(app).pictureMissed()
    }
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
            MessageSender(this).send(address = address, body = body, isDefaultApp = MessagesRole.isDefault(this))
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
