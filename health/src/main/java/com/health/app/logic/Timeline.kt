package com.health.app.logic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Everything that was done, in the order it was done.
 *
 * `EpisodeSummaries` answers *how did it go* — the peak, the trend, how long the fever has run. This
 * answers the other question people ask about an illness, usually to somebody in a waiting room:
 * **what actually happened, and when?** A doctor asks it, a second parent taking over asks it, and
 * the person who was up all three nights certainly can't answer it from memory.
 *
 * So it takes the four kinds of record — readings, symptoms, doses, care notes — and merges them
 * into one chronological list, grouped by day of the illness. Nothing is summarised or dropped: the
 * whole value of the thing is that it is complete.
 *
 * ## Records made afterwards
 *
 * A history you can fill in later is a history that will be filled in later, and Health would rather
 * have the 2am dose typed up over breakfast than not have it at all. But a record made at the time
 * and a record made from memory are not equally reliable, and presenting a reconstruction as an
 * observation would be a quiet lie about the evidence.
 *
 * So each entry carries when it *happened* and when it was *written*, and [TimelineEntry.wasFilledIn]
 * says whether those differ by more than [FILLED_IN_AFTER_MS]. The history shows it as a note rather
 * than a warning — this is a normal, encouraged thing to do, not a defect — and stays silent for the
 * rows written before Health tracked it, where the honest answer is that it doesn't know.
 *
 * Framework-free and unit-tested, like the rest of `logic/`.
 */

/**
 * What kind of thing happened. The order here is the order simultaneous entries happened in, which
 * is the order they read in — reversed, like everything else, when the history reads newest first.
 */
enum class TimelineKind(val label: String) {
    /** A temperature or other measurement. */
    READING("Reading"),

    /** A symptom starting. */
    SYMPTOM_STARTED("Symptom"),

    /** A symptom being marked over — a separate moment from it starting, and worth its own row. */
    SYMPTOM_ENDED("Symptom passed"),

    DOSE("Dose"),

    /** Fluids, rest, the call to the doctor and what they said. */
    CARE("Care"),

    /** The illness itself starting or ending — the bookends the rest hangs between. */
    EPISODE("Illness")
}

/**
 * One thing that happened.
 *
 * [id] is prefixed by kind because a symptom contributes two entries from one row, and a list needs
 * stable keys that don't collide. [careLevel] is carried only where something computed one — a
 * temperature with a fever assessment — and is null everywhere else rather than defaulting to
 * "routine", which would be Health forming an opinion about a care note.
 */
data class TimelineEntry(
    val id: String,
    val kind: TimelineKind,
    val atMillis: Long,
    /** When the row was written. Null for rows written before Health recorded that. */
    val recordedAtMillis: Long?,
    val headline: String,
    val detail: String? = null,
    val careLevel: CareLevel? = null
) {
    /**
     * Whether this was written up after the fact rather than as it happened.
     *
     * Null [recordedAtMillis] is "don't know", which reads as false: an old row is not evidence of
     * anything either way, and flagging it would be inventing a fact about how it was entered.
     */
    val wasFilledIn: Boolean
        get() = recordedAtMillis != null &&
            recordedAtMillis - atMillis > Timeline.FILLED_IN_AFTER_MS

    /** "written 7h later" — how the note reads next to the entry, or null when it doesn't apply. */
    val filledInLabel: String?
        get() {
            if (!wasFilledIn) return null
            val gap = (recordedAtMillis ?: return null) - atMillis
            return "written ${DoseSchedule.formatDuration(gap)} later"
        }
}

/**
 * Which way the history reads.
 *
 * Whichever is chosen, it applies to **both** levels of the list — the days and the entries inside
 * them. That sounds obvious and was, for a while, not what this did: days ran newest-first while each
 * day ran forwards, on the reasoning that you want the latest day immediately and then to read it
 * the way it was lived. Both halves of that are true and the combination is still wrong, because it
 * breaks the one promise a timeline makes — that moving one row moves you one step in time. A
 * reading at 23:55 and the next one at 00:05 are ten minutes apart and were landing at opposite ends
 * of the screen, with a whole day of records between them, which is exactly the stretch of an
 * illness somebody is trying to read when they are up at midnight.
 *
 * So the direction is one choice, made once, and the reader picks it: [NEWEST_FIRST] to see where
 * things stand now, [OLDEST_FIRST] to read the illness as a story from the beginning.
 */
enum class TimelineOrder(val label: String) {
    /** Now at the top; reading downwards walks backwards in time. */
    NEWEST_FIRST("Newest first"),

    /** The start at the top; reading downwards walks forwards, the way it was lived. */
    OLDEST_FIRST("Oldest first")
}

/**
 * One day of the history: its entries, and which day of the illness it is.
 *
 * [dayNumber] counts calendar days from the episode's first day, so the day the illness started is
 * "Day 1" — which is how everybody counts it out loud, and the number a doctor is asking for when
 * they say "and how long has this been going on?". Null when the history isn't anchored to an
 * episode, where a day number would be counting from nothing.
 */
data class TimelineDay(
    val date: LocalDate,
    val dayNumber: Int?,
    val entries: List<TimelineEntry>
) {
    val label: String get() = dayNumber?.let { "Day $it" } ?: date.toString()
}

/** The rows to merge, already flattened out of the database. */
data class TimelineFacts(
    val readings: List<TimelineEntry> = emptyList(),
    val symptoms: List<TimelineEntry> = emptyList(),
    val doses: List<TimelineEntry> = emptyList(),
    val careNotes: List<TimelineEntry> = emptyList(),
    /** The illness this is the history of, when it is one. Anchors the day numbering. */
    val episodeStartedAtMillis: Long? = null,
    val episodeEndedAtMillis: Long? = null,
    val episodeTitle: String? = null
)

object Timeline {

    /**
     * How long after the fact a record counts as filled in rather than logged live.
     *
     * Half an hour. Long enough that finishing the thermometer, settling a child and *then* opening
     * the app is still "at the time" — which it is, and flagging it would make the note meaningless
     * by attaching it to nearly everything. Short enough that last night's dose typed up at
     * breakfast is correctly a reconstruction.
     */
    const val FILLED_IN_AFTER_MS: Long = 30L * 60 * 1000

    /**
     * Merge everything into days, in one consistent direction — see [TimelineOrder].
     *
     * The episode's own start and end become entries too, so the history has its bookends and it is
     * obvious at a glance which records fall inside the illness and which were adopted from just
     * before it.
     */
    fun build(
        facts: TimelineFacts,
        zone: ZoneId = ZoneId.systemDefault(),
        order: TimelineOrder = TimelineOrder.NEWEST_FIRST
    ): List<TimelineDay> {
        val entries = buildList {
            addAll(facts.readings)
            addAll(facts.symptoms)
            addAll(facts.doses)
            addAll(facts.careNotes)

            facts.episodeStartedAtMillis?.let { started ->
                add(
                    TimelineEntry(
                        id = "episode:start",
                        kind = TimelineKind.EPISODE,
                        atMillis = started,
                        recordedAtMillis = null,
                        headline = facts.episodeTitle?.let { "$it started" } ?: "Illness started"
                    )
                )
            }
            facts.episodeEndedAtMillis?.let { ended ->
                add(
                    TimelineEntry(
                        id = "episode:end",
                        kind = TimelineKind.EPISODE,
                        atMillis = ended,
                        recordedAtMillis = null,
                        headline = facts.episodeTitle?.let { "$it marked over" } ?: "Illness marked over"
                    )
                )
            }
        }

        if (entries.isEmpty()) return emptyList()

        val firstDay = facts.episodeStartedAtMillis?.let { dateOf(it, zone) }

        val days = entries
            .groupBy { dateOf(it.atMillis, zone) }
            .map { (date, dayEntries) ->
                TimelineDay(
                    date = date,
                    dayNumber = firstDay?.let { ChronoUnit.DAYS.between(it, date).toInt() + 1 },
                    entries = dayEntries
                )
            }

        return inOrder(days, order)
    }

    /**
     * Put a history the requested way round — days and the entries inside them together.
     *
     * Sorting rather than reversing, so it doesn't matter which way the list already ran: the screen
     * that offers the reader a direction can hand back whatever it last drew and get the right answer.
     *
     * Ties are broken by [TimelineKind] and then id, so a dose given "at" the same minute as the
     * reading that prompted it reads in the order the two actually happened — and the other way round
     * under [TimelineOrder.NEWEST_FIRST], because that is what "later is higher" means when two rows
     * share a minute. Every comparison flips together; a timeline that reversed one level and not
     * another is the bug this replaced.
     */
    fun inOrder(days: List<TimelineDay>, order: TimelineOrder): List<TimelineDay> {
        val newestFirst = order == TimelineOrder.NEWEST_FIRST
        val byMoment = compareBy<TimelineEntry>({ it.atMillis }, { it.kind.ordinal }, { it.id })
            .let { if (newestFirst) it.reversed() else it }
        val byDate = compareBy<TimelineDay> { it.date }
            .let { if (newestFirst) it.reversed() else it }

        return days
            .map { day -> day.copy(entries = day.entries.sortedWith(byMoment)) }
            .sortedWith(byDate)
    }

    /**
     * How many entries were written up after the fact — what the history's header says, so somebody
     * reading it knows how much of it is memory before they rely on it.
     */
    fun filledInCount(days: List<TimelineDay>): Int =
        days.sumOf { day -> day.entries.count { it.wasFilledIn } }

    fun entryCount(days: List<TimelineDay>): Int =
        days.sumOf { day -> day.entries.count { it.kind != TimelineKind.EPISODE } }

    private fun dateOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
