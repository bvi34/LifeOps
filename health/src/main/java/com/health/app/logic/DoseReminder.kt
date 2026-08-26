package com.health.app.logic

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When to next nudge somebody about a medicine.
 *
 * Health nudges in exactly two ways, because households take medicines in exactly two ways:
 *
 *  - **At set times** ([ReminderMode.FIXED_TIMES]) — the regular ones. "The 8am and 8pm tablet."
 *    These want a clock, not a countdown: a blood-pressure tablet is taken at breakfast whether or
 *    not the last one was three hours late.
 *  - **When the next dose is allowed** ([ReminderMode.WHEN_DUE]) — the as-needed ones. Calpol at
 *    2am. Nobody wants a reminder at a fixed hour for those; they want to be told the moment the
 *    four hours are up, which is a fact `DoseSchedule` already computes.
 *
 * Both are computed here, framework-free and against an injected clock, so the awkward cases — a
 * reminder time that has already passed today, a dose window that closed while the phone was asleep,
 * a medicine that was paused — are provable on the JVM rather than discovered at 3am. Scheduling the
 * resulting instant with WorkManager is `reminder/MedicationReminderScheduler`'s job.
 *
 * Nothing here decides *whether* a dose should be taken. A reminder is a memory aid on a rule the
 * user typed in; the dose window still has the final word on screen.
 */

/** How a medicine's reminder is set, if at all. */
enum class ReminderMode(val key: String, val label: String) {
    /** No reminder. The default, and what everything already in the database becomes. */
    OFF("off", "No reminder"),

    /** One or more times of day, every day. */
    FIXED_TIMES("times", "At set times"),

    /** As soon as the label's spacing allows the next dose — and only while a dose is outstanding. */
    WHEN_DUE("when_due", "When the next dose is due");

    companion object {
        fun fromKey(key: String?): ReminderMode = entries.firstOrNull { it.key == key } ?: OFF
    }
}

object DoseReminder {

    /**
     * The longest Health will look ahead for a [ReminderMode.WHEN_DUE] reminder.
     *
     * A medicine that hasn't been given for a week has no outstanding dose to be reminded about —
     * the person stopped taking it, or never started. Firing a "your dose is due" notification into
     * that silence trains people to ignore the ones that matter, so the reminder simply lapses and
     * comes back the next time a dose is actually recorded.
     */
    const val WHEN_DUE_HORIZON_MS: Long = 24L * 60 * 60 * 1000

    /**
     * How long after a dose becomes due Health still bothers to fire, when the wake-up was late.
     *
     * WorkManager is not an alarm clock: a doze-deferred worker can run well after its target. A
     * reminder that arrives twenty minutes late is still useful; one that arrives six hours late is
     * a lie about the present, and the screen's live dose window is the better answer by then.
     */
    const val LATE_TOLERANCE_MS: Long = 60L * 60 * 1000

    /** The times of day offered when somebody first turns on a set-times reminder. */
    val SUGGESTED_TIMES: List<LocalTime> = listOf(
        LocalTime.of(8, 0),
        LocalTime.of(12, 0),
        LocalTime.of(18, 0),
        LocalTime.of(22, 0)
    )

    /**
     * The next instant to fire for a set-times reminder: the earliest of today's remaining times,
     * or the first time tomorrow once today's are all past.
     *
     * The zone is the device's, resolved at call time rather than stored, so "the 8am tablet" stays
     * the 8am tablet after a flight rather than becoming a 3am one.
     */
    fun nextFixedTime(
        times: List<LocalTime>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long? {
        if (times.isEmpty()) return null
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val sorted = times.distinct().sorted()
        val todayNext = sorted
            .map { now.with(it).withSecond(0).withNano(0) }
            .firstOrNull { it.toInstant().toEpochMilli() > nowMillis }
        val target = todayNext ?: now.plusDays(1).with(sorted.first()).withSecond(0).withNano(0)
        return target.toInstant().toEpochMilli()
    }

    /**
     * The next instant to fire for a when-due reminder, or null if there is nothing to wait for.
     *
     * Null happens in three ordinary ways, and all three mean "don't schedule anything":
     *  - no dose has been recorded, so nothing is outstanding;
     *  - the dose is already due, so the notification would be about the present, not the future
     *    (the caller fires now instead — see [shouldFireWhenDue]);
     *  - the wait runs past [WHEN_DUE_HORIZON_MS], which is a medicine nobody is currently taking.
     */
    fun nextWhenDue(window: DoseWindow, nowMillis: Long): Long? {
        if (window.lastDoseAtMillis == null) return null
        val allowedAt = window.nextAllowedAtMillis ?: return null
        if (allowedAt <= nowMillis) return null
        if (allowedAt - nowMillis > WHEN_DUE_HORIZON_MS) return null
        return allowedAt
    }

    /**
     * Whether a when-due reminder that has just woken up should actually notify.
     *
     * It should when the dose really is due now and the wake-up wasn't hopelessly late. A worker
     * that ran on time for a window that has since been re-armed by another dose — somebody gave it
     * early, from the other parent's phone — finds a window that is no longer ready and stays quiet.
     */
    fun shouldFireWhenDue(window: DoseWindow, nowMillis: Long, targetMillis: Long): Boolean =
        window.isReady && nowMillis - targetMillis <= LATE_TOLERANCE_MS

    /**
     * Parse the stored `"08:00,20:00"` form. Unparseable entries are dropped rather than defaulted:
     * a reminder at a time nobody chose is worse than one that doesn't fire.
     */
    fun parseTimes(csv: String?): List<LocalTime> =
        csv.orEmpty()
            .split(',')
            .mapNotNull { part -> parseTime(part) }
            .distinct()
            .sorted()

    /** The storage form — always `HH:mm`, always sorted, so two equal schedules compare equal. */
    fun formatTimes(times: List<LocalTime>): String =
        times.distinct().sorted().joinToString(",") { formatTime(it) }

    /** `"8:5"`, `"08:05"` and `" 8:05 "` all mean five past eight; anything else means nothing. */
    fun parseTime(raw: String?): LocalTime? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    fun formatTime(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)

    /** "08:00 and 20:00" — how a schedule reads on a card. */
    fun describeTimes(times: List<LocalTime>): String {
        val labels = times.distinct().sorted().map { formatTime(it) }
        return when (labels.size) {
            0 -> "No times set"
            1 -> labels.first()
            2 -> "${labels[0]} and ${labels[1]}"
            else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
        }
    }
}
