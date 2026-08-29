package com.project.app.logic

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The scales a "when" can be read on. Two events are only comparable on the same scale. */
enum class WhenScale(val key: String, val label: String, val unit: String) {
    /** A real calendar date, read as an epoch day. */
    DATE("date", "Date", "days"),
    /** A bare or prefixed year: "1487", "Year 412", "412 AD". */
    YEAR("year", "Year", "years"),
    /** A day count from some start: "Day 3", "D12". */
    DAY("day", "Day", "days"),
    /** A chapter or part number: "Chapter 4". Ordering only; gaps between them mean nothing. */
    CHAPTER("chapter", "Chapter", "chapters")
}

/** A when-label the app could read a number out of. */
data class ParsedWhen(val value: Long, val scale: WhenScale, val source: String)

/** One event on the project's timeline. */
data class TimelineEvent(
    val id: String,
    val title: String,
    val detail: String?,
    /** The era, age or arc this sits in. Free text; blank means "no era". */
    val era: String?,
    /** What the author wrote in the "when" box. Never interpreted destructively. */
    val whenLabel: String?,
    /** The author's own ordering. This, and not the label, is what the screen draws by. */
    val order: Int,
    val outlineNodeId: String? = null
)

/** An event as the timeline screen draws it. */
data class TimelineRow(
    val event: TimelineEvent,
    val parsed: ParsedWhen?,
    /** Distance from the previous event on the same scale, when both could be read. */
    val gapFromPrevious: Long?,
    /** True when this row opens a new era band. */
    val startsEra: Boolean,
    /** True when the label says this happens before the event drawn above it. */
    val contradictsOrder: Boolean
)

/** A run of consecutive events sharing an era. */
data class EraSpan(val label: String?, val firstIndex: Int, val lastIndex: Int, val count: Int)

/**
 * The timeline: what order things happen in, and whether the author's order agrees with the dates
 * they wrote down.
 *
 * The design decision that everything else follows from: **the author's manual order is the
 * timeline; the "when" label is a note about it.** A project timeline has to hold "Tuesday",
 * "Year 412 of the Concord", "Day 3", and "some time before the war" — often in the same project —
 * and any scheme that insists on a sortable value will either refuse the vague entries or invent a
 * number for them. So events carry an explicit [TimelineEvent.order] and a free-text
 * [TimelineEvent.whenLabel], and this file's job is to *read* the label where it can and say
 * something useful about it.
 *
 * What it says is [TimelineRow.contradictsOrder]: you placed the coronation before the battle, but
 * you dated it after. That is the error a story timeline exists to catch, and it is only catchable
 * because the two facts are kept apart instead of one being derived from the other. [autoSorted]
 * offers to fix it — offers, because the author may well be right and the label a typo.
 */
object Timeline {

    private val YEAR_LABEL = Regex("""^(?:year|yr|y)\s*[.:]?\s*(-?\d{1,6})\b""", RegexOption.IGNORE_CASE)
    private val YEAR_SUFFIX = Regex("""^(-?\d{1,6})\s*(ad|ce|bc|bce)?\.?$""", RegexOption.IGNORE_CASE)
    private val DAY_LABEL = Regex("""^(?:day|d)\s*[.:]?\s*(-?\d{1,6})\b""", RegexOption.IGNORE_CASE)
    private val CHAPTER_LABEL = Regex("""^(?:chapter|ch|part|act)\s*[.:]?\s*(-?\d{1,6})\b""", RegexOption.IGNORE_CASE)

    /**
     * Read a number out of a when-label, or return null when it does not read as one.
     *
     * Null is a perfectly good answer. "The night before the coronation" is a legitimate thing to
     * write in the box, and the timeline still draws it exactly where the author put it — it simply
     * cannot be checked against its neighbours, which is honest.
     */
    fun parseWhen(label: String?): ParsedWhen? {
        val text = label?.trim().orEmpty()
        if (text.isEmpty()) return null

        isoDate(text)?.let { return ParsedWhen(it.toEpochDay(), WhenScale.DATE, text) }
        DAY_LABEL.find(text)?.let { return ParsedWhen(it.groupValues[1].toLong(), WhenScale.DAY, text) }
        CHAPTER_LABEL.find(text)?.let { return ParsedWhen(it.groupValues[1].toLong(), WhenScale.CHAPTER, text) }
        YEAR_LABEL.find(text)?.let { return ParsedWhen(it.groupValues[1].toLong(), WhenScale.YEAR, text) }
        YEAR_SUFFIX.find(text)?.let { match ->
            val magnitude = match.groupValues[1].toLong()
            val era = match.groupValues[2].lowercase()
            // "44 BC" is before "14 AD"; storing it as a negative year is what makes that true.
            val value = if (era == "bc" || era == "bce") -magnitude else magnitude
            return ParsedWhen(value, WhenScale.YEAR, text)
        }
        return null
    }

    private fun isoDate(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    /** The events as drawn: the author's order, ties broken by title so the list never jitters. */
    fun ordered(events: List<TimelineEvent>): List<TimelineEvent> =
        events.sortedWith(compareBy({ it.order }, { it.title.lowercase() }))

    /**
     * The scale the whole timeline is on, or null when its labels mix scales (or there are none).
     *
     * Mixed scales are not an error — a project may date its modern chapters and number its
     * flashback days — but nothing can be *compared* across them, so gaps and contradictions are
     * only computed between adjacent events that share one.
     */
    fun scaleOf(events: List<TimelineEvent>): WhenScale? {
        val scales = events.mapNotNull { parseWhen(it.whenLabel)?.scale }.toSet()
        return scales.singleOrNull()
    }

    /** Rows for the screen: parsed labels, era bands, gaps, and the order contradictions. */
    fun rows(events: List<TimelineEvent>): List<TimelineRow> {
        val ordered = ordered(events)
        var previousEra: String? = null
        var previousParsed: ParsedWhen? = null

        return ordered.mapIndexed { index, event ->
            val parsed = parseWhen(event.whenLabel)
            val era = event.era?.takeIf { it.isNotBlank() }
            val comparable = parsed != null && previousParsed != null && parsed.scale == previousParsed!!.scale
            val gap = if (comparable) parsed!!.value - previousParsed!!.value else null

            val row = TimelineRow(
                event = event,
                parsed = parsed,
                gapFromPrevious = gap,
                startsEra = index == 0 || era != previousEra,
                // A chapter number going backwards is a flashback, not a mistake, so it is never
                // reported as a contradiction — only dated scales are checked.
                contradictsOrder = gap != null && gap < 0 && parsed!!.scale != WhenScale.CHAPTER
            )

            previousEra = era
            if (parsed != null) previousParsed = parsed
            row
        }
    }

    /** The era bands, as consecutive runs. An era that recurs later is a second span, not a merge. */
    fun eras(events: List<TimelineEvent>): List<EraSpan> {
        val ordered = ordered(events)
        val spans = ArrayList<EraSpan>()
        var start = 0
        while (start < ordered.size) {
            val label = ordered[start].era?.takeIf { it.isNotBlank() }
            var end = start
            while (end + 1 < ordered.size && ordered[end + 1].era?.takeIf { it.isNotBlank() } == label) end++
            spans += EraSpan(label, start, end, end - start + 1)
            start = end + 1
        }
        return spans
    }

    /** True when every event's label reads on one scale, so an automatic sort would mean something. */
    fun canAutoSort(events: List<TimelineEvent>): Boolean =
        events.size > 1 && events.all { parseWhen(it.whenLabel) != null } && scaleOf(events) != null

    /**
     * The events re-ordered by their labels. Returns the list unchanged when the labels cannot
     * carry the sort — an offer that quietly did nothing would be worse than a disabled button.
     */
    fun autoSorted(events: List<TimelineEvent>): List<TimelineEvent> {
        if (!canAutoSort(events)) return ordered(events)
        return events
            .sortedWith(compareBy({ parseWhen(it.whenLabel)!!.value }, { it.title.lowercase() }))
            .mapIndexed { index, event -> if (event.order == index) event else event.copy(order = index) }
    }

    /** The ids of events whose label puts them before the event drawn above them. */
    fun contradictions(events: List<TimelineEvent>): List<String> =
        rows(events).filter { it.contradictsOrder }.map { it.event.id }

    /** The next free order — where a newly added event lands. */
    fun nextOrder(events: List<TimelineEvent>): Int = (events.maxOfOrNull { it.order } ?: -1) + 1

    /** Move an event one place up (-1) or down (+1), renumbering the run it belongs to. */
    fun move(events: List<TimelineEvent>, id: String, delta: Int): List<TimelineEvent> {
        val ordered = ordered(events).toMutableList()
        val index = ordered.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in ordered.indices) return emptyList()
        ordered.add(target, ordered.removeAt(index))
        return ordered.mapIndexedNotNull { position, event ->
            event.takeIf { it.order != position }?.copy(order = position)
        }
    }
}
