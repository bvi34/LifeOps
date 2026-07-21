package com.lifeops.app.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Guesstimates when the phone was last put down before "now", from screen-time / usage events —
 * the raw material for the morning sleep prompt (sleep ≈ last phone use → wake). This needs the
 * special PACKAGE_USAGE_STATS access, which the user grants once in system Settings; when it isn't
 * granted, [estimate] reports [SleepEstimate.hasAccess] = false and the prompt falls back to a
 * hand-entered duration.
 */
object ScreenTimeEstimator {

    /** [lastUseMillis] is the epoch time the phone was last used before the query; null if unknown. */
    data class SleepEstimate(val lastUseMillis: Long?, val hasAccess: Boolean)

    /** Whether the app currently holds usage-access (PACKAGE_USAGE_STATS). */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            // unsafeCheckOpNoThrow is API 29+; fall back to the (deprecated) checkOpNoThrow below it.
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Find the last moment the phone was actively used before [now], by scanning the last
     * [lookbackHours] of usage events for the newest "put it down" signal — an app moving to the
     * background or the screen turning off. That timestamp is treated as the moment sleep began.
     */
    fun estimate(context: Context, now: Long = System.currentTimeMillis(), lookbackHours: Int = 16): SleepEstimate {
        if (!hasUsageAccess(context)) return SleepEstimate(null, hasAccess = false)
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val begin = now - lookbackHours * 60L * 60L * 1000L
            val events = usm.queryEvents(begin, now)
            val event = UsageEvents.Event()
            var lastUse = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_BACKGROUND,
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        if (event.timeStamp > lastUse) lastUse = event.timeStamp
                    }
                }
            }
            SleepEstimate(lastUse.takeIf { it > 0L }, hasAccess = true)
        } catch (_: Exception) {
            SleepEstimate(null, hasAccess = true)
        }
    }

    /**
     * Sleep minutes from a [lastUseMillis] to [now], clamped to a sane [0, 16h] band so a bad
     * signal (phone used minutes ago, or dead for days) can't produce a nonsense duration.
     */
    fun sleepMinutes(lastUseMillis: Long, now: Long = System.currentTimeMillis()): Int {
        val minutes = ((now - lastUseMillis) / 60000L).toInt()
        return minutes.coerceIn(0, 16 * 60)
    }
}
