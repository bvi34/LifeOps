package com.lifeops.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.db.dao.*
import com.lifeops.app.data.db.entities.WeekSnapshotEntity
import com.lifeops.app.data.model.*
import com.lifeops.app.data.model.TaskSource
import com.lifeops.app.util.*
import com.lifeops.app.util.ImportParser
import com.lifeops.app.util.ScoringUtils
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
        if (taskDao.getChildOf(task.id) != null) return
        taskDao.markCompleted(task.id, TaskStatus.COMPLETED.value, DateUtil.now())
    }

    suspend fun skipTask(taskId: String) {
        taskDao.updateStatus(taskId, TaskStatus.SKIPPED.value)
    }

    suspend fun unsuccessTask(taskId: String) {
        taskDao.updateStatus(taskId, TaskStatus.UNSUCCESSFUL.value)
    }

    suspend fun unUnsuccessTask(taskId: String) {
        taskDao.updateStatus(taskId, TaskStatus.PENDING.value)
    }

    suspend fun carryForward(task: Task) {
        notificationRepository.cancelForTask(task.id)
        taskDao.updateStatus(task.id, TaskStatus.CARRIED_FORWARD.value)
    }

    suspend fun unCarryForward(taskId: String) {
        val child = taskDao.getChildOf(taskId)
        if (child == null) taskDao.unCarryForward(taskId)
    }

    suspend fun upsertTask(task: Task) = taskDao.upsert(task.toEntity())

    suspend fun updateTask(task: Task) = taskDao.update(task.toEntity())

    suspend fun getById(id: String): Task? = taskDao.getById(id)?.toModel()

    suspend fun getAllSince(since: String): List<Task> =
        taskDao.getAllSince(since).map { it.toModel() }

    suspend fun closeWeek(weekId: String, newWeekId: String, selfRating: Int? = null, selfRatingNote: String? = null) {
        val week = weekDao.getById(weekId) ?: return
        if (week.isClosed) return
        val now = DateUtil.now()

        val pending = taskDao.getPendingByWeek(weekId)
        val carriedForward = taskDao.getCarriedForwardByWeek(weekId)

        val timeByTask = db.timeEntryDao().getByWeek(weekId)
            .groupBy { it.taskId }
            .mapValues { (_, entries) -> entries.sumOf { it.durationMinutes } }

        val newTasksToSchedule = mutableListOf<Task>()

        db.withTransaction {
            pending.forEach { task ->
                val newStatus = if (task.hardDeadline) TaskStatus.EXPIRED.value else TaskStatus.INCOMPLETE.value
                taskDao.updateStatus(task.id, newStatus)
            }
            for (carried in carriedForward) {
                if (taskDao.getChildOf(carried.id) == null) {
                    val newTask = carried.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        weekId = newWeekId,
                        status = TaskStatus.PENDING.value,
                        carriedFromTaskId = carried.id,
                        carriedCount = carried.carriedCount + 1,
                        completedAt = null,
                        sortOrder = 0,
                        createdAt = now
                    )
                    taskDao.upsert(newTask)
                    newTasksToSchedule.add(newTask.toModel())
                }
            }
            val allTasks = taskDao.getAllByWeek(weekId).map { it.toModel() }
            val aspectMeta = aspectDao.getAllSync().associate { it.id to (it.name to it.color) }
            val snapshot = buildSnapshot(weekId, allTasks, timeByTask, now, aspectMeta, selfRating, selfRatingNote)
            weekSnapshotDao.insert(snapshot)
            weekDao.update(week.copy(isClosed = true, closedAt = now))
            applySnapshotToGameResources(snapshot)
        }

        for (task in newTasksToSchedule) notificationRepository.scheduleForTask(task)
        notificationRepository.scheduleWeekCloseReminder()
    }

    suspend fun getLineageIds(taskId: String): List<String> {
        val upChain = mutableListOf<String>()
        var currentId: String? = taskId
        val visited = mutableSetOf<String>()
        while (currentId != null && currentId !in visited) {
            visited.add(currentId)
            val task = taskDao.getById(currentId) ?: break
            upChain.add(0, task.id)
            currentId = task.carriedFromTaskId
        }
        val downChain = mutableListOf<String>()
        val visitedDown = visited.toMutableSet()
        var child = taskDao.getChildOf(taskId)
        while (child != null && child.id !in visitedDown) {
            visitedDown.add(child.id)
            downChain.add(child.id)
            child = taskDao.getChildOf(child.id)
        }
        return upChain + downChain
    }

    suspend fun repairSameWeekCarries() {
        val allTasks = taskDao.getAll()
        val taskById = allTasks.associateBy { it.id }
        val badChildren = allTasks.filter { task ->
            val parent = task.carriedFromTaskId?.let { taskById[it] }
            parent != null && parent.weekId == task.weekId
        }
        if (badChildren.isEmpty()) return
        db.withTransaction {
            for (child in badChildren) {
                val parent = taskById[child.carriedFromTaskId] ?: continue
                val parentMinutes = db.timeEntryDao().getByTask(parent.id).sumOf { it.durationMinutes }
                val parentNotes = db.taskNoteDao().getByTask(parent.id)
                if (parentMinutes == 0 && parentNotes.isEmpty()) {
                    db.timeEntryDao().reassignToTask(parent.id, child.id)
                    db.taskNoteDao().reassignToTask(parent.id, child.id)
                    db.taskCostEntryDao().reassignToTask(parent.id, child.id)
                    taskDao.update(child.copy(carriedFromTaskId = parent.carriedFromTaskId))
                    taskDao.delete(parent.id)
                } else {
                    taskDao.updateStatus(parent.id, TaskStatus.CARRIED_FORWARD.value)
                }
            }
        }
    }

    suspend fun promoteTaskToProject(taskId: String, projectId: String) {
        val lineageIds = getLineageIds(taskId)
        db.withTransaction {
            for (id in lineageIds) {
                val entity = taskDao.getById(id) ?: continue
                taskDao.update(entity.copy(projectId = projectId))
            }
        }
    }

    private fun accuracyMultiplier(task: Task, actualMinutes: Int?): Double =
        ScoringUtils.accuracyMultiplier(task.estimatedMinutes, actualMinutes)

    private fun buildSnapshot(
        weekId: String,
        tasks: List<Task>,
        timeByTask: Map<String, Int>,
        now: String,
        aspectMeta: Map<String, Pair<String, String>>,
        selfRating: Int? = null,
        selfRatingNote: String? = null
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
                TaskStatus.UNSUCCESSFUL -> {
                    val earned = (task.resourceValue * accuracyMultiplier(task, timeByTask[task.id]) * 0.5).roundToInt()
                    task.aspectId?.let { aspectBreakdown[it] = (aspectBreakdown[it] ?: 0) + earned }
                    task.categoryId?.let { categoryBreakdown[it] = (categoryBreakdown[it] ?: 0) + earned }
                }
                TaskStatus.INCOMPLETE, TaskStatus.EXPIRED -> {
                    task.categoryId?.let { categorySlipBreakdown[it] = (categorySlipBreakdown[it] ?: 0) + 1 }
                }
                else -> Unit
            }
        }

        // Growth rings: minutes per aspect = ALL logged time that week (effort is effort,
        // regardless of whether the task was completed). Seal the aspect's current name +
        // colour so the ring stays faithful if the aspect is later deleted/renamed/recoloured.
        val aspectMinutes = mutableMapOf<String, Int>()
        tasks.forEach { task ->
            val aspectId = task.aspectId ?: return@forEach
            val mins = timeByTask[task.id] ?: 0
            if (mins > 0) aspectMinutes[aspectId] = (aspectMinutes[aspectId] ?: 0) + mins
        }
        val aspectHistory = aspectMinutes.mapValues { (id, mins) ->
            val meta = aspectMeta[id]
            AspectHistoryEntry(mins, meta?.first ?: id, meta?.second ?: GrowthRings.SCAR_COLOR)
        }

        val hdCompleted = tasks.count { it.hardDeadline && it.status == TaskStatus.COMPLETED }
        val hdExpired = tasks.count { it.hardDeadline && it.status == TaskStatus.EXPIRED }

        val totalEarned = tasks.sumOf { task ->
            when (task.status) {
                TaskStatus.COMPLETED ->
                    (task.resourceValue * accuracyMultiplier(task, timeByTask[task.id])).roundToInt()
                TaskStatus.UNSUCCESSFUL ->
                    (task.resourceValue * accuracyMultiplier(task, timeByTask[task.id]) * 0.5).roundToInt()
                else -> 0
            }
        }

        return WeekSnapshotEntity(
            id = java.util.UUID.randomUUID().toString(),
            weekId = weekId,
            completedCount = tasks.count { it.status == TaskStatus.COMPLETED },
            incompleteCount = tasks.count { it.status == TaskStatus.INCOMPLETE },
            expiredCount = tasks.count { it.status == TaskStatus.EXPIRED },
            skippedCount = tasks.count { it.status == TaskStatus.SKIPPED },
            carriedForwardCount = tasks.count { it.status == TaskStatus.CARRIED_FORWARD },
            unsuccessfulCount = tasks.count { it.status == TaskStatus.UNSUCCESSFUL },
            totalResourcesEarned = totalEarned,
            aspectBreakdown = gson.toJson(aspectBreakdown),
            categoryBreakdown = gson.toJson(categoryBreakdown),
            categorySlipBreakdown = gson.toJson(categorySlipBreakdown),
            categoryTotalBreakdown = gson.toJson(categoryTotalBreakdown),
            hardDeadlineCompletedCount = hdCompleted,
            hardDeadlineExpiredCount = hdExpired,
            createdAt = now,
            aspectHistory = gson.toJson(aspectHistory),
            selfRating = selfRating,
            selfRatingNote = selfRatingNote?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * One-time backfill of [WeekSnapshotEntity.aspectHistory] for weeks closed before that
     * column existed, so their Growth rings are deletion-safe (and identical to what a live
     * derivation would produce today). Idempotent — only fills snapshots that are still empty.
     */
    suspend fun backfillAspectHistory() {
        val snapshots = weekSnapshotDao.getAll()
        if (snapshots.isEmpty()) return
        val aspectMeta = aspectDao.getAllSync().associate { it.id to (it.name to it.color) }
        db.withTransaction {
            for (snap in snapshots) {
                if (snap.aspectHistory.isNotBlank() && snap.aspectHistory != "{}") continue
                val timeByTask = db.timeEntryDao().getByWeek(snap.weekId)
                    .groupBy { it.taskId }
                    .mapValues { (_, entries) -> entries.sumOf { it.durationMinutes } }
                val aspectMinutes = mutableMapOf<String, Int>()
                taskDao.getAllByWeek(snap.weekId).forEach { task ->
                    val aspectId = task.aspectId ?: return@forEach
                    val mins = timeByTask[task.id] ?: 0
                    if (mins > 0) aspectMinutes[aspectId] = (aspectMinutes[aspectId] ?: 0) + mins
                }
                if (aspectMinutes.isEmpty()) continue
                val history = aspectMinutes.mapValues { (id, mins) ->
                    val meta = aspectMeta[id]
                    AspectHistoryEntry(mins, meta?.first ?: id, meta?.second ?: GrowthRings.SCAR_COLOR)
                }
                weekSnapshotDao.insert(snap.copy(aspectHistory = gson.toJson(history)))
            }
        }
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

    suspend fun createTaskWithNotes(
        weekId: String,
        title: String,
        notes: List<String>,
        source: TaskSource,
        aspectId: String? = null,
        categoryId: String? = null
    ) {
        val now = DateUtil.now()
        val task = Task(
            id = java.util.UUID.randomUUID().toString(),
            weekId = weekId,
            title = title,
            aspectId = aspectId,
            categoryId = categoryId,
            priority = Priority.MEDIUM,
            status = TaskStatus.PENDING,
            resourceValue = ImportParser.computeResourceValue("medium", false, null, isManuallyAdded = true),
            createdAt = now,
            source = source
        )
        db.withTransaction {
            taskDao.upsert(task.toEntity())
            for (note in notes) {
                db.taskNoteDao().insert(
                    com.lifeops.app.data.db.entities.TaskNoteEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        taskId = task.id,
                        content = note,
                        createdAt = now
                    )
                )
            }
        }
        notificationRepository.scheduleForTask(task)
    }

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
