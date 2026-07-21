package com.lifeops.app.util

import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.data.model.ForecastPeriod
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Pure, Android-free free/busy logic. Kept in util/ so the recurrence/overlap rules are
 * JVM-testable, matching the growth/weather/recurrence modules.
 */
object BusyBlocks {

    /** True when [block] is active on [date] — a one-off matches its date; a weekly block matches
     *  when its day-of-week bit is set (bit 0 = Monday … bit 6 = Sunday). */
    fun occursOn(block: BusyBlock, date: LocalDate): Boolean =
        if (block.specificDate != null) {
            block.specificDate == date.toString()
        } else {
            (block.daysMask and (1 shl (date.dayOfWeek.value - 1))) != 0
        }

    /** True when any of [blocks] overlaps the local window [periodStart, periodEnd). Half-open so a
     *  block that ends exactly when a window starts is not treated as a conflict. Spans every date
     *  the window touches (a window rarely crosses midnight, but this stays correct if it does). */
    fun isBusy(blocks: List<BusyBlock>, periodStart: LocalDateTime, periodEnd: LocalDateTime): Boolean {
        if (blocks.isEmpty() || !periodStart.isBefore(periodEnd)) return false
        var date = periodStart.toLocalDate()
        val lastDate = periodEnd.toLocalDate()
        while (!date.isAfter(lastDate)) {
            for (block in blocks) {
                if (!occursOn(block, date)) continue
                val midnight = date.atStartOfDay()
                val blockStart = midnight.plusMinutes(block.startMinutes.toLong())
                val blockEnd = midnight.plusMinutes(block.endMinutes.toLong())
                if (blockStart.isBefore(periodEnd) && blockEnd.isAfter(periodStart)) return true
            }
            date = date.plusDays(1)
        }
        return false
    }

    /**
     * Names of the forecast [periods] that collide with [blocks], for BestTime's `busyLabels`
     * disqualifier. Forecast start/end are ISO-8601 with offset (NWS); we compare on local wall
     * time. A period whose timestamps can't be parsed is skipped rather than crashing the ranking.
     */
    fun conflictingPeriodNames(blocks: List<BusyBlock>, periods: List<ForecastPeriod>): Set<String> {
        if (blocks.isEmpty()) return emptySet()
        return periods.mapNotNull { period ->
            val start = parseLocal(period.startTime) ?: return@mapNotNull null
            val end = parseLocal(period.endTime) ?: return@mapNotNull null
            if (isBusy(blocks, start, end)) period.name else null
        }.toSet()
    }

    private fun parseLocal(iso: String): LocalDateTime? = try {
        OffsetDateTime.parse(iso).toLocalDateTime()
    } catch (e: DateTimeParseException) {
        try { LocalDateTime.parse(iso) } catch (e2: DateTimeParseException) { null }
    }
}
