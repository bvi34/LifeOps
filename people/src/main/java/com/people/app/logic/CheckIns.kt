package com.people.app.logic

import java.time.LocalDate

/**
 * The daily check-in's rules, framework-free and unit-tested.
 *
 * A check-in is a small form somebody designs **for one person** — "what they had for lunch", "the
 * activity they enjoyed", "how the day went" — and fills in once a day. The form is per person on
 * purpose: the questions worth asking about a six-year-old are not the ones worth asking about a
 * parent, and a single household-wide form would end up as the union of everybody's, mostly blank.
 *
 * What lives here is everything that would otherwise be decided twice — once by the screen and once
 * by the store — and get two different answers: what a kind accepts, what a stored value looks like,
 * how it reads back, and when a run of days counts as unbroken.
 */

/**
 * What one question asks for.
 *
 * Deliberately few. Every extra kind is a control to build, a value to validate and a way to render
 * it, and the six here already cover the shape of a day: a couple of words, a paragraph, a count, a
 * yes, a rating, and a pick from a list. Anything more specific is a [TEXT] field with a good label.
 *
 * The [key] is what the column stores, so renaming a constant cannot silently retype existing rows.
 */
enum class CheckInKind(val key: String, val label: String, val hint: String) {
    TEXT("text", "Short answer", "A word or a line — what they had for lunch"),
    NOTE("note", "Longer note", "A few sentences — how the day went"),
    NUMBER("number", "Number", "Hours slept, pages read, times asked"),
    YES_NO("yes_no", "Yes or no", "Did it happen at all"),
    SCALE("scale", "Scale of 1 to 5", "Mood, energy, how the day went"),
    CHOICE("choice", "One of a list", "Pick from options you set");

    companion object {
        /** Unknown keys read as [TEXT]: a row from a newer version is shown, not dropped. */
        fun fromKey(key: String?): CheckInKind = entries.firstOrNull { it.key == key } ?: TEXT
    }
}

object CheckIns {

    const val SCALE_MIN = 1
    const val SCALE_MAX = 5

    /** What [YES_NO][CheckInKind.YES_NO] stores. Words rather than 0/1, so a row reads as itself. */
    const val YES = "yes"
    const val NO = "no"

    // --- options, for the one kind that has them ---

    /**
     * A choice field's options, stored as one newline-separated string.
     *
     * A column rather than a table because options are not a thing anybody queries across: they are
     * part of the question's text, they are always read with it, and a five-row join to render one
     * dropdown would be a table earning nothing. Blanks and duplicates are dropped here so the two
     * places that build a chip row cannot disagree about what the list is.
     */
    fun encodeOptions(options: List<String>): String? {
        val cleaned = options.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return cleaned.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun decodeOptions(raw: String?): List<String> =
        raw?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    // --- values ---

    /**
     * What a typed answer becomes in the database, or null when there is nothing to record.
     *
     * Null is the whole point of this function. A check-in is filled in by somebody in a hurry, and
     * most days most fields are left alone; an empty answer must be *absent* rather than an empty
     * string, so that "they didn't say" and "they said nothing happened" stay different facts and a
     * day with three of eight fields filled is not stored as five lies.
     *
     * Invalid answers are treated the same as empty ones rather than throwing. Everything reaching
     * here has already been through a control that only offers valid values — this is the backstop
     * for a field whose kind or options changed *after* somebody typed into it, which is a real
     * sequence and not one worth crashing over.
     */
    fun clean(kind: CheckInKind, raw: String?, options: List<String> = emptyList()): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return when (kind) {
            CheckInKind.TEXT, CheckInKind.NOTE -> text
            CheckInKind.NUMBER -> text.takeIf { it.toDoubleOrNull() != null }
            CheckInKind.YES_NO -> when (text.lowercase()) {
                YES, "true", "y" -> YES
                NO, "false", "n" -> NO
                else -> null
            }
            CheckInKind.SCALE -> text.toIntOrNull()?.takeIf { it in SCALE_MIN..SCALE_MAX }?.toString()
            // Matched case-insensitively but stored as the option is written, so renaming an option's
            // capitalisation does not leave two spellings of the same answer in the history.
            CheckInKind.CHOICE -> options.firstOrNull { it.equals(text, ignoreCase = true) }
        }
    }

    /** One stored value, as a person reads it. */
    fun display(kind: CheckInKind, value: String): String = when (kind) {
        CheckInKind.YES_NO -> if (value == YES) "Yes" else "No"
        CheckInKind.SCALE -> "$value of $SCALE_MAX"
        else -> value
    }

    /**
     * A day's answers on one line, for the person's page and the history list.
     *
     * Truncated rather than wrapped: the summary's job is to say *whether* the day was recorded and
     * roughly what it said, and the day itself is one tap away. A long note is cut at a word so the
     * line does not end mid-syllable.
     */
    fun summarise(answers: List<Pair<String, String>>, fields: Int = 2, width: Int = 40): String {
        if (answers.isEmpty()) return "Nothing recorded"
        val shown = answers.take(fields).joinToString(" · ") { (label, value) ->
            "$label: ${value.truncate(width)}"
        }
        val rest = answers.size - fields
        return if (rest > 0) "$shown · +$rest more" else shown
    }

    private fun String.truncate(width: Int): String {
        if (length <= width) return this
        val cut = take(width)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > width / 2) cut.take(lastSpace) else cut).trimEnd() + "…"
    }

    // --- days ---

    /**
     * How many days in a row have been checked in, counting back from [today].
     *
     * Today not being done yet does **not** break the run, and that is the only interesting decision
     * in here. A check-in is an evening habit; a streak that reset at midnight would tell somebody
     * they had broken a four-day run at breakfast, before the day it is counting had even happened.
     * So a run ending yesterday still counts — it is only a day with *nothing* on either side of it
     * that ends one.
     */
    fun streak(days: Collection<LocalDate>, today: LocalDate = LocalDate.now()): Int {
        val recorded = days.toHashSet()
        var cursor = if (today in recorded) today else today.minusDays(1)
        var run = 0
        while (cursor in recorded) {
            run++
            cursor = cursor.minusDays(1)
        }
        return run
    }

    /** Parse a stored `yyyy-MM-dd` day, or null — the same forgiving read the dates logic uses. */
    fun parseDay(day: String?): LocalDate? =
        day?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /**
     * How a day reads on screen: today and yesterday by name, anything else by date.
     *
     * Worth the special-casing because those two are the only days anybody normally fills in, and
     * "Yesterday" answers *is this the one I missed?* at a glance where "30 Aug" does not.
     */
    fun describeDay(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> "%d %s".format(
            day.dayOfMonth,
            day.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
        )
    }

    /**
     * The form somebody gets if they ask for a starting point, rather than a blank page.
     *
     * An empty form builder is a worse first experience than a slightly wrong form: nobody knows
     * what a good question looks like until they see three, and every one of these is renameable and
     * removable. They are the questions a school day actually produces — what they ate, what they
     * enjoyed, and room for the rest.
     */
    val STARTER_FORM: List<Triple<String, CheckInKind, List<String>>> = listOf(
        Triple("How the day went", CheckInKind.SCALE, emptyList()),
        Triple("Lunch", CheckInKind.TEXT, emptyList()),
        Triple("Enjoyed", CheckInKind.TEXT, emptyList()),
        Triple("Anything else", CheckInKind.NOTE, emptyList())
    )
}
