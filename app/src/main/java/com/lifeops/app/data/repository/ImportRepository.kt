package com.lifeops.app.data.repository

import androidx.room.withTransaction
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.model.*
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ImportParser
import java.util.UUID

class ImportRepository(
    private val db: LifeOpsDatabase,
    private val aspectRepository: AspectRepository,
    private val taskRepository: TaskRepository,
    private val weekRepository: WeekRepository,
    private val notificationRepository: NotificationRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val timeEntryRepository: TimeEntryRepository
) {
    suspend fun previewImport(json: String): Result<ImportPreview> {
        val result = ImportParser.parse(json)
        if (result.error != null) return Result.failure(Exception(result.error))

        val aspectCache = mutableMapOf<String, Aspect>()
        val categoryCache = mutableMapOf<String, Category>()
        val newAspects = mutableListOf<Aspect>()
        val newCategories = mutableListOf<Category>()

        val week = weekRepository.getOrCreateCurrentWeek()
        val existingTitles = db.taskDao().getAllByWeek(week.id).map { it.title.lowercase().trim() }.toSet()

        val tasks = result.tasks.map { parsed ->
            val aspect: Aspect? = parsed.aspectName?.let { aspName ->
                val key = aspName.lowercase()
                aspectCache.getOrPut(key) {
                    val found = resolveAspectFromDb(aspName)
                    if (found == null) {
                        val new = Aspect(UUID.randomUUID().toString(), aspName, "#6200EE", "star")
                        newAspects.add(new)
                        new
                    } else found
                }
            }
            val category: Category? = parsed.categoryName?.let { catName ->
                aspect?.let { asp ->
                    val key = "${asp.id}/${catName.lowercase()}"
                    categoryCache.getOrPut(key) {
                        val found = resolveCategoryFromDb(asp.id, catName)
                        if (found == null) {
                            val new = Category(UUID.randomUUID().toString(), asp.id, catName)
                            newCategories.add(new)
                            new
                        } else found
                    }
                }
            }
            val taskStatus = TaskStatus.from(parsed.status)
            val validDueDate = parsed.dueDate?.takeIf { DateUtil.isValidDate(it) }
            Task(
                id = UUID.randomUUID().toString(),
                weekId = week.id,
                title = parsed.title,
                aspectId = aspect?.id,
                categoryId = category?.id,
                priority = Priority.from(parsed.priority),
                dueDate = validDueDate,
                hardDeadline = parsed.hardDeadline,
                status = taskStatus,
                completedAt = if (taskStatus == TaskStatus.COMPLETED) DateUtil.now() else null,
                // Imported completed tasks get resourceValue=0 — they're historical records
                // and should not inflate the current week's score.
                resourceValue = if (taskStatus == TaskStatus.COMPLETED) 0
                               else ImportParser.computeResourceValue(parsed.priority, parsed.hardDeadline, parsed.estimatedMinutes, isManuallyAdded = false),
                estimatedMinutes = parsed.estimatedMinutes,
                isRecurring = parsed.isRecurring,
                createdAt = DateUtil.now()
            )
        }

        val existingTaskCount = tasks.count { it.title.lowercase().trim() in existingTitles }

        val unknownFieldsByTask = result.tasks
            .filter { it.unknownFields.isNotEmpty() }
            .map { parsed -> parsed.title to parsed.unknownFields.map { (k, v) -> "$k: $v" } }

        return Result.success(
            ImportPreview(
                newTasks = tasks,
                newAspects = newAspects.distinctBy { it.name.lowercase() },
                newCategories = newCategories.distinctBy { it.name.lowercase() },
                existingTaskCount = existingTaskCount,
                unknownFieldsByTask = unknownFieldsByTask
            )
        )
    }

    suspend fun commitImport(json: String, includeUnknownAsNotes: Boolean = false): Result<Int> {
        val result = ImportParser.parse(json)
        if (result.error != null) return Result.failure(Exception(result.error))

        val week = weekRepository.getOrCreateCurrentWeek()
        val existingTitles = db.taskDao().getAllByWeek(week.id).map { it.title.lowercase().trim() }.toSet()
        val createdTasks = mutableListOf<Task>()

        // All DB writes are atomic; notification scheduling (WorkManager) happens after.
        db.withTransaction {
            for (parsed in result.tasks) {
                if (parsed.title.lowercase().trim() in existingTitles) continue

                val aspect = parsed.aspectName?.let { aspectRepository.findOrCreateAspect(it) }
                val category = parsed.categoryName?.let { catName ->
                    aspect?.let { asp -> aspectRepository.findOrCreateCategory(asp.id, catName) }
                }
                val taskStatus = TaskStatus.from(parsed.status)
                val validDueDate = parsed.dueDate?.takeIf { DateUtil.isValidDate(it) }
                val task = Task(
                    id = UUID.randomUUID().toString(),
                    weekId = week.id,
                    title = parsed.title,
                    aspectId = aspect?.id,
                    categoryId = category?.id,
                    priority = Priority.from(parsed.priority),
                    dueDate = validDueDate,
                    hardDeadline = parsed.hardDeadline,
                    status = taskStatus,
                    completedAt = if (taskStatus == TaskStatus.COMPLETED) DateUtil.now() else null,
                    resourceValue = if (taskStatus == TaskStatus.COMPLETED) 0
                                   else ImportParser.computeResourceValue(parsed.priority, parsed.hardDeadline, parsed.estimatedMinutes, isManuallyAdded = false),
                    estimatedMinutes = parsed.estimatedMinutes,
                    isRecurring = parsed.isRecurring,
                    createdAt = DateUtil.now()
                )
                taskRepository.upsertTask(task)
                parsed.notes?.let { taskNoteRepository.addNote(task.id, it) }
                if (includeUnknownAsNotes && parsed.unknownFields.isNotEmpty()) {
                    val extraNote = parsed.unknownFields.entries.joinToString("\n") { (k, v) -> "$k: $v" }
                    taskNoteRepository.addNote(task.id, extraNote)
                }
                val timeToLog = parsed.timeLoggedMinutes
                if (timeToLog != null && timeToLog > 0) {
                    timeEntryRepository.logTime(task.id, timeToLog, "imported")
                }
                createdTasks.add(task)
            }
        }

        createdTasks.forEach { notificationRepository.scheduleForTask(it) }
        return Result.success(createdTasks.size)
    }

    private suspend fun resolveAspectFromDb(name: String): Aspect? =
        aspectRepository.findAspectByName(name)

    private suspend fun resolveCategoryFromDb(aspectId: String, name: String): Category? =
        aspectRepository.findCategoryByName(aspectId, name)
}
