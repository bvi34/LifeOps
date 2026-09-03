package com.lifeops.app.util

import com.operations.suitekit.SuiteVerdict
import java.time.LocalDate

/**
 * What LifeOps thinks of a due date somebody has just picked.
 *
 * The suite's date picker offers every day and asks the app. This is LifeOps' answer — and it is
 * deliberately not Health's. A date next week is a *plan* here, which is the whole point of a
 * planner; the same tap in Health is a typo. What LifeOps cares about instead is which week the date
 * lands in, because that decides whether the task is filed, parked, or quietly dropped.
 *
 * Two things it says out loud that the app used to keep to itself:
 *
 * **A closed week is refused.** `TaskRepository.addTask` returns without writing when the week has
 * been closed — the task is typed, confirmed, and silently gone. That is the worst kind of failure,
 * and it was invisible because nothing on the way in ever mentioned the week's state.
 *
 * **A date past this week is fine, and worth a word.** The task does not vanish; it waits in Future
 * Tasks until its week comes round. That note already existed as a line hand-drawn under the picker
 * in one dialog. It belongs to the date, not to that dialog, so it lives here now.
 */
object DueDates {

    /**
     * [weekEndDate] and [weekStartDate] are the current week's ISO bounds, [weekClosed] its state.
     * A null week — the app has not opened one yet — has no opinion to offer, so nothing is said.
     */
    fun check(
        date: LocalDate,
        weekStartDate: String?,
        weekEndDate: String?,
        weekClosed: Boolean
    ): SuiteVerdict {
        // ISO dates compare lexicographically, which is why these are plain string compares.
        val iso = date.toString()
        if (weekClosed && weekStartDate != null && weekEndDate != null &&
            iso >= weekStartDate && iso <= weekEndDate
        ) {
            return SuiteVerdict.Refused(
                "That week is already closed — a task dated into it would not be filed. " +
                    "Pick a day in the week ahead."
            )
        }
        if (weekEndDate != null && iso > weekEndDate) {
            return SuiteVerdict.Note(
                "Due after this week — it will wait in Future Tasks (Planning tab) until its week arrives."
            )
        }
        return SuiteVerdict.Fine
    }
}
