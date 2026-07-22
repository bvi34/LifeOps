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
 * Fires one habit's own daily reminder at its configured device-local hour, then re-schedules its
 * next occurrence so the reminder keeps recurring without a periodic worker (same self-perpetuating
 * pattern as [WellnessCheckinWorker]). Each habit is queued under a unique work name keyed by its
 * counter id, so habits schedule, reschedule, and cancel independently. Tapping the notification
 * opens the app.
 */
class HabitReminderWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val counterId = inputData.getString(KEY_COUNTER_ID) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: "Habit"
        // -1 = the reminder was turned off between scheduling and firing; don't notify or re-queue.
        val hour = inputData.getInt(KEY_HOUR, -1)
        if (hour !in 0..23) return Result.success()

        val channelId = "lifeops_habits"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Habit Reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, counterId.hashCode(), openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Habit reminder")
            .setContentText("Time for \"$name\" — keep the streak going.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_BASE_ID + (counterId.hashCode() and 0xFFFF), notification)

        // Self-perpetuate: queue tomorrow's reminder for this same habit.
        schedule(context, counterId, name, hour)
        return Result.success()
    }

    companion object {
        private const val NOTIF_BASE_ID = 77000
        private const val WORK_PREFIX = "habit_reminder_"
        private const val TAG = "habit_reminder"
        private const val KEY_COUNTER_ID = "counter_id"
        private const val KEY_NAME = "name"
        private const val KEY_HOUR = "hour"

        private fun workName(counterId: String) = "$WORK_PREFIX$counterId"

        /** Enqueue the next occurrence of [counterId]'s reminder at [hour], replacing any pending one. */
        fun schedule(context: Context, counterId: String, name: String, hour: Int) {
            val request = OneTimeWorkRequestBuilder<HabitReminderWorker>()
                .setInitialDelay(millisUntilNext(hour), TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        KEY_COUNTER_ID to counterId,
                        KEY_NAME to name,
                        KEY_HOUR to hour
                    )
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(counterId), ExistingWorkPolicy.REPLACE, request)
        }

        /** Cancel [counterId]'s pending reminder (habit un-flagged, reminder cleared, or archived). */
        fun cancel(context: Context, counterId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(counterId))
        }

        /** Cancel every queued habit reminder (by shared tag), e.g. before a full re-sync. */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }

        private fun millisUntilNext(hour: Int): Long {
            val now = ZonedDateTime.now()
            var next = now.withHour(hour.coerceIn(0, 23)).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return next.toInstant().toEpochMilli() - System.currentTimeMillis()
        }
    }
}
