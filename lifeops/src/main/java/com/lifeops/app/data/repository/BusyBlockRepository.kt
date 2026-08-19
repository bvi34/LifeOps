package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.BusyBlockDao
import com.lifeops.app.data.db.entities.BusyBlockPersonEntity
import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class BusyBlockRepository(
    private val dao: BusyBlockDao,
    private val reminderScheduler: BusyBlockReminderScheduler = NoopBusyBlockReminderScheduler
) {
    /** The user's own schedule (personId == null), with tagged people hydrated in. */
    fun observeMine(): Flow<List<BusyBlock>> =
        withPeople(dao.observeMine())

    /** One person's schedule. */
    fun observeForPerson(personId: String): Flow<List<BusyBlock>> =
        withPeople(dao.observeForPerson(personId))

    /** Everything, own + everyone's — the best-time engine partitions by personId. */
    fun observeAll(): Flow<List<BusyBlock>> =
        withPeople(dao.observeAll())

    private fun withPeople(blocks: Flow<List<com.lifeops.app.data.db.entities.BusyBlockEntity>>): Flow<List<BusyBlock>> =
        combine(blocks, dao.observeAllPeopleLinks()) { entities, links ->
            val peopleByBlock = links.groupBy({ it.busyBlockId }, { it.personId })
            entities.map { it.toModel().copy(peopleIds = peopleByBlock[it.id].orEmpty()) }
        }

    suspend fun getById(id: String): BusyBlock? {
        val entity = dao.getById(id) ?: return null
        return entity.toModel().copy(peopleIds = dao.getPersonIdsForBlock(id))
    }

    /** The block a previously-pulled Google Calendar occurrence maps to, if any. */
    suspend fun findByGoogleInstance(googleEventId: Long, googleCalendarId: Long, specificDate: String): BusyBlock? {
        val entity = dao.findByGoogleInstance(googleEventId, googleCalendarId, specificDate) ?: return null
        return entity.toModel().copy(peopleIds = dao.getPersonIdsForBlock(entity.id))
    }

    suspend fun upsert(block: BusyBlock) {
        dao.upsert(block.toEntity())
        dao.clearPeople(block.id)
        block.peopleIds.distinct().forEach { personId ->
            dao.attachPerson(BusyBlockPersonEntity(block.id, personId))
        }
        // Reminders are an own-schedule feature; a person's block never notifies. REPLACE-then-cancel
        // keeps a toggled-off (or newly person-owned) block from leaving a stale reminder queued.
        if (block.reminderEnabled && block.personId == null) reminderScheduler.schedule(block)
        else reminderScheduler.cancel(block.id)
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        reminderScheduler.cancel(id)
    }

    /** Full snapshot (own + everyone's), people hydrated — for the Google Calendar sync engine. */
    suspend fun getAll(): List<BusyBlock> {
        val links = dao.getAllPeopleLinks().groupBy({ it.busyBlockId }, { it.personId })
        return dao.getAll().map { it.toModel().copy(peopleIds = links[it.id].orEmpty()) }
    }

    /**
     * Re-arm every own-schedule reminder from the database. WorkManager's queue can be lost across
     * reinstall/device transfer while the blocks persist in Room, so this runs at app start.
     */
    suspend fun rescheduleAllReminders() {
        dao.getAll()
            .map { it.toModel() }
            .filter { it.reminderEnabled && it.personId == null }
            .forEach { reminderScheduler.schedule(it) }
    }
}
