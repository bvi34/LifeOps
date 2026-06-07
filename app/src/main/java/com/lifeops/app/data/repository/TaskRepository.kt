package com.lifeops.app.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lifeops.app.data.db.dao.*
import com.lifeops.app.data.db.entities.WeekSnapshotEntity
import com.lifeops.app.data.model.*
import com.lifeops.app.util.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TaskRepository(
    private val taskDao: TaskDao,
    private val aspectDao: AspectDao,
    private val categoryDao: CategoryDao,
    private val weekDao: WeekDao,
    private val weekSnapshotDao: WeekSnapshotDao,
    private val gameResourceDao: GameResourceDao,
    private val gameResourceMappingDao: GameResourceMappingDao,
    private val notificationDao: NotificationDao,
    private val notificationRepository: NotificationRepository
) {
    private val gson = Gson()

    fun observeTasksForWeek(weekId: String): Flow<List<Task>> =
        taskDao.observeByWeek(weekId).map { list -> list.map { it.toModel() } }

    suspend fun completeTask(task: Task) {
        // Mark completed; resources are tallied and applied to GameResource at week close.
        taskDao.markCompleted(task.id, TaskStatus.COMPLETED.value, DateUtil.now())
    }

    suspend fun skipTask(taskId: String) {
        taskDao.updateStatus(taskId, TaskStatus.SKIPPED.value)
    }

    suspend fun carryForward(task: Task, newWeekId: String): Task {
        taskDao.updateStatus(task.id, TaskStatus.CARRIED_FORWARD.value)
        notificationRepository.cancelForTask(task.id)
        val newTask = task.copy(
            id = java.util.UUID.randomUUID().toString(),
            weekId = newWeekId,
            status = TaskStatus.PENDING,
            completedAt = null,
            carriedFromTaskId = task.id,
            createdAt = DateUtil.now()
        )
        taskDao.upsert(newTask.toEntity())
        notificationRepository.scheduleForTask(newTask)
        return newTask
    }

    suspend fun upsertTask(task: Task) = taskDao.upsert(task.toEntity())

    suspend fun updateTask(task: Task) = taskDao.update(task.toEntity())

    suspend fun getById(id: String): Task? = taskDao.getById(id)?.toModel()

    suspend fun closeWeek(weekId: String) {
        val week = weekDao.getById(weekId) ?: return
        if (week.isClosed) return  // idempotent guard
        val now = DateUtil.now()

        // Transition all pending tasks
        val pending = taskDao.getPendingByWeek(weekId)
        pending.forEach { task ->
            val newStatus = if (task.hardDeadline) TaskStatus.EXPIRED.value else TaskStatus.INCOMPLETE.value
            taskDao.updateStatus(task.id, newStatus)
        }

        val allTasks = taskDao.getAllByWeek(weekId).map { it.toModel() }
        val snapshot = buildSnapshot(weekId, allTasks, now)
        weekSnapshotDao.insert(snapshot)

        val updatedWeek = week.copy(isClosed = true, closedAt = now)
        weekDao.update(updatedWeek)

        // Per spec: current_value and lifetime_earned increment on week close
        applySnapshotToGameResources(snapshot)

        // Reschedule next Sunday's week-close reminder
        notificationRepository.scheduleWeekCloseReminder()
    }

    private fun buildSnapshot(weekId: String, tasks: List<Task>, now: String): WeekSnapshotEntity {
        val aspectBreakdown = mutableMapOf<String, Int>()
        val categoryBreakdown = mutableMapOf<String, Int>()
        val categorySlipBreakdown = mutableMapOf<String, Int>()
        val categoryTotalBreakdown = mutableMapOf<String, Int>()

        tasks.forEach { task ->
            // Count every task (regardless of status) in the category total
            task.categoryId?.let { catId ->
                categoryTotalBreakdown[catId] = (categoryTotalBreakdown[catId] ?: 0) + 1
            }
            when (task.status) {
                TaskStatus.COMPLETED -> {
                    task.aspectId?.let { aspectBreakdown[it] = (aspectBreakdown[it] ?: 0) + task.resourceValue }
                    task.categoryId?.let { categoryBreakdown[it] = (categoryBreakdown[it] ?: 0) + task.resourceValue }
                }
                TaskStatus.INCOMPLETE, TaskStatus.EXPIRED -> {
                    task.categoryId?.let { categorySlipBreakdown[it] = (categorySlipBreakdown[it] ?: 0) + 1 }
                }
                else -> Unit
            }
        }

        val hdCompleted = tasks.count { it.hardDeadline && it.status == TaskStatus.COMPLETED }
        val hdExpired = tasks.count { it.hardDeadline && it.status == TaskStatus.EXPIRED }

        return WeekSnapshotEntity(
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
            categorySlipBreakdown = gson.toJson(categorySlipBreakdown),
            categoryTotalBreakdown = gson.toJson(categoryTotalBreakdown),
            hardDeadlineCompletedCount = hdCompleted,
            hardDeadlineExpiredCount = hdExpired,
            createdAt = now
        )
    }

    private suspend fun applySnapshotToGameResources(snapshot: WeekSnapshotEntity) {
        val type = object : TypeToken<Map<String, Int>>() {}.type
        val aspectBreakdown: Map<String, Int> = gson.fromJson(snapshot.aspectBreakdown, type) ?: emptyMap()
        aspectBreakdown.forEach { (aspectId, earned) ->
            val mappings = gameResourceMappingDao.getByAspect(aspectId)
            mappings.forEach { mapping ->
                val amount = (earned * mapping.weight).toInt()
                if (amount > 0) gameResourceDao.addValue(mapping.gameResourceId, amount)
            }
        }
    }

    suspend fun getEarnedThisWeekByAspect(weekId: String): Map<String, Int> {
        val tasks = taskDao.getAllByWeek(weekId).map { it.toModel() }
        val result = mutableMapOf<String, Int>()
        tasks.filter { it.status == TaskStatus.COMPLETED }.forEach { task ->
            task.aspectId?.let { result[it] = (result[it] ?: 0) + task.resourceValue }
        }
        return result
    }
}
