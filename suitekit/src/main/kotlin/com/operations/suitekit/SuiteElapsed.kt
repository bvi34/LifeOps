package com.operations.suitekit

/**
 * How the suite says "how long" — one wording, so a gap of three hours and twenty minutes reads the
 * same whether it is a dose window counting down, a fever counting up, or a picker confirming which
 * afternoon you just chose.
 */
object SuiteElapsed {

    /**
     * "3h 20m" spacing, rounded up to the minute. Under a minute reads as "less than a minute"
     * rather than "0m", because a countdown that sits on zero looks broken.
     */
    fun formatDuration(millis: Long): String {
        if (millis <= 0) return "now"
        val totalMinutes = (millis + 59_999) / 60_000
        if (totalMinutes < 1) return "less than a minute"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L -> "${minutes}m"
            minutes == 0L -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    /** The same gap, spoken about the past: "3h 20m ago". */
    fun formatAgo(millis: Long): String =
        if (millis < 60_000) "just now" else "${formatDuration(millis)} ago"
}
