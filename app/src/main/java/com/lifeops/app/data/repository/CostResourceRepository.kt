package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.CostResourceDao
import com.lifeops.app.data.db.dao.TaskCostEntryDao
import com.lifeops.app.data.db.entities.CostResourceEntity
import com.lifeops.app.data.db.entities.TaskCostEntryEntity
import com.lifeops.app.data.model.CostResource
import com.lifeops.app.data.model.TaskCostEntry
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class CostResourceRepository(
    private val costResourceDao: CostResourceDao,
    private val taskCostEntryDao: TaskCostEntryDao
) {
    fun observeActiveResources(): Flow<List<CostResource>> =
        costResourceDao.observeActive().map { list -> list.map { it.toModel() } }

    fun observeAllResources(): Flow<List<CostResource>> =
        costResourceDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun getAllSync(): List<CostResource> =
        costResourceDao.getAllSync().map { it.toModel() }

    suspend fun addResource(name: String, resetCycle: String = "monthly", capacity: Int? = null) {
        costResourceDao.upsert(
            CostResourceEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                resetCycle = resetCycle,
                capacity = capacity,
                isActive = true,
                sortIndex = 0,
                createdAt = DateUtil.now()
            )
        )
    }

    suspend fun updateResource(resource: CostResource) =
        costResourceDao.upsert(resource.toEntity())

    suspend fun setActive(id: String, active: Boolean) = costResourceDao.setActive(id, active)

    fun observeEntriesByWeek(weekId: String): Flow<List<TaskCostEntry>> =
        taskCostEntryDao.observeByWeek(weekId).map { list -> list.map { it.toModel() } }

    fun observeEntriesByTask(taskId: String): Flow<List<TaskCostEntry>> =
        taskCostEntryDao.observeByTask(taskId).map { list -> list.map { it.toModel() } }

    suspend fun logCost(taskId: String, resourceId: String, amount: Int, note: String? = null) {
        taskCostEntryDao.insert(
            TaskCostEntryEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                resourceId = resourceId,
                amount = amount,
                note = note,
                recordedAt = DateUtil.now()
            )
        )
    }

    suspend fun deleteCostEntry(id: String) = taskCostEntryDao.deleteById(id)

    suspend fun getEntriesByWeek(weekId: String): List<TaskCostEntry> =
        taskCostEntryDao.getByWeek(weekId).map { it.toModel() }

    suspend fun getAllEntries(): List<TaskCostEntry> =
        taskCostEntryDao.getAll().map { it.toModel() }
}
