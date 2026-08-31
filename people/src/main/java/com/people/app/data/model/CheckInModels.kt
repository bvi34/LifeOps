package com.people.app.data.model

import com.people.app.logic.CheckIns
import com.people.app.logic.CheckInKind
import java.time.LocalDate

/**
 * What the check-in screens work in: rows with their kind resolved, their options split, and each
 * answer already sitting beside the question it answers.
 *
 * The join is done once, here, rather than in each screen — the person's page, the day being filled
 * in and the history list all need the same "question, kind, value" triple, and three of them
 * assembling it separately is three chances to show an answer under the wrong label.
 */

/** One question on a person's form. */
data class CheckInField(
    val id: String,
    val personId: String,
    val label: String,
    val kind: CheckInKind,
    val options: List<String>,
    val position: Int,
    /** Off the form, but still naming the answers it has already collected. */
    val retired: Boolean
)

/** One answer, with the question it belongs to. */
data class CheckInAnswer(
    val fieldId: String,
    val label: String,
    val kind: CheckInKind,
    val value: String
) {
    /** The value as a person reads it — "Yes", "4 of 5", or just the text. */
    val display: String get() = CheckIns.display(kind, value)
}

/**
 * One day's check-in.
 *
 * [answers] carries only what was actually recorded, in form order, and a question that was left
 * alone is simply absent — the distinction the answers table exists to keep.
 */
data class CheckIn(
    val id: String,
    val personId: String,
    val day: LocalDate,
    val answers: List<CheckInAnswer>,
    val updatedAt: Long
) {
    /** The one-line version, for the person's page and the history list. */
    val summary: String get() = CheckIns.summarise(answers.map { it.label to it.display })
}
