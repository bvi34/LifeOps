package com.lifeops.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lifeops.app.MainActivity
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Fires a daytime wellness check-in notification at one of the configured slots (10:00 / 15:00 /
 * 21:00 device time) and then re-schedules its own next occurrence, so the three slots keep
 * recurring without a periodic worker. Tapping the notification opens the app, which surfaces the
 * check-in pop-up (a slot has come due). See [scheduleSlot] / [scheduleAll].
 */
class WellnessCheckinWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val hour = inputData.getInt("slot_hour", DEFAULT_SLOTS.first())

        val channelId = "lifeops_wellness"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Wellness Check-ins", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Wellness check-in")
            .setContentText("How's your energy and sensory load right now?")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_BASE_ID + hour, notification)

        // Self-perpetuate: queue the next occurrence of this same slot.
        scheduleSlot(context, hour)
        return Result.success()
    }

    companion object {
        val DEFAULT_SLOTS = listOf(10, 15, 21)
        private const val NOTIF_BASE_ID = 88000
        private const val WORK_PREFIX = "wellness_slot_"

        /** Enqueue every slot in [slots]; called once at app start (safe to call repeatedly). */
        fun scheduleAll(context: Context, slots: List<Int> = DEFAULT_SLOTS) {
            slots.forEach { scheduleSlot(context, it) }
        }

        /** Enqueue the next occurrence of the [hour] slot (device local), replacing any pending one. */
        fun scheduleSlot(context: Context, hour: Int) {
            val delay = millisUntilNext(hour)
            val request = OneTimeWorkRequestBuilder<WellnessCheckinWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("slot_hour" to hour))
                .addTag("wellness_checkin")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("$WORK_PREFIX$hour", ExistingWorkPolicy.REPLACE, request)
        }

        private fun millisUntilNext(hour: Int): Long {
            val now = ZonedDateTime.now()
            var next = now.withHour(hour.coerceIn(0, 23)).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return next.toInstant().toEpochMilli() - System.currentTimeMillis()
        }
    }
}
