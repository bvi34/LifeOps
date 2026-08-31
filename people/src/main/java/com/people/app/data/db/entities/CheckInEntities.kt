package com.people.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The daily check-in's tables: the questions, the days, and the answers.
 *
 * Three tables rather than one row of columns per day, because the form is the user's rather than
 * ours — a column per question would mean a migration every time somebody added one. The shape is
 * the ordinary one for user-defined forms, and its two costs are paid deliberately: values are all
 * `TEXT` (the kind says how to read them — see `logic/CheckIns`), and a day is a join.
 */

/**
 * One question on one person's form.
 *
 * Hung off the person, not shared: the questions worth asking about a child are not the ones worth
 * asking about a parent, and a household-wide form would be the union of everybody's, mostly blank.
 *
 * [retired] is why there is no delete for a question that has ever been answered. Removing it from
 * the form must not remove what it recorded — six months of "Lunch" is the *point* of keeping a
 * daily log — so the row stays, out of the form and still naming its own answers. A question nobody
 * ever answered is deleted outright; there is nothing to orphan and no reason to keep the clutter.
 *
 * [position] orders the form. It is a plain integer resequenced on every move rather than a float or
 * a linked list: a form is a handful of questions edited by one person on one device, and the
 * cleverer schemes exist for orderings that several writers fight over.
 */
@Entity(
    tableName = "check_in_fields",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("personId")]
)
data class CheckInFieldEntity(
    @PrimaryKey val id: String,
    val personId: String,
    val label: String,
    /** A [com.people.app.logic.CheckInKind] key. */
    val kind: String,
    /** Newline-separated options, for the one kind that has them. Null for every other. */
    val options: String?,
    val position: Int,
    val retired: Boolean,
    val createdAt: Long
)

/**
 * One day's check-in for one person.
 *
 * `(personId, day)` is uniquely indexed, which is the table's whole rule: a day is checked in once,
 * and opening it again *edits* that day rather than adding a second version of it. A log that let
 * the same evening be recorded twice would make every count downstream — streaks, "have I done
 * today?", how often something happened — a question about rows instead of about days.
 *
 * [day] is an ISO `yyyy-MM-dd` string rather than an instant, for the reason the birth date is: the
 * answer to "was Tuesday recorded?" must not change when somebody flies to another timezone.
 */
@Entity(
    tableName = "check_ins",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("personId"), Index(value = ["personId", "day"], unique = true), Index("day")]
)
data class CheckInEntity(
    @PrimaryKey val id: String,
    val personId: String,
    /** ISO `yyyy-MM-dd`. A day, not an instant. */
    val day: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One answer: what this day said to that question.
 *
 * Keyed by `(checkInId, fieldId)` — one answer per question per day, so saving a day again replaces
 * its answers rather than appending a second set.
 *
 * A row exists only when there is something to record. An unanswered question stores nothing at all,
 * which keeps "they didn't say" and "they said nothing happened" different facts; `CheckIns.clean`
 * is what turns a blank control into an absent row.
 */
@Entity(
    tableName = "check_in_answers",
    primaryKeys = ["checkInId", "fieldId"],
    foreignKeys = [
        ForeignKey(
            entity = CheckInEntity::class,
            parentColumns = ["id"],
            childColumns = ["checkInId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CheckInFieldEntity::class,
            parentColumns = ["id"],
            childColumns = ["fieldId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("checkInId"), Index("fieldId")]
)
data class CheckInAnswerEntity(
    val checkInId: String,
    val fieldId: String,
    val value: String
)
