package com.maintenance.app.logic

import kotlin.math.roundToLong

/** One reading off a meter: the number on the dial, and when it was read. */
data class MeterReading(val readAt: Long, val value: Long)

/**
 * What a meter's history says about the future.
 *
 * A mileage-based service interval — "every 5,000 miles" — is useless on its own, because the
 * question a person actually asks is *when*. Two readings a month apart answer it: they give a rate,
 * and a rate turns 5,000 miles into a date you can plan around. That is this file's whole job, and
 * it is the reason readings are stored at all rather than a single "current mileage" field being
 * overwritten each time.
 *
 * The rate is measured from the readings themselves rather than assumed. There is no default
 * "12,000 miles a year" here: a car that does 3,000 gets its own answer, and a car with one reading
 * gets no answer at all rather than a made-up one.
 */
object Meter {

    private const val DAY_MILLIS = 86_400_000.0

    /** The most recent reading, by time. */
    fun latest(readings: List<MeterReading>): MeterReading? = readings.maxByOrNull { it.readAt }

    /**
     * The readings a rate can honestly be drawn from: the run since the last time the meter went
     * *backwards*.
     *
     * Meters do go backwards — a cluster is replaced, an hour meter is swapped with the engine, or
     * somebody typed 12,000 for 21,000 and fixed it next month. Averaging across that produces a
     * negative or absurd rate, so everything up to and including the drop is dropped and the rate
     * is drawn from the run that follows. Losing history is the right trade: the recent run is
     * the one that predicts next month anyway.
     */
    fun usable(readings: List<MeterReading>): List<MeterReading> {
        val ordered = readings.sortedBy { it.readAt }
        var start = 0
        for (i in 1 until ordered.size) {
            if (ordered[i].value < ordered[i - 1].value) start = i
        }
        return ordered.drop(start)
    }

    /**
     * How fast the meter climbs, per day — or null when the readings cannot say: fewer than two of
     * them, or two taken on the same day.
     */
    fun perDay(readings: List<MeterReading>): Double? {
        val run = usable(readings)
        if (run.size < 2) return null
        val first = run.first()
        val last = run.last()
        val days = (last.readAt - first.readAt) / DAY_MILLIS
        if (days <= 0.0) return null
        val travelled = (last.value - first.value).toDouble()
        if (travelled <= 0.0) return null
        return travelled / days
    }

    /**
     * Where the meter is expected to read at [at], given the last reading and the rate. With only
     * one reading to go on the last reading *is* the answer — a number that is at least true of
     * some day, rather than a projection off a rate that doesn't exist yet.
     */
    fun projectValue(readings: List<MeterReading>, at: Long): Long? {
        val last = latest(readings) ?: return null
        val rate = perDay(readings) ?: return last.value
        val days = (at - last.readAt) / DAY_MILLIS
        return (last.value + rate * days).roundToLong()
    }

    /**
     * When the meter is expected to reach [target] — the date a mileage interval falls due.
     *
     * Null when there is no rate to project with, which the screens report as "no date yet, log
     * another reading" rather than inventing one. A target already passed comes back as the last
     * reading's own timestamp: it is due now, and pretending to know *which day last month* it
     * crossed would be arithmetic dressed up as a fact.
     */
    fun projectDate(readings: List<MeterReading>, target: Long): Long? {
        val last = latest(readings) ?: return null
        if (last.value >= target) return last.readAt
        val rate = perDay(readings) ?: return null
        val days = (target - last.value).toDouble() / rate
        return last.readAt + (days * DAY_MILLIS).roundToLong()
    }

    /** How far the meter moved between [from] and [to], for cost-per-mile arithmetic. */
    fun travelled(readings: List<MeterReading>, from: Long, to: Long): Long? {
        val window = usable(readings).filter { it.readAt in from..to }
        if (window.size < 2) return null
        return window.last().value - window.first().value
    }
}
