package com.lifeops.app.data.repository

import androidx.room.withTransaction
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.model.*
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ImportParser
import com.lifeops.app.util.ParsedTask
import com.lifeops.app.util.toSlug
import java.util.UUID
import com.lifeops.app.data.model.TaskSource

class ImportRepository(
    private val db: LifeOpsDatabase,
    private val aspectRepository: AspectRepository,
    private val taskRepository: TaskRepository,
    private val weekRepository: WeekRepository,
    private val notificationRepository: NotificationRepository,
    private val taskNoteRepository: TaskNoteRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val runbookRepository: RunbookRepository
) {
    suspend fun previewImport(json: String): Result<ImportPreview> {
        val result = ImportParser.parse(json)
        if (result.error != null) return Result.failure(Exception(result.error))

        val aspectCache = mutableMapOf<String, Aspect>()
        val categoryCache = mutableMapOf<String, Category>()
        val newAspects = mutableListOf<Aspect>()
        val newCategories = mutableListOf<Category>()

        val week = weekRepository.getOrCreateCurrentWeek()
        val existingSlugs = db.taskDao().getSlugsByWeek(week.id).toSet()

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
                resourceValue = if (taskStatus == TaskStatus.COMPLETED) 0
                               else ImportParser.computeResourceValue(parsed.priority, parsed.hardDeadline, parsed.estimatedMinutes, isManuallyAdded = false),
                estimatedMinutes = parsed.estimatedMinutes,
                isRecurring = parsed.isRecurring,
                recurrenceIntervalWeeks = if (parsed.isRecurring) parsed.recurrenceIntervalWeeks.coerceAtLeast(1) else 1,
                recurrenceDayOfMonth = if (parsed.isRecurring) parsed.recurrenceDayOfMonth else null,
                createdAt = DateUtil.now(),
                source = TaskSource.PLANNED
            )
        }

        val existingTaskCount = tasks.count { it.title.toSlug() in existingSlugs }

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
        return try {
            Result.success(commitParsedTasks(result.tasks, week.id, includeUnknownAsNotes))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Shared pipeline for all three entry paths (JSON import, template apply, one-off).
     * Phase 4 merge: slug collision → incumbent survives, incoming task is skipped.
     * Phase 7 stamp: runbookName/runbookId on ParsedTask triggers runbook stamp after create.
     */
    suspend fun commitParsedTasks(
        tasks: List<ParsedTask>,
        weekId: String,
        includeUnknownAsNotes: Boolean = false
    ): Int {
        val existingSlugs = db.taskDao().getSlugsByWeek(weekId).toMutableSet()
        val createdTasks = mutableListOf<Task>()

        db.withTransaction {
            for (parsed in tasks) {
                val slug = parsed.title.toSlug()
                if (slug in existingSlugs) continue

                val aspect = parsed.aspectName?.let { aspectRepository.findOrCreateAspect(it) }
                val category = parsed.categoryName?.let { catName ->
                    aspect?.let { asp -> aspectRepository.findOrCreateCategory(asp.id, catName) }
                }
                val taskStatus = TaskStatus.from(parsed.status)
                val validDueDate = parsed.dueDate?.takeIf { DateUtil.isValidDate(it) }
                val task = Task(
                    id = UUID.randomUUID().toString(),
                    weekId = weekId,
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
                    recurrenceIntervalWeeks = if (parsed.isRecurring) parsed.recurrenceIntervalWeeks.coerceAtLeast(1) else 1,
                    recurrenceDayOfMonth = if (parsed.isRecurring) parsed.recurrenceDayOfMonth else null,
                    createdAt = DateUtil.now(),
                    source = TaskSource.PLANNED,
                    slug = slug
                )
                taskRepository.upsertTask(task)

                for (note in parsed.notes) taskNoteRepository.addNote(task.id, note)
                if (includeUnknownAsNotes && parsed.unknownFields.isNotEmpty()) {
                    val extraNote = parsed.unknownFields.entries.joinToString("\n") { (k, v) -> "$k: $v" }
                    taskNoteRepository.addNote(task.id, extraNote)
                }
                val timeToLog = parsed.timeLoggedMinutes
                if (timeToLog != null && timeToLog > 0) {
                    timeEntryRepository.logTime(task.id, timeToLog, "imported")
                }

                // Phase 7: stamp runbook. Direct ID wins over name lookup.
                val rb = parsed.runbookId?.let { runbookRepository.getRunbookWithSteps(it) }
                    ?: parsed.runbookName?.let { runbookRepository.findByName(it) }
                rb?.let { runbookRepository.stampRunbook(task.id, it) }

                existingSlugs.add(slug)
                createdTasks.add(task)
            }
        }

        createdTasks.forEach { notificationRepository.scheduleForTask(it) }
        return createdTasks.size
    }

    private suspend fun resolveAspectFromDb(name: String): Aspect? =
        aspectRepository.findAspectByName(name)

    private suspend fun resolveCategoryFromDb(aspectId: String, name: String): Category? =
        aspectRepository.findCategoryByName(aspectId, name)
}
