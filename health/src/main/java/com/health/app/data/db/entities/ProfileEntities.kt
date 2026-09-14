package com.health.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Who Health keeps records for, and the tombstone left when one is deleted.
 */

@Entity(tableName = "profiles", indices = [Index("name"), Index("syncVersion")])
data class ProfileEntity(
    @PrimaryKey val id: String,
    /**
     * The identity this person keeps across the People sync seam, as distinct from [id], which is
     * only this database's row id. Null until the seam stamps one.
     */
    val personKey: String? = null,
    /** Bumped by every local edit, left alone by every write that arrived over the seam. */
    val syncVersion: Long = 0L,
    val name: String,
    /** Free text — "Me", "Daughter", "Mum". Not an enum; households don't fit one. */
    val relationship: String?,
    /** ISO `yyyy-MM-dd`, or null. Drives the age-aware fever rules, so it is worth asking for. */
    val birthDate: String?,
    val colorArgb: Long,
    val baselineTempC: Double?,
    /**
     * Allergies, conditions, the doctor's number — whatever you'd want in front of you at 3am.
     *
     * **Never published over the People seam**, even though People has a field of the same name.
     * They are not the same field: People's note is "likes hiking, hates crowds", and this one is
     * medical. Mapping one onto the other would quietly copy a person's conditions into the
     * household directory and from there into LifeOps — which is exactly the kind of leak a shared
     * wire makes easy and nobody asked for. See `HealthRepository.toPacket`.
     */
    val notes: String?,
    /**
     * Whether the directory counts this person as a **household member** — which is what decides
     * whether Health grows a profile for them at all (see `HealthSyncService`).
     *
     * Health holds a copy rather than deriving it from "do I have a profile?", because the two can
     * legitimately disagree: un-ticking somebody in People stops them being *offered* to Health and
     * never deletes what Health already recorded, so a profile can outlive the flag. Storing the
     * answer is also what stops Health's own next packet flipping the directory's tick back on.
     */
    val household: Boolean = true,
    val sortOrder: Int,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A profile Health has removed, kept only long enough to say so across the People sync seam.
 *
 * Removing somebody in Health means "stop tracking their health", not "remove them from the
 * household" — so unlike LifeOps' tombstone this one does **not** publish a withdrawal. It publishes
 * `household = false`: the directory un-ticks them, and the next round stops offering them back.
 *
 * Without it the removal simply doesn't stick. Health's profile is created *from* the directory's
 * tick, so the first time that person is edited in People their packet comes round again above the
 * cursor and Health dutifully re-creates the profile that was just deleted. A tombstone is the only
 * thing left to speak for a row that has gone.
 */
@Entity(tableName = "profile_tombstones", indices = [Index("syncVersion")])
data class ProfileTombstoneEntity(
    @PrimaryKey val personKey: String,
    val name: String,
    val removedAt: Long,
    val syncVersion: Long
)
