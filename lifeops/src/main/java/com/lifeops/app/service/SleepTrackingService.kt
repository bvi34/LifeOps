package com.lifeops.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lifeops.app.MainActivity
import com.lifeops.app.receiver.PhoneActivityReceiver

/**
 * Keeps a live process so [PhoneActivityReceiver] can hear screen on/off broadcasts overnight — the
 * system only delivers those to a runtime-registered receiver, which needs a running component. A
 * lightweight foreground service is the supported way to stay resident; it shows a low-importance,
 * ongoing notification ("Sleep tracking active") and registers the receiver for the whole activity
 * stream (screen + charging). Sticky so the OS restarts it after being killed.
 *
 * Started from [com.lifeops.app.LifeOpsApp] on launch and from [com.lifeops.app.receiver.SleepBootReceiver]
 * after a reboot, both gated on the user's tracking preference. Stopping it (via [stop]) tears the
 * receiver down and drops the notification.
 */
class SleepTrackingService : Service() {

    private var receiver: PhoneActivityReceiver? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        registerActivityReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground on restart; registration already happened in onCreate.
        startInForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerActivityReceiver() {
        if (receiver != null) return
        val r = PhoneActivityReceiver()
        val filter = IntentFilter().apply { PhoneActivityReceiver.ACTIONS.forEach { addAction(it) } }
        // Not exported: these are protected system broadcasts delivered to us regardless.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, filter)
        }
        receiver = r
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sleep tracking", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Keeps sleep tracking running in the background." }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("Sleep tracking active")
            .setContentText("Recording screen & charging activity to estimate your sleep.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        // The specialUse FGS type (and its matching permission) only exist on API 34+; on older
        // versions a plain foreground service with no declared type is correct.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    companion object {
        private const val CHANNEL_ID = "lifeops_sleep_tracking"
        private const val NOTIF_ID = 88100

        /** Start the tracking service (foreground). Safe to call repeatedly. */
        fun start(context: Context) {
            val intent = Intent(context, SleepTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop tracking: tears down the receiver and clears the notification. */
        fun stop(context: Context) {
            context.stopService(Intent(context, SleepTrackingService::class.java))
        }
    }
}
