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
    /**
     * Absolute meter milestones — "spark plugs at 100,000 miles" — ascending.
     *
     * This is the other half of how a manufacturer writes a schedule, and it is **not** the same as
     * [everyMeter]. A cadence is measured from the last time the job was done; a milestone is a
     * number on the odometer. On a car bought at 60,000 miles the difference is "next at 100,000"
     * versus "100,000 from now", which is four years of getting it wrong.
     *
     * When both are empty the plan has no meter leg. When this is set it *replaces* the cadence
     * rather than adding to it: a schedule that wants both says so by listing the milestones.
     */
    val atMeter: List<Long> = emptyList(),
    val lastDoneAt: Long? = null,
    val lastDoneMeter: Long? = null,
    val createdAt: Long = 0L,
    val active: Boolean = true,
    /**
     * What kind of thing this is — work to be done, or a prompt to read the meter.
     *
     * They look the same on a list and behave differently when satisfied: see [PlanKind].
     */
    val kind: PlanKind = PlanKind.UPKEEP,
    /**
     * The schedule pack and item this plan came from, when it wasn't typed by hand
     * (`"jeep-jl-36-a"` / `"spark-plugs"`). Applying a pack again matches on this pair, so a second
     * apply adds what's new and leaves everything you have already tuned exactly as it is.
     */
    val sourcePack: String? = null,
    val sourceItem: String? = null,
    /**
     * Whether this plan puts itself on the LifeOps week as a task, dated the day it falls due.
     *
     * On by default, because a schedule nobody is reminded of is a schedule nobody keeps — and
     * per-plan rather than app-wide, because "change the furnace filter" belongs on a week and
     * "check the roof after a storm" does not. See `logic/UpkeepTasks`.
     */
    val publishToLifeOps: Boolean = true
)

/**
 * Work, or a prompt.
 *
 * The distinction earns its place at exactly one moment: what "done" means. Finishing a job writes a
 * service record and restarts its clock. "Read the odometer" is not a job — there is nothing to
 * record and nothing to cost, and a service history full of weekly zero-pound entries called *Read
 * the odometer* would bury the eleven entries that matter. Nor is "check the recalls".
 *
 * A prompt also gets satisfied differently. A LifeOps task cannot carry a number, so ticking one off
 * over there cannot be what captures a reading. Instead the **thing itself satisfies the prompt** —
 * type the reading in, or run the check, and the task ticks itself off in LifeOps — and the task is
 * what nudges you to do that.
 */
enum class PlanKind(val key: String, val label: String) {
    UPKEEP("upkeep", "Upkeep"),
    METER_READING("meter_reading", "Meter reading"),

    /**
     * "Ask NHTSA what is open on this model" — the same shape as a meter prompt, for the same
     * reason.
     *
     * A recall list is the one thing on the docket that goes stale on its own: nothing about your
     * vehicle changes, and the answer does. Checking is a question this app can ask for you, so the
     * prompt exists to *make it happen on a cadence* rather than to be work you do — and running the
     * check is what satisfies it, the way typing a reading satisfies the odometer prompt.
     */
    RECALL_CHECK("recall_check", "Recall check");

    /**
     * Whether finishing this writes a service record and costs something.
     *
     * Only real upkeep does. A reading and a recall check are prompts: they move their own clock on
     * and leave the history alone, because a service history full of weekly £0 entries called *Read
     * the odometer* would bury the eleven entries that matter.
     */
    val isWork: Boolean get() = this == UPKEEP

    companion object {
        fun of(key: String?): PlanKind = entries.firstOrNull { it.key == key } ?: UPKEEP
    }
}

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
        if (plan.everyDays == null && plan.everyMeter == null && plan.atMeter.isEmpty()) {
            return DueVerdict(DueStatus.DORMANT, summary = "No schedule — done when you say so")
        }

        // --- the date leg ---
        val dateDue = plan.everyDays?.let { days ->
            (plan.lastDoneAt ?: plan.createdAt) + days.toLong() * DAY_MILLIS
        }
        val daysLeft = dateDue?.let { ceil((it - now).toDouble() / DAY_MILLIS).toInt() }

        // --- the meter leg ---
        val meterNow = meter?.current
        val meterDue = nextMeterTarget(plan, meterNow)
        // How far this leg spans, for the "last tenth counts as soon" fallback below: a cadence
        // spans its interval; a milestone spans the gap from whatever it is measured after.
        val meterSpan = meterSpan(plan, meterDue)
        // A rate of zero is no rate: a meter that hasn't moved between two readings cannot date a
        // mileage interval, and dividing by it would put the due date at the end of time.
        val perDay = meter?.perDay?.takeIf { it > 0.0 }
        val meterLeft = if (meterDue != null && meterNow != null) meterDue - meterNow else null
        val meterDueAt = if (meterDue != null && meterNow != null && perDay != null) {
            now + ((meterDue - meterNow).toDouble() / perDay * DAY_MILLIS).roundToLong()
        } else {
            null
        }

        // Milestones need no baseline — they are anchored on the odometer itself — so this is only
        // about a cadence with nothing to measure from.
        if (plan.atMeter.isEmpty() && plan.everyMeter != null && plan.lastDoneMeter == null && plan.everyDays == null) {
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
        val status = status(daysLeft, meterLeft, meterSpan, meterDueAt, dateDue, now, soonDays)

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

    /**
     * The next number on the meter this plan is waiting for.
     *
     * Milestones win when a plan has them. The one judgement in here is what to do about milestones
     * that are **already behind you** when a schedule is first applied: they are taken as done. The
     * app cannot know what a previous owner had done at 30,000 miles, and starting a used car off
     * with eleven overdue jobs produces a list nobody reads — the same reason a plan that has never
     * been done starts its clock the day it was written rather than instantly overdue.
     */
    fun nextMeterTarget(plan: UpkeepPlan, meterNow: Long?): Long? {
        if (plan.atMeter.isNotEmpty()) {
            val floor = plan.lastDoneMeter ?: meterNow
            return if (floor == null) plan.atMeter.firstOrNull() else plan.atMeter.firstOrNull { it > floor }
        }
        val interval = plan.everyMeter ?: return null
        return plan.lastDoneMeter?.let { it + interval }
    }

    /** The distance this leg covers, used only to judge "nearly there" without a usage rate. */
    private fun meterSpan(plan: UpkeepPlan, meterDue: Long?): Long? {
        if (plan.atMeter.isEmpty()) return plan.everyMeter
        if (meterDue == null) return null
        val previous = plan.lastDoneMeter ?: plan.atMeter.lastOrNull { it < meterDue } ?: 0L
        return (meterDue - previous).takeIf { it > 0L }
    }

    private fun status(
        daysLeft: Int?,
        meterLeft: Long?,
        meterSpan: Long?,
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
        val meterSoonByDistance = meterDueAt == null && meterLeft != null && meterSpan != null &&
            meterLeft <= (meterSpan * METER_SOON_FRACTION).roundToLong()
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
