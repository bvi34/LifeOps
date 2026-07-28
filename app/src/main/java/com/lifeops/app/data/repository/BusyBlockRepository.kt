package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.BusyBlockDao
import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BusyBlockRepository(
    private val dao: BusyBlockDao,
    private val reminderScheduler: BusyBlockReminderScheduler = NoopBusyBlockReminderScheduler
) {
    /** The user's own schedule (personId == null). */
    fun observeMine(): Flow<List<BusyBlock>> =
        dao.observeMine().map { list -> list.map { it.toModel() } }

    /** One person's schedule. */
    fun observeForPerson(personId: String): Flow<List<BusyBlock>> =
        dao.observeForPerson(personId).map { list -> list.map { it.toModel() } }

    /** Everything, own + everyone's — the best-time engine partitions by personId. */
    fun observeAll(): Flow<List<BusyBlock>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun upsert(block: BusyBlock) {
        dao.upsert(block.toEntity())
        // Reminders are an own-schedule feature; a person's block never notifies. REPLACE-then-cancel
        // keeps a toggled-off (or newly person-owned) block from leaving a stale reminder queued.
        if (block.reminderEnabled && block.personId == null) reminderScheduler.schedule(block)
        else reminderScheduler.cancel(block.id)
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        reminderScheduler.cancel(id)
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
