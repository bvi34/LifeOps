package com.utilities.app.messages.seal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import com.utilities.app.messages.MessagePrefs
import com.utilities.app.messages.MessagesRole
import com.utilities.app.messages.logic.Addresses

/**
 * How two installs of this app find out about each other, without a server.
 *
 * ## The channel
 *
 * A **binary SMS on a port**. Not a text with a marker in it, and the difference is the whole
 * design: a data SMS addressed to a port is never stored in the message database and is never shown
 * by any messaging app. If the person on the other end has Utilities, their copy hears it and
 * answers. If they do not, it is discarded by the platform and they see **nothing at all** — no
 * mysterious string of base64 from a friend, no entry in their thread, nothing to explain.
 *
 * That property is what makes automatic key exchange acceptable at all. The alternative — a visible
 * line of gibberish appended to a real message — would be this app making other people's phones
 * worse in order to make itself more secure.
 *
 * ## What it costs, and who is told
 *
 * One SMS segment per contact, once, charged to the household. It is silent but it is **not free**,
 * so it is a setting that says so ([MessagePrefs.announceKeys]) and is described in plain words on
 * the settings screen. Rate limiting is per contact and generous — see [SHOULD_RETRY_AFTER_MS] —
 * because the failure being avoided is a loop where two installs announce at each other forever.
 *
 * ## What is in it
 *
 * Two public keys and a version byte. No number, no name, no device identifier, nothing derived from
 * the SIM. The recipient already knows who sent it, because it arrived from their number.
 */
object KeyExchange {

    /**
     * The port.
     *
     * An arbitrary high number, chosen once and now unchangeable without orphaning every install
     * that already speaks it. There is no registry for these; collisions are possible and harmless,
     * because a payload that is not ours fails its version check and is dropped.
     */
    const val PORT = 17_763

    const val VERSION = 1

    /** A bundle offered without being asked. */
    const val KIND_ADVERT = 1

    /** A bundle sent because one arrived. Distinguished so an answer cannot trigger an answer. */
    const val KIND_REPLY = 2

    /** How long before an unanswered advert is worth repeating. A day: this is not a handshake. */
    const val SHOULD_RETRY_AFTER_MS = 24 * 60 * 60 * 1000L

    private const val PREFS = "utilities_seal_adverts"

    /** The payload: version, kind, identity key, prekey. */
    fun encode(kind: Int, bundle: PreKeyBundle): ByteArray =
        Bytes.concat(byteArrayOf(VERSION.toByte(), kind.toByte()), bundle.encode())

    fun decode(bytes: ByteArray): Advert? {
        if (bytes.size != 2 + PreKeyBundle.BYTES) return null
        if ((bytes[0].toInt() and 0xFF) != VERSION) return null
        val kind = bytes[1].toInt() and 0xFF
        if (kind != KIND_ADVERT && kind != KIND_REPLY) return null
        return runCatching {
            Advert(kind = kind, bundle = PreKeyBundle.decode(Bytes.slice(bytes, 2, PreKeyBundle.BYTES)))
        }.getOrNull()
    }

    data class Advert(val kind: Int, val bundle: PreKeyBundle)

    /**
     * Offer our keys to somebody, if it is worth doing.
     *
     * Returns false without sending when there is nothing to gain: the setting is off, we cannot
     * send, we already have their bundle, or we asked recently. The caller does not have to check
     * any of that — the point of putting it here is that every path into key exchange gets the same
     * rules.
     */
    fun announce(context: Context, address: String, kind: Int = KIND_ADVERT): Boolean {
        val app = context.applicationContext
        if (address.isBlank()) return false
        if (!MessagePrefs(app).announceKeys) return false
        if (!MessagesRole.canSend(app)) return false

        val store = SealStore.get(app)
        // An answer is always worth sending; an unprompted offer is not, if we already know them.
        if (kind == KIND_ADVERT && store.bundleFor(address) != null) return false
        if (kind == KIND_ADVERT && !dueFor(app, address)) return false

        val payload = encode(kind, store.identity().bundle())
        val sent = runCatching {
            val manager = app.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            manager.sendDataMessage(address, null, PORT.toShort(), payload, null, null)
            true
        }.getOrDefault(false)

        if (sent) recordAnnounced(app, address)
        return sent
    }

    /** Whether an unprompted offer to this contact is due. */
    fun dueFor(context: Context, address: String): Boolean {
        val last = prefs(context).getLong(Addresses.key(address), 0L)
        return System.currentTimeMillis() - last > SHOULD_RETRY_AFTER_MS
    }

    private fun recordAnnounced(context: Context, address: String) {
        prefs(context).edit().putLong(Addresses.key(address), System.currentTimeMillis()).apply()
    }

    /**
     * Named outside the prefix the backup contributor sweeps.
     *
     * It is a table of "when did we last text this person's phone invisibly", which is both useless
     * on a new phone and a slightly unpleasant thing to find in an archive.
     */
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Somebody's keys arriving.
 *
 * Registered in the manifest on [KeyExchange.PORT], which is the only way a data SMS is delivered at
 * all. Not exported in any meaningful sense — the filter is a port, and what reaches it is the
 * platform.
 *
 * Two things happen and the order matters: the bundle is stored, and *then* an answer is sent if one
 * is owed. Answering first would mean the other end could reply before we had anywhere to put their
 * keys.
 */
class KeyExchangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        if (intent?.action != Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION) return
        if (!MessagesRole.canRead(app)) return

        val messages: Array<SmsMessage> = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        }.getOrNull() ?: return
        if (messages.isEmpty()) return

        val address = messages.first().displayOriginatingAddress?.takeIf { it.isNotBlank() } ?: return
        val payload = messages.fold(ByteArray(0)) { acc, part -> Bytes.concat(acc, part.userData ?: ByteArray(0)) }
        val advert = KeyExchange.decode(payload) ?: return

        val store = SealStore.get(app)

        // A key that disagrees with one on file is either a reinstall or somebody in the middle, and
        // nothing here can tell them apart. It is refused, the thread says so, and starting again is
        // something a person does on purpose.
        if (store.identityChanged(address, advert.bundle)) return
        if (!store.rememberBundle(address, advert.bundle)) return

        // Answer an offer, never an answer — or two installs would talk to each other forever.
        if (advert.kind == KeyExchange.KIND_ADVERT) {
            KeyExchange.announce(app, address, KeyExchange.KIND_REPLY)
        }
    }
}
