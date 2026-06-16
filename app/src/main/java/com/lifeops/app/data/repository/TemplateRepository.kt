package com.lifeops.app.data.repository

import androidx.room.withTransaction
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.model.*
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ParsedTask
import com.lifeops.app.util.toModel
import com.lifeops.app.util.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class TemplateRepository(
    private val db: LifeOpsDatabase,
    private val importRepository: ImportRepository
) {
    fun observeAll(): Flow<List<Template>> =
        db.templateDao().observeAll().map { list -> list.map { it.toModel() } }

    suspend fun getAllWithTasks(): List<TemplateWithTasks> {
        val templates = db.templateDao().getAll()
        return templates.map { t ->
            TemplateWithTasks(
                template = t.toModel(),
                tasks = db.templateDao().getTasksForTemplate(t.id).map { it.toModel() }
            )
        }
    }

    suspend fun createTemplate(name: String, tasks: List<TemplateTask>): Template {
        val template = Template(UUID.randomUUID().toString(), name, DateUtil.now())
        db.withTransaction {
            db.templateDao().upsertTemplate(template.toEntity())
            db.templateDao().upsertTasks(tasks.map { it.toEntity() })
        }
        return template
    }

    suspend fun updateTemplate(template: Template, tasks: List<TemplateTask>) {
        db.withTransaction {
            db.templateDao().updateTemplate(template.toEntity())
            db.templateDao().deleteTasksForTemplate(template.id)
            db.templateDao().upsertTasks(tasks.map { it.toEntity() })
        }
    }

    suspend fun deleteTemplate(id: String) = db.templateDao().deleteTemplate(id)

    /**
     * Apply a template to a week. Each template task runs through the Phase 4 merge:
     * if a task with the same slug already exists in the week, it is skipped (incumbent
     * survives). Tasks that pass the merge have their runbook stamped if set.
     */
    suspend fun applyTemplate(templateId: String, weekId: String): Int {
        val tasks = db.templateDao().getTasksForTemplate(templateId)
        val parsed = tasks.sortedBy { it.taskOrder }.map { task ->
            ParsedTask(
                title = task.title,
                notes = emptyList(),
                aspectName = task.aspectName,
                categoryName = task.categoryName,
                priority = task.priority,
                dueDate = null,
                hardDeadline = false,
                estimatedMinutes = task.estimatedMinutes,
                runbookId = task.runbookId
            )
        }
        return importRepository.commitParsedTasks(parsed, weekId)
    }
}
