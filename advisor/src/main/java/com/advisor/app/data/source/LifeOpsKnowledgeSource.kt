package com.advisor.app.data.source

import android.content.Context
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp
import com.lifeops.app.data.db.LifeOpsDatabase

/**
 * Reads LifeOps' own database and flattens the parts worth reasoning over — tasks, aspects,
 * projects, milestones — into [KnowledgeDocument]s. It reads entity rows directly (no repository
 * construction, no writes) so it stays a thin, low-coupling consumer of LifeOps' data.
 */
class LifeOpsKnowledgeSource(context: Context) : KnowledgeSource {

    private val appContext = context.applicationContext
    override val source = SourceApp.LIFEOPS

    override suspend fun load(): List<KnowledgeDocument> {
        val db = LifeOpsDatabase.getInstance(appContext)
        val docs = ArrayList<KnowledgeDocument>()

        // Aspects give the model the vocabulary of the user's life areas.
        val aspects = db.aspectDao().getAllSync()
        val aspectName = aspects.associate { it.id to it.name }
        for (aspect in aspects) {
            docs += KnowledgeDocument(
                id = "lifeops:aspect:${aspect.id}",
                source = source,
                kind = "aspect",
                title = aspect.name,
                body = buildString {
                    append("Aspect (life area): ").append(aspect.name)
                    if (aspect.isArchived) append(" — archived")
                }
            )
        }

        // Tasks — the core of "what am I doing this week / did I do X".
        for (task in db.taskDao().getAll()) {
            val aspect = task.aspectId?.let { aspectName[it] }
            docs += KnowledgeDocument(
                id = "lifeops:task:${task.id}",
                source = source,
                kind = "task",
                title = task.title,
                body = buildString {
                    append("Task: ").append(task.title)
                    append(". Status: ").append(task.status)
                    append(". Priority: ").append(task.priority)
                    if (aspect != null) append(". Aspect: ").append(aspect)
                    task.dueDate?.let { append(". Due: ").append(it) }
                    task.estimatedMinutes?.let { append(". Estimate: ").append(it).append(" min") }
                    task.completedAt?.let { append(". Completed: ").append(it) }
                }
            )
        }

        // Projects — longer efforts that group tasks.
        for (project in db.projectDao().getAll()) {
            val aspect = project.aspectId?.let { aspectName[it] }
            docs += KnowledgeDocument(
                id = "lifeops:project:${project.id}",
                source = source,
                kind = "project",
                title = project.title,
                body = buildString {
                    append("Project: ").append(project.title)
                    append(". Status: ").append(project.status)
                    if (aspect != null) append(". Aspect: ").append(aspect)
                    project.description?.takeIf { it.isNotBlank() }?.let { append(". ").append(it) }
                }
            )
        }

        // Milestones — once-in-a-lifetime accomplishments.
        for (milestone in db.milestoneDao().getAll()) {
            val aspect = milestone.aspectId?.let { aspectName[it] }
            docs += KnowledgeDocument(
                id = "lifeops:milestone:${milestone.id}",
                source = source,
                kind = "milestone",
                title = milestone.title,
                body = buildString {
                    append("Milestone: ").append(milestone.title)
                    append(" (").append(milestone.points).append(" pts)")
                    append(". Achieved: ").append(milestone.achievedAt)
                    if (aspect != null) append(". Aspect: ").append(aspect)
                    milestone.description?.takeIf { it.isNotBlank() }?.let { append(". ").append(it) }
                }
            )
        }

        return docs
    }
}
