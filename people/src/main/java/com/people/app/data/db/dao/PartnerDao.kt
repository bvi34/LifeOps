package com.people.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.people.app.data.db.entities.PartnerEventEntity
import com.people.app.data.db.entities.PartnerLinkEntity
import com.people.app.data.db.entities.PartnerOutboxEntity
import com.people.app.data.db.entities.PartnerTakenEntity
import com.people.app.data.db.entities.PartnerWeekTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PartnerDao {

    // --- links ---

    @Query("SELECT * FROM partner_links WHERE personId = :personId LIMIT 1")
    fun observeLinkFor(personId: String): Flow<PartnerLinkEntity?>

    @Query("SELECT * FROM partner_links WHERE personId = :personId LIMIT 1")
    suspend fun linkFor(personId: String): PartnerLinkEntity?

    @Query("SELECT * FROM partner_links WHERE id = :id")
    suspend fun link(id: String): PartnerLinkEntity?

    /**
     * Whether this partner install is already linked to somebody *else* here — the check behind the
     * unique index, asked before the insert so a second scan of the same code is an explanation
     * rather than a constraint violation.
     */
    @Query("SELECT * FROM partner_links WHERE partnerInstanceId = :instanceId AND personId != :exceptPersonId LIMIT 1")
    suspend fun linkClaiming(instanceId: String, exceptPersonId: String): PartnerLinkEntity?

    /** The links with both halves of a secret — the only ones a round has anything to do with. */
    @Query("SELECT * FROM partner_links WHERE partnerInstanceId IS NOT NULL")
    suspend fun scannedLinks(): List<PartnerLinkEntity>

    @Upsert
    suspend fun upsertLink(link: PartnerLinkEntity)

    @Query("DELETE FROM partner_links WHERE id = :id")
    suspend fun deleteLink(id: String)

    // --- the mirror of their week ---

    @Query("SELECT * FROM partner_week_tasks WHERE linkId = :linkId")
    fun observeWeek(linkId: String): Flow<List<PartnerWeekTaskEntity>>

    @Query("SELECT * FROM partner_week_tasks WHERE linkId = :linkId")
    suspend fun week(linkId: String): List<PartnerWeekTaskEntity>

    @Upsert
    suspend fun upsertWeekTasks(tasks: List<PartnerWeekTaskEntity>)

    /**
     * Replace the mirror wholesale. The partner owns their week and publishes it whole, so anything
     * we hold that is not in what they just sent is a task they have removed — there is no third
     * possibility to be careful about.
     */
    @Query("DELETE FROM partner_week_tasks WHERE linkId = :linkId")
    suspend fun clearWeek(linkId: String)

    // --- what we have added to their week ---

    @Query("SELECT * FROM partner_outbox WHERE linkId = :linkId ORDER BY createdAt")
    fun observeOutbox(linkId: String): Flow<List<PartnerOutboxEntity>>

    @Query("SELECT * FROM partner_outbox WHERE linkId = :linkId ORDER BY createdAt")
    suspend fun outbox(linkId: String): List<PartnerOutboxEntity>

    @Upsert
    suspend fun upsertOutbox(entry: PartnerOutboxEntity)

    @Query("DELETE FROM partner_outbox WHERE id = :id")
    suspend fun deleteOutbox(id: String)

    @Query("DELETE FROM partner_outbox WHERE linkId = :linkId AND id IN (:ids)")
    suspend fun deleteOutbox(linkId: String, ids: List<String>)

    @Query("DELETE FROM partner_outbox WHERE linkId = :linkId")
    suspend fun clearOutbox(linkId: String)

    // --- what we have taken from theirs ---

    @Query("SELECT contributionId FROM partner_taken WHERE linkId = :linkId")
    suspend fun takenIds(linkId: String): List<String>

    /**
     * The rows themselves, not just the ids — what publishing needs, so a task that began as this
     * partner's contribution can be stamped with the id they know it by. That stamp is how they
     * recognise their own suggestion arriving back on our week, and it is kept here rather than on
     * the LifeOps task because LifeOps has no business knowing this seam exists.
     */
    @Query("SELECT * FROM partner_taken WHERE linkId = :linkId AND localTaskId IS NOT NULL")
    suspend fun takenFor(linkId: String): List<PartnerTakenEntity>

    @Upsert
    suspend fun upsertTaken(taken: PartnerTakenEntity)

    // --- what to tell the user ---

    /**
     * The unread changes, read once rather than observed.
     *
     * A snapshot, deliberately: the screen shows these and then marks them seen, and an observed
     * flow would empty the panel the instant it did — the reader would watch the news they came for
     * disappear as they arrived.
     */
    @Query("SELECT * FROM partner_events WHERE linkId = :linkId AND seen = 0 ORDER BY at DESC")
    suspend fun unseenEvents(linkId: String): List<PartnerEventEntity>

    @Query("SELECT linkId, COUNT(*) AS unseen FROM partner_events WHERE seen = 0 GROUP BY linkId")
    fun observeUnseenByLink(): Flow<List<UnseenCount>>

    /**
     * The same tally keyed by person, for the roster.
     *
     * A partner's changes arrive while the app is closed, so the only way anybody learns about them
     * is a mark on the list they open first. Keyed by person rather than by link because that is
     * what the roster is a list of.
     */
    @Query(
        "SELECT l.personId AS personId, COUNT(e.id) AS unseen FROM partner_links l " +
            "JOIN partner_events e ON e.linkId = l.id AND e.seen = 0 GROUP BY l.personId"
    )
    fun observeUnseenByPerson(): Flow<List<UnseenForPerson>>

    @Upsert
    suspend fun upsertEvents(events: List<PartnerEventEntity>)

    @Query("UPDATE partner_events SET seen = 1 WHERE linkId = :linkId")
    suspend fun markSeen(linkId: String)

    @Query("DELETE FROM partner_events WHERE linkId = :linkId")
    suspend fun clearEvents(linkId: String)

    /**
     * Keep the log short. What a partner did three weeks ago is not news anybody is waiting for, and
     * an unbounded table on a seam that runs every app open is a slow leak.
     */
    @Query(
        "DELETE FROM partner_events WHERE linkId = :linkId AND seen = 1 AND id NOT IN " +
            "(SELECT id FROM partner_events WHERE linkId = :linkId ORDER BY at DESC LIMIT :keep)"
    )
    suspend fun trimEvents(linkId: String, keep: Int)
}

/** One link's unread tally. */
data class UnseenCount(val linkId: String, val unseen: Int)

/** One person's unread tally, for the roster's badge. */
data class UnseenForPerson(val personId: String, val unseen: Int)
