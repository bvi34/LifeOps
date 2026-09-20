package com.utilities.app.messages.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import com.utilities.app.messages.pdu.MmsBudget
import com.utilities.app.messages.pdu.PduEncoder

/**
 * The network half, which is the platform's, driven from here.
 *
 * `SmsManager` does the part no app could do for itself: it knows the carrier's MMSC address, it
 * knows the APN to reach it on, and it will bring up a cellular data connection for that one
 * transfer while the phone is sitting on Wi-Fi. What it will not do is take or return bytes — both
 * calls hand a **`content://` URI** across a process boundary, which is why [MmsFileProvider]
 * exists and why the awkward part of this file is permissions rather than protocol.
 *
 * ## The grant, and giving it back
 *
 * The URI is granted to the two system packages that do the work, for one transfer, and revoked
 * when the transfer ends — in the result receiver, whether it succeeded or failed. A grant that is
 * never revoked is a picture message readable by the phone process indefinitely; a buffer that is
 * never deleted is that message sitting in the cache. Neither is a disaster and both are avoidable,
 * so both are avoided.
 *
 * ## Everything here is asynchronous
 *
 * Neither call blocks and neither reports its outcome by returning. The answer arrives as a
 * broadcast, seconds or minutes later, possibly after the app has been killed — so both results are
 * handled by **manifest** receivers ([MmsDownloadedReceiver], [MmsSentReceiver]) rather than by
 * anything holding a screen open.
 */
class MmsTransport(context: Context) {

    private val app = context.applicationContext

    /**
     * Fetch a picture message the network has announced.
     *
     * [contentLocation] came out of the notification PDU and is the carrier's own URL; it is passed
     * straight through to the platform, which is the only thing that can reach it. [pendingRowId]
     * is the placeholder row in the message store, carried through the result so the downloaded
     * message can replace it.
     *
     * Returns false when the handover itself failed — no SIM, no permission, a location the
     * platform will not parse. Everything after that arrives at [MmsDownloadedReceiver].
     */
    fun download(
        contentLocation: String,
        transactionId: String?,
        pendingRowId: Long,
        subscriptionId: Int = -1
    ): Boolean {
        MmsFileProvider.sweep(app)
        val name = MmsFileProvider.nameFor("in", contentLocation)
        val file = MmsFileProvider.file(app, name)
        if (!runCatching { file.parentFile?.mkdirs(); file.delete(); file.createNewFile() }.getOrDefault(false)) {
            return false
        }

        val uri = MmsFileProvider.uriFor(app, name)
        grant(uri, read = true, write = true)

        val result = Intent(ACTION_DOWNLOADED).apply {
            setPackage(app.packageName)
            putExtra(EXTRA_FILE, name)
            putExtra(EXTRA_URI, uri.toString())
            putExtra(EXTRA_TRANSACTION, transactionId)
            putExtra(EXTRA_ROW, pendingRowId)
            putExtra(EXTRA_SUBSCRIPTION, subscriptionId)
        }

        return runCatching {
            manager(subscriptionId).downloadMultimediaMessage(
                app,
                contentLocation,
                uri,
                null,
                pendingIntent(result, name.hashCode())
            )
            true
        }.getOrElse {
            revoke(uri)
            MmsFileProvider.delete(app, name)
            false
        }
    }

    /**
     * Hand a built message to the radio.
     *
     * [storedRow] is the outbox row this send belongs to, carried through so the result can move it
     * to sent or to failed. The PDU was built by [PduEncoder]; nothing about its contents is
     * decided here.
     */
    fun send(
        pdu: ByteArray,
        storedRow: Uri?,
        threadId: Long,
        subscriptionId: Int = -1
    ): Boolean {
        MmsFileProvider.sweep(app)
        val name = MmsFileProvider.nameFor("out", storedRow?.toString() ?: threadId.toString())
        val file = MmsFileProvider.file(app, name)
        if (!runCatching { file.parentFile?.mkdirs(); file.writeBytes(pdu); true }.getOrDefault(false)) {
            return false
        }

        val uri = MmsFileProvider.uriFor(app, name)
        grant(uri, read = true, write = false)

        val result = Intent(ACTION_SENT).apply {
            setPackage(app.packageName)
            putExtra(EXTRA_FILE, name)
            putExtra(EXTRA_URI, uri.toString())
            putExtra(EXTRA_ROW_URI, storedRow?.toString())
            putExtra(EXTRA_THREAD, threadId)
        }

        return runCatching {
            manager(subscriptionId).sendMultimediaMessage(
                app,
                uri,
                null,
                null,
                pendingIntent(result, name.hashCode())
            )
            true
        }.getOrElse {
            revoke(uri)
            MmsFileProvider.delete(app, name)
            false
        }
    }

    /**
     * Answer a notification without fetching what it announced.
     *
     * The PDU is an `m-notifyresp-ind` saying *deferred*, and sending it is the difference between
     * "I will fetch this later" and silence — a network that hears nothing re-pushes the
     * notification, so declining quietly costs the household the same announcement four times.
     *
     * It goes out through the same call a message does, with no location and nothing stored against
     * it, which is how the platform expects a bare response to be sent.
     */
    fun respond(pdu: ByteArray, seed: String, subscriptionId: Int = -1): Boolean {
        val name = MmsFileProvider.nameFor("resp", seed)
        val file = MmsFileProvider.file(app, name)
        if (!runCatching { file.parentFile?.mkdirs(); file.writeBytes(pdu); true }.getOrDefault(false)) {
            return false
        }
        val uri = MmsFileProvider.uriFor(app, name)
        grant(uri, read = true, write = false)

        val result = Intent(ACTION_SENT).apply {
            setPackage(app.packageName)
            putExtra(EXTRA_FILE, name)
            putExtra(EXTRA_URI, uri.toString())
            putExtra(EXTRA_THREAD, -1L)
        }
        return runCatching {
            manager(subscriptionId).sendMultimediaMessage(app, uri, null, null, pendingIntent(result, name.hashCode()))
            true
        }.getOrElse {
            revoke(uri)
            MmsFileProvider.delete(app, name)
            false
        }
    }

    /**
     * The largest message this carrier will carry.
     *
     * Asked of the platform rather than assumed, and then clamped — a config that says 0, or one
     * that claims ten megabytes, is a config nobody filled in. See [MmsBudget.clampCarrierMax].
     */
    fun maxMessageBytes(subscriptionId: Int = -1): Int = runCatching {
        val config = manager(subscriptionId).carrierConfigValues
        MmsBudget.clampCarrierMax(config.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0))
    }.getOrDefault(MmsBudget.DEFAULT_MAX_BYTES)

    /**
     * Whether a picture message could be fetched right now without it costing anything unexpected.
     *
     * Roaming is the case this exists for. An auto-download while roaming is a charge nobody asked
     * for and cannot see coming, so a message that arrives then is stored as a placeholder and
     * fetched when somebody taps it.
     */
    fun autoDownloadSensible(roamingAllowed: Boolean): Boolean {
        if (roamingAllowed) return true
        return !runCatching {
            val telephony = app.getSystemService(android.telephony.TelephonyManager::class.java)
            telephony?.isNetworkRoaming == true
        }.getOrDefault(false)
    }

    private fun manager(subscriptionId: Int): SmsManager {
        val base = app.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        return if (subscriptionId >= 0) runCatching { base.createForSubscriptionId(subscriptionId) }.getOrDefault(base)
        else base
    }

    private fun pendingIntent(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Hand the URI to the packages that carry out the transfer, and to nobody else. */
    private fun grant(uri: Uri, read: Boolean, write: Boolean) {
        var flags = 0
        if (read) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (write) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        SYSTEM_PACKAGES.forEach { target ->
            runCatching { app.grantUriPermission(target, uri, flags) }
        }
    }

    companion object {

        const val ACTION_DOWNLOADED = "com.utilities.app.messages.MMS_DOWNLOADED"
        const val ACTION_SENT = "com.utilities.app.messages.MMS_SENT"

        const val EXTRA_FILE = "file"
        const val EXTRA_URI = "uri"
        const val EXTRA_TRANSACTION = "transaction"
        const val EXTRA_ROW = "row"
        const val EXTRA_ROW_URI = "rowUri"
        const val EXTRA_THREAD = "thread"
        const val EXTRA_SUBSCRIPTION = "subscription"

        /**
         * The two packages that actually carry out an MMS transfer.
         *
         * `com.android.phone` hosts the service on most builds and `com.android.mms.service` on
         * some; granting to both costs nothing and is the difference between a feature that works
         * on one manufacturer's phone and one that works on all of them.
         */
        private val SYSTEM_PACKAGES = listOf("com.android.phone", "com.android.mms.service")

        /** Take the grant back. Called from the result receivers, on both paths. */
        fun revoke(context: Context, uri: Uri) {
            runCatching {
                context.applicationContext.revokeUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
    }

    private fun revoke(uri: Uri) = revoke(app, uri)
}
