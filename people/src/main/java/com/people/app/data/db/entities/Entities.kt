package com.people.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * People's tables.
 *
 * The column that makes this app different from a list of names is [PersonEntity.personKey]: the
 * identity a person keeps across every peer on the sync seam, as distinct from [PersonEntity.id],
 * which is only ever this database's row id. Two apps will independently invent a row for the same
 * human; the key is what lets them agree afterwards without either one having to win.
 */

/**
 * One person in the household.
 *
 * [syncVersion] is this peer's monotonic stamp: every local edit bumps it, and the outbound envelope
 * is simply "every row above what the other peer acknowledged". That makes the outbox *derived from
 * the rows* rather than a separate durable queue — the same trick Citation uses to survive a restart
 * without losing unacked packets, arrived at from the other direction.
 *
 * [updatedAt] is the merge clock (see `sync/PersonMerge`) and is deliberately distinct from
 * [syncVersion]: one orders edits *between* peers, the other orders them *within* one.
 */
@Entity(
    tableName = "people",
    indices = [Index("personKey", unique = true), Index("name"), Index("syncVersion")]
)
data class PersonEntity(
    @PrimaryKey val id: String,
    val personKey: String,
    val name: String,
    /** Free text — "Me", "Daughter", "Mum". Not an enum; households don't fit one. */
    val relationship: String?,
    /** ISO `yyyy-MM-dd`. A birth date is a calendar fact, not an instant. */
    val birthDate: String?,
    val email: String?,
    val phone: String?,
    /** The freeform "likes hiking, hates crowds" note that rides the sync seam. */
    val note: String?,
    val colorArgb: Long,
    val archived: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val syncVersion: Long
)

/** One timeline note about a person — append-only, and local to People rather than synced. */
@Entity(
    tableName = "person_notes",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("personId"), Index("createdAt")]
)
data class PersonNoteEntity(
    @PrimaryKey val id: String,
    val personId: String,
    val content: String,
    val createdAt: Long
)

/**
 * A date that comes round again: a birthday, an anniversary, the annual check-up.
 *
 * [monthDay] is `MM-dd` and [year] is the year it first happened, when known — stored apart because
 * "every 2 April" and "2 April 2019" are different facts, and only the second one can tell you the
 * age somebody is turning. See `logic/ImportantDates`.
 */
@Entity(
    tableName = "important_dates",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("personId"), Index("monthDay")]
)
data class ImportantDateEntity(
    @PrimaryKey val id: String,
    val personId: String,
    val label: String,
    val kind: String,
    val monthDay: String,
    val year: Int?,
    val note: String?,
    val createdAt: Long
)
