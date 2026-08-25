package com.health.app.logic

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * The rules a medicine is given under. All three limits are optional because real bottles differ:
 * some say "every 4–6 hours", some add "no more than 4 doses in 24 hours", some cap the total
 * amount instead. Whatever the label says, that's what goes here.
 */
data class MedicationRule(
    val name: String,
    val doseAmount: Double? = null,
    val doseUnit: String = "",
    val minIntervalHours: Double? = null,
    val maxDosesPer24h: Int? = null,
    val maxAmountPer24h: Double? = null
)

/** One dose actually given. */
data class DoseRecord(val takenAtMillis: Long, val amount: Double = 0.0)

/** Whether the next dose can be given now, and if not, why not. */
enum class DoseStatus { READY, WAIT, LIMIT_REACHED }

/**
 * The answer to the only question anyone asks at 3am: *can I give it yet?*
 *
 * [nextAllowedAtMillis] is null when the answer is yes. [dosesInWindow] / [amountInWindow] describe
 * the trailing 24 hours, which is the window every "max per day" limit is really written against —
 * midnight has nothing to do with it, and a day boundary is exactly where a naive counter lets a
 * fifth dose through.
 */
data class DoseWindow(
    val status: DoseStatus,
    val nextAllowedAtMillis: Long?,
    val lastDoseAtMillis: Long?,
    val dosesInWindow: Int,
    val amountInWindow: Double,
    val dosesRemaining: Int?,
    val reason: String
) {
    val isReady: Boolean get() = status == DoseStatus.READY

    /** Milliseconds until the next dose is allowed; 0 when it already is. */
    fun waitMillis(nowMillis: Long): Long = max(0L, (nextAllowedAtMillis ?: nowMillis) - nowMillis)
}

/**
 * Dose timing, framework-free.
 *
 * Two independent gates, and the later one wins:
 *  - the **interval** gate — last dose plus the label's minimum spacing;
 *  - the **rolling 24-hour** gate — when the day's allowance is spent, nothing is allowed until the
 *    oldest dose in the window ages out of it.
 *
 * A rule with no limits at all is always [DoseStatus.READY]; Health does not invent restrictions the
 * user didn't write down.
 */
object DoseSchedule {

    const val WINDOW_MS: Long = 24L * 60 * 60 * 1000

    fun evaluate(rule: MedicationRule, history: List<DoseRecord>, nowMillis: Long): DoseWindow {
        // Only doses already given count; a future-dated row is a data-entry slip, not a dose.
        val given = history.filter { it.takenAtMillis <= nowMillis }.sortedBy { it.takenAtMillis }
        val inWindow = given.filter { nowMillis - it.takenAtMillis < WINDOW_MS }
        val last = given.lastOrNull()
        val amountInWindow = inWindow.sumOf { it.amount }
        val dosesRemaining = rule.maxDosesPer24h?.let { max(0, it - inWindow.size) }

        // Gate 1: the label's spacing.
        val intervalReleaseAt = rule.minIntervalHours
            ?.takeIf { it > 0 }
            ?.let { hours -> last?.let { it.takenAtMillis + (hours * 60 * 60 * 1000).roundToLong() } }

        // Gate 2: the rolling allowance. When it's spent, the release time is when the dose that
        // filled it leaves the window — for a count limit that's the Nth-from-last dose, for an
        // amount limit it's however many oldest doses must age out to make room for the next one.
        var limitReleaseAt: Long? = null
        var limitReason: String? = null

        val maxDoses = rule.maxDosesPer24h
        if (maxDoses != null && inWindow.size >= maxDoses) {
            val freeing = inWindow[inWindow.size - maxDoses]
            limitReleaseAt = freeing.takenAtMillis + WINDOW_MS
            limitReason = "${inWindow.size} of $maxDoses doses given in the last 24 hours."
        }

        val maxAmount = rule.maxAmountPer24h
        val nextAmount = rule.doseAmount ?: 0.0
        if (maxAmount != null && amountInWindow + nextAmount > maxAmount + AMOUNT_EPSILON) {
            // Age out oldest doses until one more dose would fit under the cap.
            var remaining = amountInWindow
            var releaseAt: Long? = null
            for (dose in inWindow) {
                remaining -= dose.amount
                if (remaining + nextAmount <= maxAmount + AMOUNT_EPSILON) {
                    releaseAt = dose.takenAtMillis + WINDOW_MS
                    break
                }
            }
            // Nothing frees enough (a single dose already exceeds the cap): the whole window must clear.
            val amountReleaseAt = releaseAt ?: (inWindow.lastOrNull()?.takenAtMillis?.plus(WINDOW_MS) ?: nowMillis)
            if (limitReleaseAt == null || amountReleaseAt > limitReleaseAt) {
                limitReleaseAt = amountReleaseAt
                limitReason = "${trim(amountInWindow)} ${rule.doseUnit} given in the last 24 hours " +
                    "(daily limit ${trim(maxAmount)} ${rule.doseUnit})."
            }
        }

        val blockedByLimit = limitReleaseAt != null && limitReleaseAt > nowMillis

        val nextAllowedAt = listOfNotNull(
            intervalReleaseAt?.takeIf { it > nowMillis },
            limitReleaseAt?.takeIf { it > nowMillis }
        ).maxOrNull()

        return when {
            nextAllowedAt == null -> DoseWindow(
                status = DoseStatus.READY,
                nextAllowedAtMillis = null,
                lastDoseAtMillis = last?.takenAtMillis,
                dosesInWindow = inWindow.size,
                amountInWindow = amountInWindow,
                dosesRemaining = dosesRemaining,
                reason = if (last == null) "No dose recorded yet." else "Due now."
            )
            // A spent allowance is a different fact from "not yet" — it is the one the label warns
            // about, so it names the status even when the interval also hasn't elapsed.
            blockedByLimit -> DoseWindow(
                status = DoseStatus.LIMIT_REACHED,
                nextAllowedAtMillis = nextAllowedAt,
                lastDoseAtMillis = last?.takenAtMillis,
                dosesInWindow = inWindow.size,
                amountInWindow = amountInWindow,
                dosesRemaining = dosesRemaining,
                reason = limitReason ?: "Daily limit reached."
            )
            else -> DoseWindow(
                status = DoseStatus.WAIT,
                nextAllowedAtMillis = nextAllowedAt,
                lastDoseAtMillis = last?.takenAtMillis,
                dosesInWindow = inWindow.size,
                amountInWindow = amountInWindow,
                dosesRemaining = dosesRemaining,
                reason = "Next dose in ${formatDuration(nextAllowedAt - nowMillis)}."
            )
        }
    }

    /**
     * "in 3h 20m" style spacing, rounded to the minute. Under a minute reads as "less than a minute"
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

    /** "since" spacing for a past instant: how long ago a dose was given. */
    fun formatAgo(millis: Long): String = if (millis < 60_000) "just now" else "${formatDuration(millis)} ago"

    private const val AMOUNT_EPSILON = 1e-9

    private fun trim(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else Temperature.round1(value).toString()
}
