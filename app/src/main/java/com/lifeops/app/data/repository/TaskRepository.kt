package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.*
import com.lifeops.app.data.db.entities.TaskEntity
import com.lifeops.app.data.model.*
import com.lifeops.app.util.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class TaskRepository(
    private val taskDao: TaskDao,
    private val aspectDao: AspectDao,
    private val categoryDao: CategoryDao,
    private val weekDao: WeekDao,
    private val weekSnapshotDao: WeekSnapshotDao,
    private val gameResourceDao: GameResourceDao,
    private val gameResourceMappingDao: GameResourceMappingDao,
    private val notificationDao: NotificationDao
) {
    fun observeTasksForWeek(weekId: String): Flow<List<Task>> =
        taskDao.observeByWeek(weekId).map { list -> list.map { it.toModel() } }

    suspend fun completeTask(task: Task) {
        val now = DateUtil.now()
        taskDao.markCompleted(task.id, TaskStatus.COMPLETED.value, now)
        awardResources(task)
    }

    suspend fun skipTask(taskId: String) {
        taskDao.updateStatus(taskId, TaskStatus.SKIPPED.value)
    }

    suspend fun carryForward(task: Task, newWeekId: String): Task {
        taskDao.updateStatus(task.id, TaskStatus.CARRIED_FORWARD.value)
        val newTask = task.copy(
            id = java.util.UUID.randomUUID().toString(),
            weekId = newWeekId,
            status = TaskStatus.PENDING,
            completedAt = null,
            carriedFromTaskId = task.id,
            createdAt = DateUtil.now()
        )
        taskDao.upsert(newTask.toEntity())
        return newTask
    }

    suspend fun upsertTask(task: Task) = taskDao.upsert(task.toEntity())

    suspend fun getById(id: String): Task? = taskDao.getById(id)?.toModel()

    private suspend fun awardResources(task: Task) {
        val aspectId = task.aspectId ?: return
        val mappings = gameResourceMappingDao.getByAspect(aspectId)
        mappings.forEach { mapping ->
            val amount = (task.resourceValue * mapping.weight).toInt()
            if (amount > 0) gameResourceDao.addValue(mapping.gameResourceId, amount)
        }
    }

    suspend fun closeWeek(weekId: String) {
        val week = weekDao.getById(weekId) ?: return
        val pending = taskDao.getPendingByWeek(weekId)
        val now = DateUtil.now()
        pending.forEach { task ->
            val newStatus = if (task.hardDeadline) TaskStatus.EXPIRED.value else TaskStatus.INCOMPLETE.value
            taskDao.updateStatus(task.id, newStatus)
        }
        val allTasks = taskDao.getAllByWeek(weekId).map { it.toModel() }
        val snapshot = buildSnapshot(weekId, allTasks, now)
        weekSnapshotDao.insert(snapshot)
        val updatedWeek = week.copy(isClosed = true, closedAt = now)
        weekDao.update(updatedWeek)
        // Resources are awarded per task on completion (checkbox tap), not again here.
    }

    private fun buildSnapshot(weekId: String, tasks: List<Task>, now: String): com.lifeops.app.data.db.entities.WeekSnapshotEntity {
        val aspectBreakdown = mutableMapOf<String, Int>()
        val categoryBreakdown = mutableMapOf<String, Int>()
        tasks.filter { it.status == TaskStatus.COMPLETED }.forEach { task ->
            task.aspectId?.let { aspectBreakdown[it] = (aspectBreakdown[it] ?: 0) + task.resourceValue }
            task.categoryId?.let { categoryBreakdown[it] = (categoryBreakdown[it] ?: 0) + task.resourceValue }
        }
        val gson = com.google.gson.Gson()
        return com.lifeops.app.data.db.entities.WeekSnapshotEntity(
            id = java.util.UUID.randomUUID().toString(),
            weekId = weekId,
            completedCount = tasks.count { it.status == TaskStatus.COMPLETED },
            incompleteCount = tasks.count { it.status == TaskStatus.INCOMPLETE },
            expiredCount = tasks.count { it.status == TaskStatus.EXPIRED },
            skippedCount = tasks.count { it.status == TaskStatus.SKIPPED },
            carriedForwardCount = tasks.count { it.status == TaskStatus.CARRIED_FORWARD },
            totalResourcesEarned = tasks.filter { it.status == TaskStatus.COMPLETED }.sumOf { it.resourceValue },
            aspectBreakdown = gson.toJson(aspectBreakdown),
            categoryBreakdown = gson.toJson(categoryBreakdown),
            createdAt = now
        )
    }

}
