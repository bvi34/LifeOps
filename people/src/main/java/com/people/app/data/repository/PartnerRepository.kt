package com.people.app.data.repository

import com.people.app.data.db.dao.PartnerDao
import com.people.app.data.db.entities.PartnerEventEntity
import com.people.app.data.db.entities.PartnerLinkEntity
import com.people.app.data.db.entities.PartnerOutboxEntity
import com.people.app.data.db.entities.PartnerTakenEntity
import com.people.app.data.db.entities.PartnerWeekTaskEntity
import com.people.app.data.model.PartnerEvent
import com.people.app.data.model.PartnerEventKind
import com.people.app.data.model.PartnerLink
import com.people.app.data.model.PartnerLinkState
import com.people.app.data.model.PartnerPendingTask
import com.people.app.data.model.PartnerTask
import com.people.app.partner.Contribution
import com.people.app.partner.PairSecret
import com.people.app.partner.PartnerInvite
import com.people.app.partner.PartnerSyncEngine
import com.people.app.partner.PartnerWeekDiff
import com.people.app.partner.SharedTask
import com.people.app.partner.SharedWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * People's store for the partner seam.
 *
 * The rule this class exists to hold: **a partner's week goes into `partner_week_tasks` and
 * nowhere else.** Nothing here can reach a planner — there is no reference to one in the file. The
 * single payload that does reach one, a contribution somebody deliberately added to their partner's
 * week, is handed to [com.people.app.partner.HouseholdWeek] by the sync service, which is a separate
 * class on purpose: the code that *stores a mirror* and the code that can *create a task* should not
 * be the same code.
 */
class PartnerRepository(private val dao: PartnerDao) {

    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString()

    // --- links ---

    fun observeLink(personId: String): Flow<PartnerLink?> =
        dao.observeLinkFor(personId).withUnseenCount()

    suspend fun link(personId: String): PartnerLinkEntity? = dao.linkFor(personId)

    /** Every pairing, for the roster's account of the seam as a whole. */
    fun observeLinks(): Flow<List<PartnerLink>> =
        dao.observeLinks().combine(dao.observeUnseenByLink()) { links, counts ->
            links.map { link ->
                link.toModel(counts.firstOrNull { it.linkId == link.id }?.unseen ?: 0)
            }
        }

    suspend fun scannedLinks(): List<PartnerLinkEntity> = dao.scannedLinks()

    /**
     * The link to show this person's QR code from, minting our half of the secret if this is the
     * first time.
     *
     * Once minted, [PartnerLinkEntity.mySecret] is never re-rolled here — only [resetPairing] does
     * that, and only when a person asks. A code re-generated behind their back would invalidate the
     * half a partner has already stored, and the failure would show up later as a token mismatch
     * neither person could explain.
     */
    suspend fun ensureLink(personId: String, partnerNameHint: String): PartnerLinkEntity {
        dao.linkFor(personId)?.let { return it }
        val link = PartnerLinkEntity(
            id = newId(),
            personId = personId,
            partnerInstanceId = null,
            partnerName = partnerNameHint,
            partnerPersonKey = null,
            mySecret = PairSecret.mint(),
            partnerSecret = "",
            confirmedAt = null,
            lastSyncAt = null,
            lastRejection = null,
            lastSeenAt = null,
            createdAt = now()
        )
        dao.upsertLink(link)
        return link
    }

    /** Why a scanned code could not be accepted. Each is something the person can act on. */
    enum class ScanRefusal {
        /** The code is ours: somebody scanned their own screen. */
        OUR_OWN_CODE,

        /** That install is already paired with somebody else in this directory. */
        ALREADY_PAIRED_ELSEWHERE
    }

    sealed interface ScanResult {
        data class Paired(val link: PartnerLinkEntity) : ScanResult
        data class Refused(val reason: ScanRefusal, val existingPartnerName: String? = null) : ScanResult
    }

    /**
     * Take the other half of the pairing from a scanned code.
     *
     * Re-scanning an existing pairing is deliberately allowed and simply overwrites their half: it
     * is what a person does when a link has gone stale (their app was reinstalled, say), and the
     * alternative — refusing because a link already exists — leaves them with a broken pairing and
     * no way to mend it short of deleting the person.
     */
    suspend fun acceptScan(personId: String, invite: PartnerInvite, ourInstanceId: String): ScanResult {
        if (invite.instanceId == ourInstanceId) return ScanResult.Refused(ScanRefusal.OUR_OWN_CODE)

        dao.linkClaiming(invite.instanceId, exceptPersonId = personId)?.let {
            return ScanResult.Refused(ScanRefusal.ALREADY_PAIRED_ELSEWHERE, it.partnerName)
        }

        val existing = ensureLink(personId, invite.displayName)
        // Their half changing means the pairing is new: whatever we mirrored under the old one was
        // agreed with a different device, so it is dropped rather than re-attributed.
        if (existing.partnerSecret.isNotBlank() && existing.partnerSecret != invite.secret) {
            clearSharedState(existing.id)
        }
        val updated = existing.copy(
            partnerInstanceId = invite.instanceId,
            partnerName = invite.displayName.ifBlank { existing.partnerName },
            partnerPersonKey = invite.personKey.ifBlank { null },
            partnerSecret = invite.secret,
            confirmedAt = null,
            lastRejection = null
        )
        dao.upsertLink(updated)
        return ScanResult.Paired(updated)
    }

    /** Note a round's outcome against the link. */
    suspend fun recordRound(linkId: String, confirmed: Boolean, rejection: String?) {
        val link = dao.link(linkId) ?: return
        dao.upsertLink(
            link.copy(
                lastSyncAt = now(),
                lastRejection = rejection,
                confirmedAt = link.confirmedAt ?: now().takeIf { confirmed }
            )
        )
    }

    /**
     * Break the pairing off: the link, their week, and everything we were going to send them.
     *
     * [PartnerTakenEntity] rows are the exception and stay. They are not the partner's data — they
     * are the record of decisions this household already made about tasks on its own week, and
     * dropping them would let a re-pairing re-offer every contribution the person ever declined.
     */
    suspend fun unlink(linkId: String) {
        clearSharedState(linkId)
        dao.deleteLink(linkId)
    }

    /** Re-roll our half of the secret, so a fresh code can be shown after a pairing goes stale. */
    suspend fun resetPairing(linkId: String) {
        val link = dao.link(linkId) ?: return
        clearSharedState(linkId)
        dao.upsertLink(
            link.copy(
                partnerInstanceId = null,
                partnerSecret = "",
                partnerPersonKey = null,
                mySecret = PairSecret.mint(),
                confirmedAt = null,
                lastRejection = null,
                lastSyncAt = null
            )
        )
    }

    private suspend fun clearSharedState(linkId: String) {
        dao.clearWeek(linkId)
        dao.clearOutbox(linkId)
        dao.clearEvents(linkId)
    }

    // --- the mirror of their week ---

    fun observeWeek(linkId: String): Flow<List<PartnerTask>> =
        dao.observeWeek(linkId).map { rows ->
            PartnerWeekDiff.sorted(rows.map { it.toShared() }).map { task ->
                PartnerTask(
                    taskId = task.taskId,
                    title = task.title,
                    dueDate = task.dueDate,
                    done = task.done,
                    addedByUs = task.fromContribution != null
                )
            }
        }

    suspend fun mirror(linkId: String): SharedWeek {
        val rows = dao.week(linkId)
        return SharedWeek(
            weekStart = rows.firstOrNull()?.weekStart.orEmpty(),
            weekEnd = "",
            tasks = rows.map { it.toShared() }
        )
    }

    /** Replace the mirror with what they just published. See [PartnerDao.clearWeek]. */
    suspend fun replaceMirror(linkId: String, week: SharedWeek) {
        dao.clearWeek(linkId)
        dao.upsertWeekTasks(
            week.tasks.map { task ->
                PartnerWeekTaskEntity(
                    linkId = linkId,
                    taskId = task.taskId,
                    weekStart = week.weekStart,
                    title = task.title,
                    dueDate = task.dueDate,
                    done = task.done,
                    fromContribution = task.fromContribution
                )
            }
        )
    }

    // --- what we add to their week ---

    fun observePending(linkId: String): Flow<List<PartnerPendingTask>> =
        dao.observeOutbox(linkId).map { rows ->
            rows.map { PartnerPendingTask(it.id, it.title, it.dueDate) }
        }

    suspend fun addToPartnerWeek(linkId: String, title: String, dueDate: String?): String {
        val id = newId()
        dao.upsertOutbox(
            PartnerOutboxEntity(
                id = id,
                linkId = linkId,
                title = title.trim(),
                dueDate = dueDate?.takeIf { it.isNotBlank() },
                createdAt = now()
            )
        )
        return id
    }

    /** Withdraw something we added before their app has taken it. */
    suspend fun withdrawPending(id: String) = dao.deleteOutbox(id)

    suspend fun pendingContributions(linkId: String): List<Contribution> =
        dao.outbox(linkId).map { Contribution(it.id, it.title, it.dueDate, it.createdAt) }

    /**
     * Retire the contributions that have landed, recognised by the stamp the partner's app puts on
     * the task it created. Until one comes back, it keeps being sent — which is what carries a
     * contribution across a partner who does not open their app for a week.
     */
    suspend fun retireLandedContributions(linkId: String, week: SharedWeek) {
        val landed = week.tasks.mapNotNull { it.fromContribution }
        if (landed.isNotEmpty()) dao.deleteOutbox(linkId, landed)
    }

    // --- what we take from theirs ---

    suspend fun takenContributionIds(linkId: String): Set<String> = dao.takenIds(linkId).toSet()

    /**
     * `localTaskId -> contributionId` for tasks on our week that began as this partner's suggestion,
     * so publishing can stamp them and their app can recognise its own contribution having landed.
     */
    suspend fun stamps(linkId: String): Map<String, String> =
        dao.takenFor(linkId).mapNotNull { row -> row.localTaskId?.let { it to row.contributionId } }.toMap()

    /** Record that a contribution has been created on this household's week — once and for good. */
    suspend fun recordTaken(linkId: String, contribution: Contribution, localTaskId: String?) {
        dao.upsertTaken(
            PartnerTakenEntity(
                contributionId = contribution.id,
                linkId = linkId,
                localTaskId = localTaskId,
                title = contribution.title,
                takenAt = now()
            )
        )
    }

    // --- what to tell the user ---

    /** The unread changes as a snapshot, for a screen that is about to mark them read. */
    suspend fun unseenEvents(linkId: String): List<PartnerEvent> =
        dao.unseenEvents(linkId).map { it.toModel() }

    /** `personId -> unread changes`, for the roster's badges. */
    fun observeUnseenByPerson(): Flow<Map<String, Int>> =
        dao.observeUnseenByPerson().map { rows -> rows.associate { it.personId to it.unseen } }

    suspend fun recordEvents(linkId: String, events: List<Pair<PartnerEventKind, String>>) {
        if (events.isEmpty()) return
        val at = now()
        dao.upsertEvents(
            events.map { (kind, title) ->
                PartnerEventEntity(
                    id = newId(),
                    linkId = linkId,
                    kind = kind.key,
                    title = title,
                    at = at,
                    seen = false
                )
            }
        )
        dao.trimEvents(linkId, keep = EVENT_LOG_LIMIT)
    }

    /** Called when the partner's week is opened: everything waiting has now been told. */
    suspend fun markSeen(linkId: String) {
        dao.markSeen(linkId)
        dao.link(linkId)?.let { dao.upsertLink(it.copy(lastSeenAt = now())) }
    }

    // --- mapping ---

    private fun PartnerEventEntity.toModel() = PartnerEvent(
        id = id,
        kind = PartnerEventKind.fromKey(kind),
        title = title,
        at = at,
        seen = seen
    )

    private fun PartnerWeekTaskEntity.toShared() = SharedTask(
        taskId = taskId,
        title = title,
        dueDate = dueDate,
        done = done,
        fromContribution = fromContribution
    )

    /**
     * Pair each link with its unread tally, so the person screen can show one object rather than
     * juggling two flows that update at different moments.
     */
    private fun Flow<PartnerLinkEntity?>.withUnseenCount(): Flow<PartnerLink?> =
        combine(dao.observeUnseenByLink()) { link, counts ->
            link?.toModel(counts.firstOrNull { it.linkId == link.id }?.unseen ?: 0)
        }

    companion object {
        /** How many of a partner's changes are worth keeping once they have been read. */
        const val EVENT_LOG_LIMIT = 60
    }
}

fun PartnerLinkEntity.toModel(unseen: Int = 0) = PartnerLink(
    id = id,
    personId = personId,
    partnerInstanceId = partnerInstanceId.orEmpty(),
    partnerName = partnerName,
    state = when {
        confirmedAt != null -> PartnerLinkState.LINKED
        partnerSecret.isNotBlank() -> PartnerLinkState.AWAITING_THEM
        else -> PartnerLinkState.AWAITING_SCAN
    },
    lastSyncAt = lastSyncAt,
    lastRejection = lastRejection,
    unseenChanges = unseen
)

/** This link as the engine sees it — the two halves of the secret, and who they belong to. */
fun PartnerLinkEntity.toEngineLink() = PartnerSyncEngine.Link(
    // Null only on a link no round ever looks at (see PartnerDao.scannedLinks); the engine's own
    // `scanned` check refuses it either way, so an empty id here can never be acted on.
    partnerInstanceId = partnerInstanceId.orEmpty(),
    partnerName = partnerName,
    mySecret = mySecret,
    partnerSecret = partnerSecret
)
