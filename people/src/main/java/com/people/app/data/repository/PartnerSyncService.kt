package com.people.app.data.repository

import com.people.app.data.model.PartnerEventKind
import com.people.app.data.model.PartnerSyncStatus
import com.people.app.partner.HouseholdWeek
import com.people.app.partner.HouseholdWeeks
import com.people.app.partner.PartnerEnvelopeStore
import com.people.app.partner.PartnerShare
import com.people.app.partner.PartnerSyncEngine
import com.people.app.partner.SharedWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives one partner round over the exchange folder.
 *
 * The order of operations is the whole class, and each step is where it is for a reason:
 *
 *  1. **Read and verify** every partner's envelope. Nothing is believed before the pairing token is
 *     checked — see [PartnerSyncEngine].
 *  2. **Mirror** their week into People's own tables. This never touches LifeOps.
 *  3. **Take** the contributions they addressed to us: *these*, and only these, become tasks on this
 *     household's week. Each is recorded as taken before anything else happens, so a crash between
 *     creating the task and finishing the round cannot make it land twice.
 *  4. **Record** what changed, so the person can be told when they next look.
 *  5. **Publish** our own week, freshly read, with whatever we have added to theirs.
 *
 * Rounds are serialised by a mutex, as the People seam's are. Idempotence makes a *repeated* round
 * free but says nothing about two *overlapping* ones: both would read the same `partner_taken` set,
 * both would find a contribution untaken, and the task would be created twice. Opening the app while
 * a contribution is being published is exactly that race.
 */
class PartnerSyncService(
    private val repository: PartnerRepository,
    private val syncDir: File,
    private val instanceId: () -> String,
    private val displayName: () -> String,
    /**
     * The door onto this household's own week, resolved per round rather than held.
     *
     * LifeOps registers its adapter during its own startup, which may happen after People has built
     * its container, so a bridge captured once at construction could be permanently null in a host
     * where it would have worked. Resolving each round also keeps this class testable: the whole
     * thing runs against a fake with no Android and no planner in sight.
     */
    private val householdWeek: () -> HouseholdWeek? = { HouseholdWeeks.current() },
    private val markRoundAt: (Long) -> Unit = {}
) {

    private val roundLock = Mutex()

    suspend fun sync(): PartnerSyncStatus = withContext(Dispatchers.IO) {
        roundLock.withLock { round() }
    }

    private suspend fun round(): PartnerSyncStatus {
        val links = repository.scannedLinks()
        val at = System.currentTimeMillis()
        markRoundAt(at)

        // Built before the no-links case is answered, and that ordering is the point: constructing
        // the store is what creates the exchange folder, and minting the engine is what mints this
        // install's instance id. A household with nobody paired yet still needs both to exist —
        // otherwise there is nothing on disk to point a folder-sharing tool at, and no identity to
        // show, until after a pairing that cannot be made without them.
        val store = PartnerEnvelopeStore(syncDir)
        val engine = PartnerSyncEngine(instanceId(), displayName())

        if (links.isEmpty()) {
            // An envelope with no shares: it names this instance and tells a partner's device
            // nothing else. Published anyway, because "set up partner sync" has to leave something
            // behind — a folder and our own file in it — for the person who has just pressed it.
            store.write(engine.buildOutbound(emptyList(), at))
            return PartnerSyncStatus(ranAt = at, linksSynced = 0, changes = 0, takenOntoOurWeek = 0)
        }

        val week = householdWeek()

        // Read once, not once per link: it is the same week for every partner, and a round with
        // three links should not run the same query three times.
        //
        // With no planner in this host there is no week to publish and none to compare against, so
        // partners get an empty one and nothing is mirrored — but their contributions still land
        // below, because those are addressed to us rather than dated by them.
        var ourWeek = week?.current() ?: SharedWeek("", "")

        var changes = 0
        var taken = 0
        var failure: String? = null
        val shares = ArrayList<PartnerShare>(links.size)

        for (link in links) {
            // `scannedLinks` already filters these out; the check is here so the non-null id below
            // is a fact of the code rather than of a query somebody may later widen.
            val partnerInstance = link.partnerInstanceId ?: continue
            val engineLink = link.toEngineLink()
            runCatching {
                val applied = engine.applyInbound(
                    link = engineLink,
                    envelope = store.read(partnerInstance),
                    previousMirror = repository.mirror(link.id),
                    ourWeek = ourWeek,
                    takenContributionIds = repository.takenContributionIds(link.id)
                )

                val wasLinked = link.confirmedAt != null
                repository.recordRound(link.id, applied.confirmed, applied.rejection?.name)

                if (applied.rejection == null) {
                    repository.replaceMirror(link.id, applied.mirror)
                    repository.retireLandedContributions(link.id, applied.mirror)
                }

                // The one write that leaves People. Recorded as taken *before* the round can fail
                // anywhere else: a contribution that made a task but was not recorded would make a
                // second one on the next open, and a duplicate task on somebody's week is a worse
                // outcome than a contribution that has to be re-sent.
                //
                // With no planner registered the contribution is left untaken entirely, so it lands
                // if one ever appears rather than being silently swallowed by this round.
                val landed = applied.accepted.mapNotNull { contribution ->
                    val bridge = week ?: return@mapNotNull null
                    val taskId = bridge.createTask(contribution, link.partnerName)
                    repository.recordTaken(link.id, contribution, taskId)
                    contribution.title
                }
                taken += landed.size
                // A contribution just became a task on our week, so the snapshot above is already
                // out of date. Re-read before publishing, so the partner sees their suggestion
                // land on this round rather than being told about it a whole app-open later.
                if (landed.isNotEmpty()) week?.let { ourWeek = it.current() }

                val news = buildList {
                    if (applied.confirmed && !wasLinked) {
                        add(PartnerEventKind.CONNECTED to link.partnerName)
                    }
                    applied.changes.forEach { add(PartnerEventKind.of(it.kind) to it.title) }
                    landed.forEach { add(PartnerEventKind.ADDED_TO_OUR_WEEK to it) }
                }
                repository.recordEvents(link.id, news)
                changes += news.size

                engine.buildShare(
                    link = engineLink,
                    // Stamped per partner: a task that began as *their* contribution carries the id
                    // they know it by, so their app can see its own suggestion has landed. Another
                    // partner seeing the same week has no business being told any of that.
                    myWeek = ourWeek.stamped(repository.stamps(link.id)),
                    contributions = repository.pendingContributions(link.id)
                )?.let { shares += it }
            }.onFailure { failure = it.message ?: it::class.java.simpleName }
        }

        // One envelope carrying every partner's share, written last: a partner reading it mid-round
        // gets the previous complete one rather than a half-built list.
        store.write(engine.buildOutbound(shares, at))

        return PartnerSyncStatus(
            ranAt = at,
            linksSynced = links.size,
            changes = changes,
            takenOntoOurWeek = taken,
            error = failure
        )
    }

    /** This week with the tasks that came from [stamps] (`localTaskId -> contributionId`) marked. */
    private fun SharedWeek.stamped(stamps: Map<String, String>): SharedWeek =
        if (stamps.isEmpty()) this
        else copy(tasks = tasks.map { it.copy(fromContribution = stamps[it.taskId]) })
}
