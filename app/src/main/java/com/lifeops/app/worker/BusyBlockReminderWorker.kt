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
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Fires one busy block's start-of-block reminder, then — for a weekly-recurring block — re-schedules
 * its next occurrence so the reminder keeps recurring without a periodic worker (same
 * self-perpetuating pattern as [HabitReminderWorker]). A one-off block (with a specificDate) fires
 * exactly once and does not re-queue. Each block is queued under a unique work name keyed by its id,
 * so blocks schedule, reschedule, and cancel independently. Tapping the notification opens the app.
 */
class BusyBlockReminderWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val blockId = inputData.getString(KEY_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Busy time"
        val startMinutes = inputData.getInt(KEY_START, -1)
        val endMinutes = inputData.getInt(KEY_END, -1)
        val daysMask = inputData.getInt(KEY_DAYS, 0)
        val specificDate = inputData.getString(KEY_DATE)?.ifBlank { null }

        val channelId = "lifeops_busy_blocks"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Schedule Reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, blockId.hashCode(), openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val range = if (startMinutes in 0..1439 && endMinutes in 0..1440)
            " (${clock(startMinutes)}–${clock(endMinutes)})" else ""
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Starting now: $title")
            .setContentText("Your scheduled busy time is starting$range.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_BASE_ID + (blockId.hashCode() and 0xFFFF), notification)

        // Weekly blocks self-perpetuate onto their next matching day; one-offs are done.
        if (specificDate == null && startMinutes in 0..1439) {
            enqueue(context, blockId, title, startMinutes, endMinutes, daysMask, null)
        }
        return Result.success()
    }

    companion object {
        private const val NOTIF_BASE_ID = 78000
        private const val WORK_PREFIX = "busy_block_reminder_"
        private const val TAG = "busy_block_reminder"
        private const val KEY_ID = "block_id"
        private const val KEY_TITLE = "title"
        private const val KEY_START = "start"
        private const val KEY_END = "end"
        private const val KEY_DAYS = "days"
        private const val KEY_DATE = "date"

        private fun workName(blockId: String) = "$WORK_PREFIX$blockId"

        /**
         * Enqueue the block's next start-of-block reminder, replacing any pending one. When there's
         * no future occurrence (an empty day mask, or a one-off whose date/time has passed) the
         * pending reminder is simply cancelled instead.
         */
        fun schedule(
            context: Context,
            blockId: String,
            title: String,
            startMinutes: Int,
            endMinutes: Int,
            daysMask: Int,
            specificDate: String?
        ) {
            val delay = millisUntilNext(startMinutes, daysMask, specificDate)
            if (delay == null) {
                cancel(context, blockId)
                return
            }
            val request = OneTimeWorkRequestBuilder<BusyBlockReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        KEY_ID to blockId,
                        KEY_TITLE to title,
                        KEY_START to startMinutes,
                        KEY_END to endMinutes,
                        KEY_DAYS to daysMask,
                        KEY_DATE to (specificDate ?: "")
                    )
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(blockId), ExistingWorkPolicy.REPLACE, request)
        }

        private fun enqueue(
            context: Context, blockId: String, title: String,
            startMinutes: Int, endMinutes: Int, daysMask: Int, specificDate: String?
        ) = schedule(context, blockId, title, startMinutes, endMinutes, daysMask, specificDate)

        /** Cancel the block's pending reminder (deleted, toggled off, or moved to a person). */
        fun cancel(context: Context, blockId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(blockId))
        }

        /** minutes-from-midnight → "HH:mm". */
        private fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

        /**
         * Millis until the block's next start, or null if there's no future occurrence. A one-off
         * fires at its date+start when still in the future; a weekly block scans up to 7 days ahead
         * for the next day whose bit is set (bit 0 = Monday … bit 6 = Sunday, matching BusyBlocks).
         */
        private fun millisUntilNext(startMinutes: Int, daysMask: Int, specificDate: String?): Long? {
            if (startMinutes !in 0..1439) return null
            val now = ZonedDateTime.now()
            val h = startMinutes / 60
            val m = startMinutes % 60

            if (specificDate != null) {
                val date = runCatching { LocalDate.parse(specificDate) }.getOrNull() ?: return null
                val at = date.atStartOfDay(now.zone).withHour(h).withMinute(m)
                return if (at.isAfter(now)) at.toInstant().toEpochMilli() - System.currentTimeMillis() else null
            }

            if (daysMask == 0) return null
            for (offset in 0..7) {
                val candidate = now.plusDays(offset.toLong())
                    .withHour(h).withMinute(m).withSecond(0).withNano(0)
                val bit = 1 shl (candidate.dayOfWeek.value - 1)
                if ((daysMask and bit) != 0 && candidate.isAfter(now)) {
                    return candidate.toInstant().toEpochMilli() - System.currentTimeMillis()
                }
            }
            return null
        }
    }
}
