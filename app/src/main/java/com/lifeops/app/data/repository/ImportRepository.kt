package com.lifeops.app.data.repository

import com.lifeops.app.data.model.*
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ImportParser
import java.util.UUID

class ImportRepository(
    private val aspectRepository: AspectRepository,
    private val taskRepository: TaskRepository,
    private val weekRepository: WeekRepository,
    private val notificationRepository: NotificationRepository,
    private val taskNoteRepository: TaskNoteRepository
) {
    suspend fun previewImport(json: String): Result<ImportPreview> {
        val result = ImportParser.parse(json)
        if (result.error != null) return Result.failure(Exception(result.error))

        val aspectCache = mutableMapOf<String, Aspect>()
        val categoryCache = mutableMapOf<String, Category>()
        val newAspects = mutableListOf<Aspect>()
        val newCategories = mutableListOf<Category>()

        val week = weekRepository.getOrCreateCurrentWeek()
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
            Task(
                id = UUID.randomUUID().toString(),
                weekId = week.id,
                title = parsed.title,
                aspectId = aspect?.id,
                categoryId = category?.id,
                priority = Priority.from(parsed.priority),
                dueDate = parsed.dueDate,
                hardDeadline = parsed.hardDeadline,
                status = TaskStatus.PENDING,
                resourceValue = ImportParser.computeResourceValue(parsed.priority, parsed.hardDeadline),
                createdAt = DateUtil.now()
            )
        }

        return Result.success(
            ImportPreview(
                newTasks = tasks,
                newAspects = newAspects.distinctBy { it.name.lowercase() },
                newCategories = newCategories.distinctBy { it.name.lowercase() },
                existingTaskCount = 0
            )
        )
    }

    suspend fun commitImport(json: String): Result<Int> {
        val result = ImportParser.parse(json)
        if (result.error != null) return Result.failure(Exception(result.error))

        val week = weekRepository.getOrCreateCurrentWeek()
        var count = 0

        result.tasks.forEach { parsed ->
            val aspect = parsed.aspectName?.let { aspectRepository.findOrCreateAspect(it) }
            val category = parsed.categoryName?.let { catName ->
                aspect?.let { asp -> aspectRepository.findOrCreateCategory(asp.id, catName) }
            }
            val task = Task(
                id = UUID.randomUUID().toString(),
                weekId = week.id,
                title = parsed.title,
                aspectId = aspect?.id,
                categoryId = category?.id,
                priority = Priority.from(parsed.priority),
                dueDate = parsed.dueDate,
                hardDeadline = parsed.hardDeadline,
                status = TaskStatus.PENDING,
                resourceValue = ImportParser.computeResourceValue(parsed.priority, parsed.hardDeadline),
                createdAt = DateUtil.now()
            )
            taskRepository.upsertTask(task)
            // Convert the JSON notes string into the first TaskNote entry
            parsed.notes?.let { taskNoteRepository.addNote(task.id, it) }
            notificationRepository.scheduleForTask(task)
            count++
        }
        return Result.success(count)
    }

    private suspend fun resolveAspectFromDb(name: String): Aspect? =
        aspectRepository.findAspectByName(name)

    private suspend fun resolveCategoryFromDb(aspectId: String, name: String): Category? =
        aspectRepository.findCategoryByName(aspectId, name)
}
