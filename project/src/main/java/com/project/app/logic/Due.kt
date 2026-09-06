package com.project.app.logic

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * When a card is due — and, carefully, nothing else.
 *
 * This is the one date in Project, and the line it sits on is worth stating because the app spent
 * its whole life so far without it. A due date is a **fact about the work**: the competition closes
 * on the 14th, the draft is promised on the 30th. When you will actually sit down and do it is a
 * **decision about your time**, and that decision belongs to LifeOps, which is where a week is
 * planned. Project holds the first and never the second.
 *
 * That is the same line Maintenance draws — it knows the furnace is due a service and says nothing
 * about which evening you will spend on it — and it is what keeps this from becoming a second
 * answer to "what am I doing today". Concretely, it means Project has no agenda, no calendar and no
 * today screen; a board never reorders itself by date; and a card with a date on it looks exactly
 * like a card without one except for the chip that says when it is due.
 */

/** How a due date stands against today. */
enum class DueState {
    /** The day has passed and the card is not finished. */
    OVERDUE,

    /** Due today. */
    TODAY,

    /** Due within [Due.SOON_DAYS]. */
    SOON,

    /** Due, but not yet worth colouring. */
    LATER,

    /**
     * Finished, whenever it was due.
     *
     * A card in the done column stops being late the moment it is done — including one finished
     * after its date. Leaving it red would mean the board carried a permanent accusation about work
     * that is already behind you, which nobody keeps a board to be told.
     */
    DONE
}

/** A due date, read against a particular day. */
data class DueStanding(
    val state: DueState,
    /** Days from today to the date: negative when it has passed, zero today. */
    val daysAway: Long,
    /** The chip's text — "Overdue by 3 days", "Due today", "Due in 4 days", "Due 14 Mar". */
    val label: String
)

/** What Project makes of a date somebody has just chosen, before it is saved. */
sealed interface DueOpinion {
    /** Nothing to say. */
    data object Fine : DueOpinion

    /** Allowed, with a remark worth reading first. */
    data class Remark(val message: String) : DueOpinion
}

object Due {

    /** How far ahead still counts as "soon" — a week, which is the unit a board is worked in. */
    const val SOON_DAYS = 7L

    /**
     * How [dueOn] stands against [today], or `null` when there is no date to stand.
     *
     * [done] wins over everything: see [DueState.DONE].
     */
    fun standing(dueOn: LocalDate?, today: LocalDate, done: Boolean = false): DueStanding? {
        if (dueOn == null) return null
        val daysAway = ChronoUnit.DAYS.between(today, dueOn)
        val state = when {
            done -> DueState.DONE
            daysAway < 0 -> DueState.OVERDUE
            daysAway == 0L -> DueState.TODAY
            daysAway <= SOON_DAYS -> DueState.SOON
            else -> DueState.LATER
        }
        return DueStanding(state, daysAway, label(dueOn, daysAway, state))
    }

    /**
     * The chip's words.
     *
     * Near dates are counted in days because that is how they are felt — "in 3 days" lands where
     * "17 Mar" has to be worked out — and far ones are given as the date, because "in 74 days" is a
     * number nobody converts back into a day of the year. A finished card says when it *was* due
     * rather than how late it is, which is a fact rather than a reproach.
     */
    private fun label(dueOn: LocalDate, daysAway: Long, state: DueState): String = when {
        state == DueState.DONE -> "Was due ${shortDate(dueOn)}"
        daysAway == 0L -> "Due today"
        daysAway == 1L -> "Due tomorrow"
        daysAway == -1L -> "Overdue by a day"
        daysAway < 0 -> "Overdue by ${-daysAway} days"
        daysAway <= SOON_DAYS -> "Due in $daysAway days"
        else -> "Due ${shortDate(dueOn)}"
    }

    /**
     * What to say about a date as it is being picked.
     *
     * Nothing is ever refused. A date in the past is a deadline that has already gone, and people
     * write those down all the time — recording the competition you missed is how the board comes
     * to reflect reality. So it is remarked on and allowed, which is the whole difference between a
     * tool that helps and one that argues.
     */
    fun opinionOf(dueOn: LocalDate?, today: LocalDate): DueOpinion = when {
        dueOn == null -> DueOpinion.Fine
        dueOn.isBefore(today) -> DueOpinion.Remark("That day has passed. The card will read as overdue.")
        else -> DueOpinion.Fine
    }

    /**
     * The cards on a board that are past their day and not finished, soonest first.
     *
     * Reported, never acted on: this is what lets the board say "2 overdue" at the top. It does not
     * reorder anything, because the order of a lane is the order somebody put it in — a board that
     * rearranges itself the morning something slips is a board you stop trusting to stay where you
     * left it.
     */
    fun overdue(cards: List<BoardCard>, doneColumnIds: Set<String>, today: LocalDate): List<BoardCard> =
        cards
            .filter { it.columnId !in doneColumnIds }
            .filter { card -> card.dueOn?.let { it < today.toEpochDay() } == true }
            .sortedWith(compareBy({ it.dueOn }, { it.title.lowercase() }))

    /** An epoch day as a date, tolerating the null that means "no date". */
    fun dateOf(epochDay: Long?): LocalDate? = epochDay?.let { LocalDate.ofEpochDay(it) }

    private fun shortDate(date: LocalDate): String = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"

    /**
     * Month names spelled out here rather than taken from a formatter.
     *
     * `logic/` is pure and its tests must mean the same thing on every machine that runs them; a
     * locale-aware formatter would make "14 Mar" depend on the JVM's default locale, and a test that
     * passes in London and fails in Berlin is worse than one that is a little parochial.
     */
    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )
}
