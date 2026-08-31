package com.people.app.data.repository

import com.people.app.data.db.dao.PartnerDao
import com.people.app.data.db.dao.UnseenCount
import com.people.app.data.db.dao.UnseenForPerson
import com.people.app.data.db.entities.PartnerEventEntity
import com.people.app.data.db.entities.PartnerLinkEntity
import com.people.app.data.db.entities.PartnerOutboxEntity
import com.people.app.data.db.entities.PartnerTakenEntity
import com.people.app.data.db.entities.PartnerWeekTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The partner tables in memory, so a whole round can be driven through [PartnerRepository] and
 * [PartnerSyncService] on the JVM.
 *
 * A fake rather than an in-memory Room database because these tests are plain JVM unit tests — the
 * module has no instrumentation or Robolectric dependency, and the rules being checked here are the
 * service's ordering, not SQLite's. The queries it stands in for are simple enough to reimplement
 * honestly: each method below does what its `@Query` says, including the null-vs-empty distinction
 * `scannedLinks` turns on.
 */
class FakePartnerDao : PartnerDao {

    private val links = MutableStateFlow<List<PartnerLinkEntity>>(emptyList())
    private val weekTasks = MutableStateFlow<List<PartnerWeekTaskEntity>>(emptyList())
    private val outbox = MutableStateFlow<List<PartnerOutboxEntity>>(emptyList())
    private val taken = MutableStateFlow<List<PartnerTakenEntity>>(emptyList())
    private val events = MutableStateFlow<List<PartnerEventEntity>>(emptyList())

    // --- links ---

    override fun observeLinkFor(personId: String): Flow<PartnerLinkEntity?> =
        links.map { rows -> rows.firstOrNull { it.personId == personId } }

    override suspend fun linkFor(personId: String): PartnerLinkEntity? =
        links.value.firstOrNull { it.personId == personId }

    override suspend fun link(id: String): PartnerLinkEntity? =
        links.value.firstOrNull { it.id == id }

    override suspend fun linkClaiming(instanceId: String, exceptPersonId: String): PartnerLinkEntity? =
        links.value.firstOrNull { it.partnerInstanceId == instanceId && it.personId != exceptPersonId }

    override suspend fun scannedLinks(): List<PartnerLinkEntity> =
        links.value.filter { it.partnerInstanceId != null }

    override fun observeLinks(): Flow<List<PartnerLinkEntity>> =
        links.map { rows -> rows.sortedBy { it.createdAt } }

    override suspend fun upsertLink(link: PartnerLinkEntity) {
        links.value = links.value.filterNot { it.id == link.id } + link
    }

    override suspend fun deleteLink(id: String) {
        links.value = links.value.filterNot { it.id == id }
    }

    // --- the mirror of their week ---

    override fun observeWeek(linkId: String): Flow<List<PartnerWeekTaskEntity>> =
        weekTasks.map { rows -> rows.filter { it.linkId == linkId } }

    override suspend fun week(linkId: String): List<PartnerWeekTaskEntity> =
        weekTasks.value.filter { it.linkId == linkId }

    override suspend fun upsertWeekTasks(tasks: List<PartnerWeekTaskEntity>) {
        val keys = tasks.map { it.linkId to it.taskId }.toSet()
        weekTasks.value = weekTasks.value.filterNot { (it.linkId to it.taskId) in keys } + tasks
    }

    override suspend fun clearWeek(linkId: String) {
        weekTasks.value = weekTasks.value.filterNot { it.linkId == linkId }
    }

    // --- what we have added to their week ---

    override fun observeOutbox(linkId: String): Flow<List<PartnerOutboxEntity>> =
        outbox.map { rows -> rows.filter { it.linkId == linkId }.sortedBy { it.createdAt } }

    override suspend fun outbox(linkId: String): List<PartnerOutboxEntity> =
        outbox.value.filter { it.linkId == linkId }.sortedBy { it.createdAt }

    override suspend fun upsertOutbox(entry: PartnerOutboxEntity) {
        outbox.value = outbox.value.filterNot { it.id == entry.id } + entry
    }

    override suspend fun deleteOutbox(id: String) {
        outbox.value = outbox.value.filterNot { it.id == id }
    }

    override suspend fun deleteOutbox(linkId: String, ids: List<String>) {
        outbox.value = outbox.value.filterNot { it.linkId == linkId && it.id in ids }
    }

    override suspend fun clearOutbox(linkId: String) {
        outbox.value = outbox.value.filterNot { it.linkId == linkId }
    }

    // --- what we have taken from theirs ---

    override suspend fun takenIds(linkId: String): List<String> =
        taken.value.filter { it.linkId == linkId }.map { it.contributionId }

    override suspend fun takenFor(linkId: String): List<PartnerTakenEntity> =
        taken.value.filter { it.linkId == linkId && it.localTaskId != null }

    override suspend fun upsertTaken(taken: PartnerTakenEntity) {
        this.taken.value = this.taken.value.filterNot { it.contributionId == taken.contributionId } + taken
    }

    // --- what to tell the user ---

    override suspend fun unseenEvents(linkId: String): List<PartnerEventEntity> =
        events.value.filter { it.linkId == linkId && !it.seen }.sortedByDescending { it.at }

    override fun observeUnseenByLink(): Flow<List<UnseenCount>> =
        events.map { rows ->
            rows.filterNot { it.seen }.groupBy { it.linkId }.map { (linkId, unseen) ->
                UnseenCount(linkId, unseen.size)
            }
        }

    override fun observeUnseenByPerson(): Flow<List<UnseenForPerson>> =
        events.map { rows ->
            rows.filterNot { it.seen }
                .mapNotNull { event -> links.value.firstOrNull { it.id == event.linkId }?.personId }
                .groupingBy { it }
                .eachCount()
                .map { (personId, unseen) -> UnseenForPerson(personId, unseen) }
        }

    override suspend fun upsertEvents(events: List<PartnerEventEntity>) {
        val ids = events.map { it.id }.toSet()
        this.events.value = this.events.value.filterNot { it.id in ids } + events
    }

    override suspend fun markSeen(linkId: String) {
        events.value = events.value.map { if (it.linkId == linkId) it.copy(seen = true) else it }
    }

    override suspend fun clearEvents(linkId: String) {
        events.value = events.value.filterNot { it.linkId == linkId }
    }

    override suspend fun trimEvents(linkId: String, keep: Int) {
        val mine = events.value.filter { it.linkId == linkId }
        val kept = mine.sortedByDescending { it.at }.take(keep).map { it.id }.toSet()
        events.value = events.value.filterNot { it.linkId == linkId && it.seen && it.id !in kept }
    }
}
