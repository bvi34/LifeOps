package com.people.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The partner seam's tables.
 *
 * All four exist so that People can hold a partner's week **without LifeOps holding it**. That is
 * the constraint the whole feature is built around: a partner's tasks are somebody else's week, and
 * a copy of them in `people.db` is a view, whereas a copy of them in LifeOps' `tasks` table would be
 * your planner quietly acquiring another household's workload. So the mirror lives here, beside the
 * directory, and the only row that ever crosses into LifeOps is a task a person deliberately added
 * to their partner's week — tracked by [PartnerTakenEntity] so it crosses exactly once.
 */

/**
 * One pairing, hung off the person it is with.
 *
 * The two secrets are the pairing. [mySecret] is minted here and shown in this person's QR code;
 * [partnerSecret] is what their code carried, and is empty until somebody has actually scanned it.
 * Neither is a password: apart, they authorise nothing, and only together do they make the token
 * that gates the seam (see `partner/PairSecret`). That is what makes the connection two-way by
 * construction rather than by a rule somebody has to enforce — a device that was never scanned holds
 * one half and cannot complete anything.
 *
 * [partnerInstanceId] is uniquely indexed, so one partner install cannot be linked to two people in
 * this directory. Two rows claiming the same instance would race to mirror the same envelope, and
 * the second person would show the first one's week.
 *
 * It is **null** — not empty — before their code has been scanned, and that is what makes the unique
 * index usable: SQLite treats nulls as distinct, so any number of people can have a code shown and
 * nobody scanned yet, while two rows naming the same real instance are still refused. An empty
 * string would have made the second unpaired person a constraint violation.
 */
@Entity(
    tableName = "partner_links",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("personId"), Index(value = ["partnerInstanceId"], unique = true)]
)
data class PartnerLinkEntity(
    @PrimaryKey val id: String,
    val personId: String,
    /**
     * The partner's install, or null until their code is scanned — a link with only our half is a
     * code we have shown and nobody has photographed yet, which is a real and common state.
     */
    val partnerInstanceId: String?,
    val partnerName: String,
    /**
     * The key *their* directory files us under, as their code reported it. Provenance only — never
     * used to bind anything, because People's keys are agreed inside one install and mean nothing
     * across a pairing.
     */
    val partnerPersonKey: String?,
    val mySecret: String,
    val partnerSecret: String,
    /** When their envelope first came back bearing our pairing token — the handshake completing. */
    val confirmedAt: Long?,
    val lastSyncAt: Long?,
    /** The last round's [com.people.app.partner.PartnerSyncEngine.Rejection], or null when it worked. */
    val lastRejection: String?,
    /** When the user last opened this partner's week — what "changes since your last sync" is measured from. */
    val lastSeenAt: Long?,
    val createdAt: Long
)

/**
 * One task on a partner's week, as we last saw it.
 *
 * Keyed by `(linkId, taskId)` rather than by the task id alone: the id is minted by the partner's
 * install, and two different partners have no reason — and no way — to avoid colliding.
 *
 * There is no `updatedAt` and no version here, unlike every other synced row in this suite. There
 * does not need to be one: the partner publishes their whole week every round and is its only
 * author, so this table is a replacement rather than a merge, and a task's absence is as
 * authoritative as its presence.
 */
@Entity(
    tableName = "partner_week_tasks",
    primaryKeys = ["linkId", "taskId"],
    indices = [Index("linkId")]
)
data class PartnerWeekTaskEntity(
    val linkId: String,
    val taskId: String,
    val weekStart: String,
    val title: String,
    val dueDate: String?,
    val done: Boolean,
    /** Set when this task began as somebody's contribution — how we recognise our own, landed. */
    val fromContribution: String?
)

/**
 * A task this person has added to a partner's week and that has not landed there yet.
 *
 * It is deliberately *not* stored as a row in [PartnerWeekTaskEntity] with a pending flag. The
 * mirror is a copy of what the partner published, and writing our own optimistic row into it would
 * make the one table that is supposed to be "what they have" into "what they have, plus what we
 * hope". Kept apart, the screen can show both and be honest about which is which.
 *
 * The row is deleted once the task comes back in the partner's published week, recognised by
 * [PartnerWeekTaskEntity.fromContribution]. Until then it is republished every round, which is what
 * makes a contribution survive a partner who is offline for a week.
 */
@Entity(
    tableName = "partner_outbox",
    indices = [Index("linkId")]
)
data class PartnerOutboxEntity(
    @PrimaryKey val id: String,
    val linkId: String,
    val title: String,
    val dueDate: String?,
    val createdAt: Long
)

/**
 * A contribution from a partner that has been created on this household's LifeOps week.
 *
 * This table is the reason a partner cannot fill your planner by leaving their app open. It is
 * consulted before anything is written to LifeOps and appended to immediately after, and the row is
 * kept **for good** — including when the task it made is later deleted. A contribution that was
 * taken and then binned was decided about; offering it again on the next open would be the app
 * arguing with the person who binned it.
 */
@Entity(
    tableName = "partner_taken",
    indices = [Index("linkId")]
)
data class PartnerTakenEntity(
    @PrimaryKey val contributionId: String,
    val linkId: String,
    /** The LifeOps task it became, when LifeOps was there to make one. */
    val localTaskId: String?,
    val title: String,
    val takenAt: Long
)

/**
 * Something a partner did, waiting to be told to the user.
 *
 * A round happens when the app opens, which is precisely when nobody is looking at this person's
 * screen — so what changed has to be *kept* rather than shown. Rows are marked seen when the week is
 * opened, and that is what makes "changes since your last sync" a real answer instead of a diff
 * against whatever happened to be on screen.
 */
@Entity(
    tableName = "partner_events",
    indices = [Index("linkId"), Index("seen")]
)
data class PartnerEventEntity(
    @PrimaryKey val id: String,
    val linkId: String,
    /** A [com.people.app.data.model.PartnerEventKind] key. */
    val kind: String,
    val title: String,
    val at: Long,
    val seen: Boolean
)
