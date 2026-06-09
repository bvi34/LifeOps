package com.lifeops.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.db.dao.*
import com.lifeops.app.data.db.entities.WeekSnapshotEntity
import com.lifeops.app.data.model.*
import com.lifeops.app.util.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TaskRepository(
    private val db: LifeOpsDatabase,
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
        taskDao.markCompleted(task.id, TaskStatus.COMPLETED.value, DateUtil.now())
    }

    suspend fun skipTask(taskId: String) {
        taskDao.updateStatus(taskId, TaskStatus.SKIPPED.value)
    }

    suspend fun carryForward(task: Task, newWeekId: String): Task {
        notificationRepository.cancelForTask(task.id)
        val newCarriedCount = task.carriedCount + 1
        val newTask = task.copy(
            id = java.util.UUID.randomUUID().toString(),
            weekId = newWeekId,
            status = TaskStatus.PENDING,
            completedAt = null,
            carriedFromTaskId = task.id,
            carriedCount = newCarriedCount,
            createdAt = DateUtil.now()
        )
        db.withTransaction {
            taskDao.updateStatus(task.id, TaskStatus.CARRIED_FORWARD.value)
            taskDao.upsert(newTask.toEntity())
        }
        notificationRepository.scheduleForTask(newTask)
        return newTask
    }

    suspend fun upsertTask(task: Task) = taskDao.upsert(task.toEntity())

    suspend fun updateTask(task: Task) = taskDao.update(task.toEntity())

    suspend fun getById(id: String): Task? = taskDao.getById(id)?.toModel()

    suspend fun getAllSince(since: String): List<Task> =
        taskDao.getAllSince(since).map { it.toModel() }

    suspend fun closeWeek(weekId: String) {
        val week = weekDao.getById(weekId) ?: return
        if (week.isClosed) return
        val now = DateUtil.now()

        val pending = taskDao.getPendingByWeek(weekId)

        val timeByTask = db.timeEntryDao().getByWeek(weekId)
            .groupBy { it.taskId }
            .mapValues { (_, entries) -> entries.sumOf { it.durationMinutes } }

        db.withTransaction {
            pending.forEach { task ->
                val newStatus = if (task.hardDeadline) TaskStatus.EXPIRED.value else TaskStatus.INCOMPLETE.value
                taskDao.updateStatus(task.id, newStatus)
            }
            val allTasks = taskDao.getAllByWeek(weekId).map { it.toModel() }
            val snapshot = buildSnapshot(weekId, allTasks, timeByTask, now)
            weekSnapshotDao.insert(snapshot)
            weekDao.update(week.copy(isClosed = true, closedAt = now))
            applySnapshotToGameResources(snapshot)
        }

        notificationRepository.scheduleWeekCloseReminder()
    }

    /**
     * Accuracy multiplier applied to resourceValue at close time.
     *   No estimate OR no time logged  → 1.0x (no adjustment)
     *   Actual within ±15 min          → 2.0x (rewarded for accurate planning)
     *   Actual < estimated − 15        → 0.9x (over-allocated, finished faster)
     *   Actual > estimated + 15        → 0.75x (underestimated, ran over)
     */
    private fun accuracyMultiplier(task: Task, actualMinutes: Int?): Double {
        val estimated = task.estimatedMinutes ?: return 1.0
        val actual = actualMinutes?.takeIf { it > 0 } ?: return 1.0
        return when {
            abs(actual - estimated) <= 15 -> 2.0
            actual < estimated -> 0.9
            else -> 0.75
        }
    }

    private fun buildSnapshot(
        weekId: String,
        tasks: List<Task>,
        timeByTask: Map<String, Int>,
        now: String
    ): WeekSnapshotEntity {
        val aspectBreakdown = mutableMapOf<String, Int>()
        val categoryBreakdown = mutableMapOf<String, Int>()
        val categorySlipBreakdown = mutableMapOf<String, Int>()
        val categoryTotalBreakdown = mutableMapOf<String, Int>()

        tasks.forEach { task ->
            task.categoryId?.let { catId ->
                categoryTotalBreakdown[catId] = (categoryTotalBreakdown[catId] ?: 0) + 1
            }
            when (task.status) {
                TaskStatus.COMPLETED -> {
                    val earned = (task.resourceValue * accuracyMultiplier(task, timeByTask[task.id])).roundToInt()
                    task.aspectId?.let { aspectBreakdown[it] = (aspectBreakdown[it] ?: 0) + earned }
                    task.categoryId?.let { categoryBreakdown[it] = (categoryBreakdown[it] ?: 0) + earned }
                }
                TaskStatus.INCOMPLETE, TaskStatus.EXPIRED -> {
                    task.categoryId?.let { categorySlipBreakdown[it] = (categorySlipBreakdown[it] ?: 0) + 1 }
                }
                else -> Unit
            }
        }

        val hdCompleted = tasks.count { it.hardDeadline && it.status == TaskStatus.COMPLETED }
        val hdExpired = tasks.count { it.hardDeadline && it.status == TaskStatus.EXPIRED }

        val totalEarned = tasks
            .filter { it.status == TaskStatus.COMPLETED }
            .sumOf { (it.resourceValue * accuracyMultiplier(it, timeByTask[it.id])).roundToInt() }

        return WeekSnapshotEntity(
            id = java.util.UUID.randomUUID().toString(),
            weekId = weekId,
            completedCount = tasks.count { it.status == TaskStatus.COMPLETED },
            incompleteCount = tasks.count { it.status == TaskStatus.INCOMPLETE },
            expiredCount = tasks.count { it.status == TaskStatus.EXPIRED },
            skippedCount = tasks.count { it.status == TaskStatus.SKIPPED },
            carriedForwardCount = tasks.count { it.status == TaskStatus.CARRIED_FORWARD },
            totalResourcesEarned = totalEarned,
            aspectBreakdown = gson.toJson(aspectBreakdown),
            categoryBreakdown = gson.toJson(categoryBreakdown),
            categorySlipBreakdown = gson.toJson(categorySlipBreakdown),
            categoryTotalBreakdown = gson.toJson(categoryTotalBreakdown),
            hardDeadlineCompletedCount = hdCompleted,
            hardDeadlineExpiredCount = hdExpired,
            createdAt = now
        )
    }

    // Must be called from within a database transaction.
    private suspend fun applySnapshotToGameResources(snapshot: WeekSnapshotEntity) {
        val type = object : TypeToken<Map<String, Int>>() {}.type
        val aspectBreakdown: Map<String, Int> = gson.fromJson(snapshot.aspectBreakdown, type) ?: emptyMap()
        aspectBreakdown.forEach { (aspectId, earned) ->
            val mappings = gameResourceMappingDao.getByAspect(aspectId)
            mappings.forEach { mapping ->
                val amount = (earned * mapping.weight).roundToInt()
                if (amount > 0) gameResourceDao.addValue(mapping.gameResourceId, amount)
            }
        }
    }

    suspend fun unCompleteTask(taskId: String) = taskDao.unmarkCompleted(taskId)

    suspend fun clearCategoryFromTasks(categoryId: String) = taskDao.nullifyCategoryId(categoryId)

    suspend fun unSkipTask(taskId: String) = taskDao.unSkipTask(taskId)

    suspend fun updateTaskSortOrder(taskId: String, order: Int) = taskDao.updateSortOrder(taskId, order)

    suspend fun getTasksWithCarryHistory(): List<Task> =
        taskDao.getTasksWithCarryHistory().map { it.toModel() }

    suspend fun seedRecurringTasks(fromWeekId: String, toWeekId: String) {
        // Only seed if the target week has no recurring tasks yet (prevents duplicate seeding on every launch)
        val existingRecurring = taskDao.getRecurringByWeek(toWeekId)
        if (existingRecurring.isNotEmpty()) return
        val recurring = taskDao.getRecurringByWeek(fromWeekId)
        if (recurring.isEmpty()) return
        val now = DateUtil.now()
        db.withTransaction {
            for (entity in recurring) {
                val newTask = entity.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    weekId = toWeekId,
                    status = TaskStatus.PENDING.value,
                    completedAt = null,
                    carriedFromTaskId = null,
                    carriedCount = 0,
                    sortOrder = 0,
                    createdAt = now
                )
                taskDao.upsert(newTask)
            }
        }
    }

    suspend fun getAllTasks(): List<Task> = taskDao.getAll().map { it.toModel() }

    suspend fun getEarnedThisWeekByAspect(weekId: String): Map<String, Int> {
        val tasks = taskDao.getAllByWeek(weekId).map { it.toModel() }
        val timeByTask = db.timeEntryDao().getByWeek(weekId)
            .groupBy { it.taskId }
            .mapValues { (_, entries) -> entries.sumOf { it.durationMinutes } }
        val result = mutableMapOf<String, Int>()
        tasks.filter { it.status == TaskStatus.COMPLETED }.forEach { task ->
            val earned = (task.resourceValue * accuracyMultiplier(task, timeByTask[task.id])).roundToInt()
            task.aspectId?.let { result[it] = (result[it] ?: 0) + earned }
        }
        return result
    }
}
