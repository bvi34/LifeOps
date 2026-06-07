package com.lifeops.app.data.repository

import com.lifeops.app.data.model.*
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ImportParser
import java.util.UUID

class ImportRepository(
    private val aspectRepository: AspectRepository,
    private val taskRepository: TaskRepository,
    private val weekRepository: WeekRepository,
    private val notificationRepository: NotificationRepository
) {
    /**
     * Parse JSON and compute a preview without writing to DB.
     * Uses DB to find existing aspects/categories case-insensitively,
     * so the diff accurately shows only truly new items.
     */
    suspend fun previewImport(json: String): Result<ImportPreview> {
        val result = ImportParser.parse(json)
        if (result.error != null) return Result.failure(Exception(result.error))

        // Resolve aspects and categories against DB (same logic as commitImport)
        val aspectCache = mutableMapOf<String, Aspect>()   // lowercase name → resolved aspect
        val categoryCache = mutableMapOf<String, Category>() // "asp/cat" lowercase → resolved category
        val newAspects = mutableListOf<Aspect>()
        val newCategories = mutableListOf<Category>()

        // Pull all existing aspects/categories once to avoid N+1 queries in preview
        val existingAspects = mutableMapOf<String, Aspect>()  // lowercase name → aspect from DB placeholder
        val existingCategories = mutableMapOf<String, Category>()

        val week = weekRepository.getOrCreateCurrentWeek()
        val tasks = result.tasks.map { parsed ->
            val aspect: Aspect? = parsed.aspectName?.let { aspName ->
                val key = aspName.lowercase()
                aspectCache.getOrPut(key) {
                    // Check DB
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
                notes = parsed.notes,
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
                notes = parsed.notes,
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
            notificationRepository.scheduleForTask(task)
            count++
        }
        return Result.success(count)
    }

    // These delegate to AspectRepository's DAO queries without creating new records
    private suspend fun resolveAspectFromDb(name: String): Aspect? =
        aspectRepository.findAspectByName(name)

    private suspend fun resolveCategoryFromDb(aspectId: String, name: String): Category? =
        aspectRepository.findCategoryByName(aspectId, name)
}
