package com.utilities.app.messages

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.utilities.app.MainActivity
import com.utilities.app.messages.logic.Addresses

/**
 * Telling somebody a text arrived — which, once this app holds the role, nobody else will.
 *
 * ## Why there is a second channel
 *
 * Two kinds of notification, and they want different settings on them. [CHANNEL_MESSAGES] is the
 * text itself, and should make a sound. [CHANNEL_MISSED] is the one this app should not have to
 * have: a picture message arrived and could not be fetched (see [MmsDeliverReceiver]). Putting them
 * on one channel would mean somebody who silenced the second silenced the first, and the second is
 * the one most likely to be silenced, because it is the one that cannot be acted on.
 *
 * ## Nothing is posted that the platform did not ask for
 *
 * A notification is only posted while this app holds the default-SMS role. On the reading rung the
 * carrier's app is still notifying, and a second notification for every text would be the most
 * obvious possible way for this app to make the phone worse.
 */
class MessageNotifier(private val context: Context) {

    /** A text arrived. */
    fun arrived(address: String, body: String, threadId: Long?) {
        if (!MessagesRole.isDefault(context)) return
        if (!allowed()) return
        ensureChannels()

        val title = MessageStore(context).displayName(address)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openThread(threadId))
            .build()

        // One notification per thread, so five texts from one person replace each other rather than
        // stacking into a column — and so opening the thread dismisses exactly the right one.
        runCatching {
            NotificationManagerCompat.from(context).notify(
                threadId?.let { (it % Int.MAX_VALUE).toInt() } ?: Addresses.key(address).hashCode(),
                notification
            )
        }
    }

    /** A picture message arrived and this app could not fetch it. See [MmsDeliverReceiver]. */
    fun pictureMissed() {
        if (!MessagesRole.isDefault(context)) return
        if (!allowed()) return
        ensureChannels()

        val notification = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("A picture message arrived")
            .setContentText("Utilities handles text only. Hand Messages back to fetch it.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Utilities is your default messaging app and handles text messages only, so " +
                        "this one was not downloaded. Switch the default back in system settings " +
                        "and it will arrive."
                )
            )
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openThread(null))
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(MISSED_ID, notification) }
    }

    /** Take a thread's notification down, because it is now on screen. */
    fun clear(threadId: Long) {
        runCatching { NotificationManagerCompat.from(context).cancel((threadId % Int.MAX_VALUE).toInt()) }
    }

    private fun openThread(threadId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MESSAGES)
            if (threadId != null) putExtra(MainActivity.EXTRA_THREAD, threadId)
        }
        return PendingIntent.getActivity(
            context,
            threadId?.toInt() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun allowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_MESSAGES) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Texts arriving while Utilities is your messaging app" }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_MISSED) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_MISSED, "Picture messages", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Picture messages Utilities could not fetch" }
            )
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "utilities_messages"
        const val CHANNEL_MISSED = "utilities_messages_missed"
        private const val MISSED_ID = 90_101
    }
}
