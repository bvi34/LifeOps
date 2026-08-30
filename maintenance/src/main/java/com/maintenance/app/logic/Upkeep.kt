package com.maintenance.app.logic

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * A standing job on an asset: the thing that comes round again.
 *
 * The two intervals are the point. Real service schedules read *"every 5,000 miles or 6 months,
 * whichever comes first"*, and an app that only understands one of those halves is an app you stop
 * trusting the first time the car sits all winter. Either may be null; both may be set.
 *
 * [lastDoneAt] and [lastDoneMeter] are what the clock is measured from, and they are written by
 * logging the work — there is no separate "reset" button, because a reset that isn't a record of
 * having done the thing is how a history ends up with holes in it.
 */
data class UpkeepPlan(
    val id: String,
    val assetId: String,
    val title: String,
    val notes: String? = null,
    val everyDays: Int? = null,
    val everyMeter: Long? = null,
    val lastDoneAt: Long? = null,
    val lastDoneMeter: Long? = null,
    val createdAt: Long = 0L,
    val active: Boolean = true
)

/** Where a due date stands. The order of the entries is the order things get attention in. */
enum class DueStatus {
    OVERDUE,
    DUE_SOON,
    SCHEDULED,
    /** A meter interval with nothing to measure from — it needs one logged service to start. */
    NEEDS_BASELINE,
    /** Paused by hand, or a plan with no interval at all: a checklist entry, not a schedule. */
    DORMANT;

    val isPressing: Boolean get() = this == OVERDUE || this == DUE_SOON
}

/**
 * What the app has worked out about one plan: when it falls due, on which leg, and the one line a
 * list row shows.
 *
 * [dueAt] is the *effective* due date — the earlier of the two legs — and is null when only a
 * meter interval is set and there is no rate to turn it into a date. That null is meaningful and is
 * shown as such ("due in 420 mi"), rather than being papered over with a guess.
 */
data class DueVerdict(
    val status: DueStatus,
    val dueAt: Long? = null,
    val dueMeter: Long? = null,
    val daysRemaining: Int? = null,
    val meterRemaining: Long? = null,
    /** True when the meter leg is the one that falls due first. */
    val byMeter: Boolean = false,
    val summary: String
)

object Upkeep {

    const val DAY_MILLIS = 86_400_000L

    /** How far ahead "soon" reaches. Two weeks is a weekend and the one after it. */
    const val SOON_DAYS = 14

    /** The last tenth of a meter interval counts as soon, when there is no rate to date it with. */
    private const val METER_SOON_FRACTION = 0.1

    /**
     * Grade one plan.
     *
     * The rules, in the order they matter:
     *
     * 1. **A plan with no interval is dormant.** "Repaint the shutters" with no "every" on it is a
     *    note, and a note that claims to be overdue trains you to ignore the ones that are.
     * 2. **Never done still starts the clock.** The anchor for the date leg is the last completion
     *    *or the day the plan was written*. Anything else means every plan you add is instantly
     *    overdue, which is the fastest way to make a due list meaningless on day one.
     * 3. **A meter leg needs a baseline.** Without a mileage at the last service there is nothing
     *    to add the interval to. Rather than guess from today's odometer — which would silently
     *    grant a free 5,000 miles — it says so, and one logged service fixes it forever.
     * 4. **Whichever comes first wins.** When both legs are live, the earlier one governs, and
     *    [DueVerdict.byMeter] says which it was, because "due in 300 miles" and "due in 3 weeks"
     *    are acted on differently.
     */
    fun evaluate(
        plan: UpkeepPlan,
        now: Long,
        meter: MeterState? = null,
        soonDays: Int = SOON_DAYS
    ): DueVerdict {
        if (!plan.active) return DueVerdict(DueStatus.DORMANT, summary = "Paused")
        if (plan.everyDays == null && plan.everyMeter == null) {
            return DueVerdict(DueStatus.DORMANT, summary = "No schedule — done when you say so")
        }

        // --- the date leg ---
        val dateDue = plan.everyDays?.let { days ->
            (plan.lastDoneAt ?: plan.createdAt) + days.toLong() * DAY_MILLIS
        }
        val daysLeft = dateDue?.let { ceil((it - now).toDouble() / DAY_MILLIS).toInt() }

        // --- the meter leg ---
        val meterDue = plan.everyMeter?.let { interval -> plan.lastDoneMeter?.let { it + interval } }
        val meterNow = meter?.current
        // A rate of zero is no rate: a meter that hasn't moved between two readings cannot date a
        // mileage interval, and dividing by it would put the due date at the end of time.
        val perDay = meter?.perDay?.takeIf { it > 0.0 }
        val meterLeft = if (meterDue != null && meterNow != null) meterDue - meterNow else null
        val meterDueAt = if (meterDue != null && meterNow != null && perDay != null) {
            now + ((meterDue - meterNow).toDouble() / perDay * DAY_MILLIS).roundToLong()
        } else {
            null
        }

        if (plan.everyMeter != null && plan.lastDoneMeter == null && plan.everyDays == null) {
            val unit = meter?.unit
            val interval = unit?.format(plan.everyMeter) ?: "${MeterUnit.group(plan.everyMeter)}"
            return DueVerdict(
                status = DueStatus.NEEDS_BASELINE,
                summary = "Every $interval — log one service to start the clock"
            )
        }

        // --- whichever comes first ---
        val meterFirst = when {
            dateDue == null -> true
            meterDueAt == null -> false
            else -> meterDueAt < dateDue
        }
        val effectiveDue = if (meterFirst) meterDueAt else dateDue
        val status = status(daysLeft, meterLeft, plan.everyMeter, meterDueAt, dateDue, now, soonDays)

        return DueVerdict(
            status = status,
            dueAt = effectiveDue,
            dueMeter = meterDue,
            daysRemaining = daysLeft,
            meterRemaining = meterLeft,
            byMeter = meterFirst && meterLeft != null,
            summary = summarise(status, daysLeft, meterLeft, meterFirst, meter?.unit)
        )
    }

    private fun status(
        daysLeft: Int?,
        meterLeft: Long?,
        everyMeter: Long?,
        meterDueAt: Long?,
        dateDue: Long?,
        now: Long,
        soonDays: Int
    ): DueStatus {
        val overdue = (daysLeft != null && daysLeft <= 0) ||
            (meterLeft != null && meterLeft <= 0L) ||
            (meterDueAt != null && meterDueAt <= now)
        if (overdue) return DueStatus.OVERDUE

        val dateSoon = daysLeft != null && daysLeft <= soonDays
        val meterSoonByDate = meterDueAt != null && meterDueAt - now <= soonDays.toLong() * DAY_MILLIS
        // No rate to date the meter with: fall back to "the last tenth of the interval", which is
        // the only thing distance alone can say about urgency.
        val meterSoonByDistance = meterDueAt == null && meterLeft != null && everyMeter != null &&
            meterLeft <= (everyMeter * METER_SOON_FRACTION).roundToLong()
        if (dateSoon || meterSoonByDate || meterSoonByDistance) return DueStatus.DUE_SOON

        return if (dateDue == null && meterLeft == null) DueStatus.NEEDS_BASELINE else DueStatus.SCHEDULED
    }

    private fun summarise(
        status: DueStatus,
        daysLeft: Int?,
        meterLeft: Long?,
        meterFirst: Boolean,
        unit: MeterUnit?
    ): String {
        if (meterFirst && meterLeft != null) {
            val written = unit?.format(kotlin.math.abs(meterLeft)) ?: MeterUnit.group(kotlin.math.abs(meterLeft))
            return if (meterLeft <= 0L) "Overdue by $written" else "Due in $written"
        }
        if (daysLeft == null) return "Scheduled"
        return when {
            daysLeft < 0 -> "Overdue by ${days(-daysLeft)}"
            daysLeft == 0 -> "Due today"
            else -> "Due in ${days(daysLeft)}"
        }.let { if (status == DueStatus.NEEDS_BASELINE) "$it — no baseline reading yet" else it }
    }

    private fun days(count: Int): String = when {
        count == 1 -> "1 day"
        count < 14 -> "$count days"
        count < 60 -> "${count / 7} weeks"
        else -> "${count / 30} months"
    }

    /**
     * The date a plan next falls due, for sorting — plans that cannot be dated sort after those
     * that can rather than to the top, since "no date" is not urgency.
     */
    fun sortKey(verdict: DueVerdict): Long = verdict.dueAt ?: Long.MAX_VALUE
}

/** Everything the due arithmetic needs to know about an asset's meter, in one small object. */
data class MeterState(
    val unit: MeterUnit,
    val current: Long?,
    val perDay: Double?
) {
    companion object {
        /** Read straight off an asset's readings; null for a kind that wears no meter. */
        fun from(unit: MeterUnit?, readings: List<MeterReading>): MeterState? {
            if (unit == null) return null
            return MeterState(unit, Meter.latest(readings)?.value, Meter.perDay(readings))
        }
    }
}
