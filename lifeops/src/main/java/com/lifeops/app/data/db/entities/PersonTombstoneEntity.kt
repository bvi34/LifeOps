package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A person LifeOps has deleted, kept only long enough to say so across the People sync seam.
 *
 * Deleting a person in LifeOps is a real delete — `task_people`, `busy_block_people`, `busy_blocks`
 * and `milestones` all cascade off it, and that behaviour is older than the seam. The problem a real
 * delete creates for a replicated roster is that the row which would have carried the news is the
 * very thing that went away, so the peer, still holding its own copy, hands the person straight back
 * on the next round.
 *
 * This is the news, and nothing more: the key, the name (for a legible log), and a
 * [syncVersion] drawn from the same counter the people rows use, so a withdrawal takes its turn in
 * the outbound envelope like any other change. It is deliberately *not* a soft-delete of the person
 * — no preferences, no notes, no links. The peer that receives it archives rather than deletes, so
 * the household's record of that person still exists somewhere even after this side has forgotten
 * them.
 */
@Entity(tableName = "person_tombstones", indices = [Index("syncVersion")])
data class PersonTombstoneEntity(
    @PrimaryKey val personKey: String,
    val name: String,
    val deletedAt: Long,
    val syncVersion: Long
)
