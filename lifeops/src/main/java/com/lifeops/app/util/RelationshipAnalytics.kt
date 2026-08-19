package com.lifeops.app.util

import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.data.model.Person
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Pure, Android-free relationship-balance analytics — JVM-testable, matching BusyBlocks. Reads
 * the same own-schedule busy blocks the Planning calendar already shows (personId == null,
 * tagged with peopleIds); a block's occurrences within the lookback window are the "booked time"
 * signal, same expansion rule as BusyBlocks.occursOn (a weekly block counts once per matching
 * day, a one-off once on its date).
 */
object RelationshipAnalytics {
    const val DEFAULT_WINDOW_DAYS = 30
    private const val LOOKBACK_DAYS_FOR_LAST_SEEN = 120
    private const val DEFAULT_NEGLECT_THRESHOLD_DAYS = 14
    private const val DEFAULT_SKEW_FRACTION = 0.4

    /** How much booked time and how recently, per person, over the trailing window. */
    fun bookingStats(
        blocks: List<BusyBlock>,
        personIds: Collection<String>,
        today: LocalDate = LocalDate.now(),
        windowDays: Int = DEFAULT_WINDOW_DAYS
    ): Map<String, PersonBookingStats> {
        val ids = personIds.toSet()
        val minutesById = ids.associateWith { 0 }.toMutableMap()
        val occurrencesById = ids.associateWith { 0 }.toMutableMap()
        val lastSeenById = ids.associateWith { null as LocalDate? }.toMutableMap()

        val taggedBlocks = blocks.filter { it.peopleIds.any { pid -> pid in ids } }
        if (taggedBlocks.isNotEmpty()) {
            val windowStart = today.minusDays(windowDays.toLong())
            var date = today.minusDays(LOOKBACK_DAYS_FOR_LAST_SEEN.toLong())
            while (!date.isAfter(today)) {
                for (block in taggedBlocks) {
                    if (!BusyBlocks.occursOn(block, date)) continue
                    val duration = (block.endMinutes - block.startMinutes).coerceAtLeast(0)
                    for (personId in block.peopleIds) {
                        if (personId !in ids) continue
                        lastSeenById[personId] = date // dates ascend, so the last write wins
                        if (!date.isBefore(windowStart)) {
                            minutesById[personId] = minutesById.getValue(personId) + duration
                            occurrencesById[personId] = occurrencesById.getValue(personId) + 1
                        }
                    }
                }
                date = date.plusDays(1)
            }
        }

        return ids.associateWith { id ->
            PersonBookingStats(
                personId = id,
                minutesInWindow = minutesById.getValue(id),
                occurrencesInWindow = occurrencesById.getValue(id),
                daysSinceLastBooked = lastSeenById.getValue(id)?.let { ChronoUnit.DAYS.between(it, today).toInt() }
            )
        }
    }

    /**
     * Nudges for the booking flow: people who look neglected (nothing booked with them in a
     * while) or who are getting notably less time than their relationship-category peers (e.g.
     * one child vs a sibling). Only considers active people with a [Person.relationship] set —
     * that's the opt-in for relationship analytics. Sorted most-overdue first.
     */
    fun findImbalances(
        people: List<Person>,
        blocks: List<BusyBlock>,
        today: LocalDate = LocalDate.now(),
        windowDays: Int = DEFAULT_WINDOW_DAYS,
        neglectThresholdDays: Int = DEFAULT_NEGLECT_THRESHOLD_DAYS,
        skewFraction: Double = DEFAULT_SKEW_FRACTION
    ): List<RelationshipImbalance> {
        val tracked = people.filter { !it.isArchived && it.relationship != null }
        if (tracked.isEmpty()) return emptyList()
        val stats = bookingStats(blocks, tracked.map { it.id }, today, windowDays)
        val results = mutableListOf<RelationshipImbalance>()
        val flagged = mutableSetOf<String>()

        // Category skew: within each relationship bucket of 2+ people, flag anyone notably below
        // the bucket's average booked time.
        tracked.groupBy { it.relationship }.values.forEach { group ->
            if (group.size < 2) return@forEach
            val average = group.map { stats.getValue(it.id).minutesInWindow }.average()
            if (average <= 0.0) return@forEach
            group.forEach { person ->
                val s = stats.getValue(person.id)
                if (s.minutesInWindow < average * skewFraction) {
                    results += RelationshipImbalance(
                        person = person,
                        kind = ImbalanceKind.CATEGORY_SKEW,
                        stats = s,
                        bucketAverageMinutes = average.roundToInt(),
                        message = "Less time with ${person.name} than other ${person.relationship!!.label.lowercase()}s this month"
                    )
                    flagged += person.id
                }
            }
        }

        // Absolute neglect: nothing booked in a while, regardless of category size. Skipped for
        // anyone already flagged above so the same person doesn't produce two competing nudges.
        tracked.forEach { person ->
            if (person.id in flagged) return@forEach
            val s = stats.getValue(person.id)
            val days = s.daysSinceLastBooked
            if (days == null || days >= neglectThresholdDays) {
                results += RelationshipImbalance(
                    person = person,
                    kind = ImbalanceKind.NEGLECTED,
                    stats = s,
                    bucketAverageMinutes = null,
                    message = if (days == null) "Nothing booked with ${person.name} yet"
                              else "Nothing booked with ${person.name} in $days days"
                )
            }
        }

        return results.sortedWith(
            compareByDescending<RelationshipImbalance> { it.stats.daysSinceLastBooked ?: Int.MAX_VALUE }
                .thenBy { it.stats.minutesInWindow }
        )
    }
}

data class PersonBookingStats(
    val personId: String,
    /** Total minutes booked with this person across occurrences in the trailing window. */
    val minutesInWindow: Int,
    val occurrencesInWindow: Int,
    /** Days since the most recent occurrence (within the lookback horizon); null = none found. */
    val daysSinceLastBooked: Int?
)

enum class ImbalanceKind { NEGLECTED, CATEGORY_SKEW }

data class RelationshipImbalance(
    val person: Person,
    val kind: ImbalanceKind,
    val stats: PersonBookingStats,
    /** Average minutes among this person's relationship-category peers; null for NEGLECTED. */
    val bucketAverageMinutes: Int?,
    val message: String
)
