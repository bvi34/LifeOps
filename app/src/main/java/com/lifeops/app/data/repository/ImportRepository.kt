package com.lifeops.app.data.repository

import com.lifeops.app.data.model.*
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ImportParser
import com.lifeops.app.util.ParsedTask
import java.util.UUID

class ImportRepository(
    private val aspectRepository: AspectRepository,
    private val taskRepository: TaskRepository,
    private val weekRepository: WeekRepository,
    private val notificationRepository: NotificationRepository
) {
    suspend fun previewImport(json: String): Result<ImportPreview> {
        val result = ImportParser.parse(json)
        if (result.error != null) return Result.failure(Exception(result.error))

        val newAspects = mutableListOf<Aspect>()
        val newCategories = mutableListOf<Category>()
        val aspectCache = mutableMapOf<String, Aspect>()
        val categoryCache = mutableMapOf<String, Category>()

        result.tasks.forEach { parsed ->
            parsed.aspectName?.let { aspectName ->
                if (!aspectCache.containsKey(aspectName.lowercase())) {
                    val existing = aspectRepository.observeAspects()
                    // simplified: we check name cache from parsed list
                    val aspect = Aspect(UUID.randomUUID().toString(), aspectName, "#6200EE", "star")
                    aspectCache[aspectName.lowercase()] = aspect
                    newAspects.add(aspect)
                }
                parsed.categoryName?.let { categoryName ->
                    val cacheKey = "${aspectName.lowercase()}/${categoryName.lowercase()}"
                    if (!categoryCache.containsKey(cacheKey)) {
                        val aspect = aspectCache[aspectName.lowercase()]!!
                        val category = Category(UUID.randomUUID().toString(), aspect.id, categoryName)
                        categoryCache[cacheKey] = category
                        newCategories.add(category)
                    }
                }
            }
        }

        val week = weekRepository.getOrCreateCurrentWeek()
        val tasks = result.tasks.map { parsed ->
            val aspect = parsed.aspectName?.let { aspectCache[it.lowercase()] }
            val category = parsed.categoryName?.let { catName ->
                parsed.aspectName?.let { aspName ->
                    categoryCache["${aspName.lowercase()}/${catName.lowercase()}"]
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

        return Result.success(ImportPreview(tasks, newAspects, newCategories, 0))
    }

    suspend fun commitImport(json: String): Result<Int> {
        val result = ImportParser.parse(json)
        if (result.error != null) return Result.failure(Exception(result.error))

        val week = weekRepository.getOrCreateCurrentWeek()
        var count = 0

        result.tasks.forEach { parsed ->
            val aspect = parsed.aspectName?.let {
                aspectRepository.findOrCreateAspect(it)
            }
            val category = parsed.categoryName?.let { catName ->
                aspect?.let { asp ->
                    aspectRepository.findOrCreateCategory(asp.id, catName)
                }
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
}
