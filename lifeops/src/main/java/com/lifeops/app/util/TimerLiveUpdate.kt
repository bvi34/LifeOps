package com.lifeops.app.util

import com.lifeops.app.data.repository.ActiveTimer
import com.lifeops.app.data.repository.TimerController

/**
 * What the running task timer looks like outside the app: in its notification (an Android 16 Live
 * Update, with a chip in the status bar) and on its Quick Settings tile. Pure, so the wording and the
 * clock arithmetic are unit-tested; the service and the tile only copy these fields onto the platform.
 */
data class TimerDisplay(
    /** The task's title, or a stand-in until it has been read. */
    val title: String,
    /** One line under the title. */
    val text: String,
    /**
     * The wall-clock instant the platform's chronometer counts from — the start for an open timer,
     * the end for a Pomodoro, which counts down to it. The status-bar chip shows this clock.
     */
    val chronometerBaseMillis: Long,
    val countsDown: Boolean,
    /** Minutes done of [progressMax] for a Pomodoro; null for an open-ended timer, which has no end. */
    val progress: Int?,
    val progressMax: Int,
    /** A few characters for small surfaces such as the tile's subtitle: "12 min", "13 min left". */
    val short: String
)

object TimerLiveUpdate {

    const val UNTITLED = "Task timer"

    fun display(timer: ActiveTimer, taskTitle: String?, elapsedSeconds: Int): TimerDisplay {
        val title = taskTitle?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED
        val elapsedMinutes = (elapsedSeconds.coerceAtLeast(0)) / 60
        return if (timer.isPomodoro) {
            val total = TimerController.POMODORO_SECONDS / 60
            val done = elapsedMinutes.coerceAtMost(total)
            TimerDisplay(
                title = title,
                text = "Pomodoro · $done of $total min",
                chronometerBaseMillis = timer.startMillis + TimerController.POMODORO_SECONDS * 1000L,
                countsDown = true,
                progress = done,
                progressMax = total,
                short = "${total - done} min left"
            )
        } else {
            TimerDisplay(
                title = title,
                text = "Timing · stop to log ${minutesWord(elapsedMinutes)}",
                chronometerBaseMillis = timer.startMillis,
                countsDown = false,
                progress = null,
                progressMax = 0,
                short = "$elapsedMinutes min"
            )
        }
    }

    private fun minutesWord(minutes: Int): String = when (minutes) {
        0 -> "the time"
        1 -> "1 minute"
        else -> "$minutes minutes"
    }
}
