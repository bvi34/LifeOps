package com.lifeops.app.worker

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lifeops.app.MainActivity
import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.data.repository.BusyBlockReminderScheduler
import com.lifeops.app.receiver.BusyBlockReminderReceiver
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Production [BusyBlockReminderScheduler] backed by [AlarmManager] exact alarms — not WorkManager,
 * which defers long-delayed one-shots under Doze/app-standby and is killed outright by many OEM
 * battery managers, so a "fire at exactly HH:mm" reminder there silently never arrives. Exact alarms
 * wake the device at the wall-clock minute and are what the app's SCHEDULE_EXACT_ALARM permission is
 * for. A fired alarm re-arms the block's next weekly occurrence from [BusyBlockReminderReceiver].
 */
class AlarmBusyBlockReminderScheduler(
    private val context: Context
) : BusyBlockReminderScheduler {
    override fun schedule(block: BusyBlock) =
        BusyBlockAlarms.schedule(
            context, block.id, block.title,
            block.startMinutes, block.endMinutes, block.daysMask, block.specificDate
        )

    override fun cancel(blockId: String) = BusyBlockAlarms.cancel(context, blockId)
}

/**
 * Shared alarm plumbing, called from both the scheduler above and [BusyBlockReminderReceiver]. Kept
 * primitive-typed (no [BusyBlock]) so the receiver can re-arm straight from the fired intent's extras.
 */
object BusyBlockAlarms {
    const val ACTION_FIRE = "com.lifeops.app.action.BUSY_BLOCK_REMINDER"
    const val KEY_ID = "block_id"
    const val KEY_TITLE = "title"
    const val KEY_START = "start"
    const val KEY_END = "end"
    const val KEY_DAYS = "days"
    const val KEY_DATE = "date"

    private const val CHANNEL_ID = "lifeops_busy_blocks"
    private const val NOTIF_BASE_ID = 78000

    /**
     * Arm the block's next start-of-block alarm, replacing any pending one. When there's no future
     * occurrence (empty day mask, or a one-off whose date/time has passed) the pending alarm is
     * cancelled instead.
     */
    fun schedule(
        context: Context, id: String, title: String,
        startMinutes: Int, endMinutes: Int, daysMask: Int, specificDate: String?
    ) {
        val date = specificDate?.ifBlank { null }
        val triggerAt = nextTriggerAtMillis(startMinutes, daysMask, date)
        if (triggerAt == null) {
            cancel(context, id)
            return
        }
        val intent = fireIntent(context, id).apply {
            putExtra(KEY_ID, id)
            putExtra(KEY_TITLE, title)
            putExtra(KEY_START, startMinutes)
            putExtra(KEY_END, endMinutes)
            putExtra(KEY_DAYS, daysMask)
            putExtra(KEY_DATE, date ?: "")
        }
        val pi = PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // setExactAndAllowWhileIdle needs the exact-alarm permission on Android 12+; if the user
        // hasn't granted it, fall back to the inexact-but-still-delivered variant rather than crash.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** Cancel the block's pending alarm (deleted, toggled off, or moved to a person). */
    fun cancel(context: Context, id: String) {
        val pi = PendingIntent.getBroadcast(
            context, id.hashCode(), fireIntent(context, id),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }

    /** Called when an alarm fires: post the notification, then re-arm the next weekly occurrence. */
    fun onFire(context: Context, intent: Intent) {
        val id = intent.getStringExtra(KEY_ID) ?: return
        val title = intent.getStringExtra(KEY_TITLE) ?: "Busy time"
        val start = intent.getIntExtra(KEY_START, -1)
        val end = intent.getIntExtra(KEY_END, -1)
        val days = intent.getIntExtra(KEY_DAYS, 0)
        val date = intent.getStringExtra(KEY_DATE)?.ifBlank { null }

        notify(context, id, title, start, end)

        // Weekly blocks self-perpetuate onto their next matching day; one-offs are done.
        if (date == null && start in 0..1439) {
            schedule(context, id, title, start, end, days, null)
        }
    }

    private fun notify(context: Context, id: String, title: String, start: Int, end: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Schedule Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, id.hashCode(), openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val range = if (start in 0..1439 && end in 0..1440) " (${clock(start)}–${clock(end)})" else ""
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Starting now: $title")
            .setContentText("Your scheduled busy time is starting$range.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_BASE_ID + (id.hashCode() and 0xFFFF), notification)
    }

    // Explicit component intent (targets our receiver directly) with a per-block data Uri so distinct
    // blocks get distinct PendingIntents that filterEquals-match on cancel; extras aren't part of that
    // match, so cancel can rebuild it without them.
    private fun fireIntent(context: Context, id: String): Intent =
        Intent(context, BusyBlockReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            data = Uri.parse("lifeops://busyblock/$id")
        }

    private fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /**
     * Absolute epoch millis of the block's next start, or null if there's no future occurrence. A
     * one-off fires at its date+start when still ahead; a weekly block scans up to 7 days out for the
     * next day whose bit is set (bit 0 = Monday … bit 6 = Sunday, matching BusyBlocks).
     */
    private fun nextTriggerAtMillis(startMinutes: Int, daysMask: Int, specificDate: String?): Long? {
        if (startMinutes !in 0..1439) return null
        val now = ZonedDateTime.now()
        val h = startMinutes / 60
        val m = startMinutes % 60

        if (specificDate != null) {
            val date = runCatching { LocalDate.parse(specificDate) }.getOrNull() ?: return null
            val at = date.atStartOfDay(now.zone).withHour(h).withMinute(m)
            return if (at.isAfter(now)) at.toInstant().toEpochMilli() else null
        }

        if (daysMask == 0) return null
        for (offset in 0..7) {
            val candidate = now.plusDays(offset.toLong())
                .withHour(h).withMinute(m).withSecond(0).withNano(0)
            val bit = 1 shl (candidate.dayOfWeek.value - 1)
            if ((daysMask and bit) != 0 && candidate.isAfter(now)) {
                return candidate.toInstant().toEpochMilli()
            }
        }
        return null
    }
}
