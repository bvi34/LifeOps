package com.advisor.app.data.source

import android.content.Context
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp
import com.lifeops.app.data.db.LifeOpsDatabase

/**
 * Reads LifeOps' own database and flattens the parts worth reasoning over into
 * [KnowledgeDocument]s: the week's machinery (tasks, aspects, operations, milestones) *and* the
 * Collection — recipes, books, and the someday backlog — which is the half a question like "what
 * can I make with the beef" or "didn't I have an idea about X" actually lives in.
 *
 * It reads entity rows directly (no repository construction, no writes) so it stays a thin,
 * low-coupling consumer of LifeOps' data.
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

        // Operations — longer efforts that group tasks.
        for (operation in db.operationDao().getAll()) {
            val aspect = operation.aspectId?.let { aspectName[it] }
            docs += KnowledgeDocument(
                id = "lifeops:operation:${operation.id}",
                source = source,
                kind = "operation",
                title = operation.title,
                body = buildString {
                    append("Operation: ").append(operation.title)
                    append(". Status: ").append(operation.status)
                    if (aspect != null) append(". Aspect: ").append(aspect)
                    operation.description?.takeIf { it.isNotBlank() }?.let { append(". ").append(it) }
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

        // --- Collection: the reference library that outlives any one week ---

        // Books. Citation indexes its own library separately (under its own permission); this is
        // the LifeOps side of a book — the status you set, the minutes you logged, the notes you
        // took — so granting LifeOps alone still answers "what am I reading".
        val bookNotes = db.bookDao().getAllNotes().groupBy { it.bookId }
        val bookMinutes = db.bookDao().getAllTimeEntries()
            .groupBy { it.bookId }
            .mapValues { (_, entries) -> entries.sumOf { it.durationMinutes } }
        for (book in db.bookDao().getAll()) {
            docs += KnowledgeDocument(
                id = "lifeops:book:${book.id}",
                source = source,
                kind = "book",
                title = book.title,
                body = buildString {
                    // Phrasing is load-bearing: DocumentFacts reads the author out of
                    // "Book: <title> by <author>. Source: …" and the state out of "Reading state:".
                    append("Book: ").append(book.title)
                    book.author?.takeIf { it.isNotBlank() }?.let { append(" by ").append(it) }
                    append(". Source: ").append(book.sourceType ?: "LifeOps")
                    append(". Reading state: ").append(book.status)
                    book.category?.takeIf { it.isNotBlank() }?.let { append(". Reading category: ").append(it) }
                    bookMinutes[book.id]?.takeIf { it > 0 }?.let { append(". Read for ").append(it).append(" min") }
                    book.completedAt?.let { append(". Finished: ").append(it) }
                }
            )
            for (note in bookNotes[book.id].orEmpty()) {
                docs += KnowledgeDocument(
                    id = "lifeops:note:${note.id}",
                    source = source,
                    kind = "note",
                    title = book.title,
                    body = buildString {
                        append("Note on \"").append(book.title).append("\": ").append(note.content)
                    }
                )
            }
        }

        // Recipes, with their ingredient names spelled out — "what can I make with beef" is an
        // ingredient question, and the ingredient list is the only place that answer lives.
        val ingredientsByRecipe = db.recipeDao().getAllIngredients().groupBy { it.recipeId }
        val foodNames = HashMap<String, String>()
        for (id in ingredientsByRecipe.values.flatten().map { it.foodItemId }.toSet()) {
            db.foodItemDao().getById(id)?.let { foodNames[id] = it.name }
        }
        for (recipe in db.recipeDao().getAll()) {
            val ingredients = ingredientsByRecipe[recipe.id].orEmpty()
                .sortedBy { it.sortOrder }
                .mapNotNull { foodNames[it.foodItemId] }
            docs += KnowledgeDocument(
                id = "lifeops:recipe:${recipe.id}",
                source = source,
                kind = "recipe",
                title = recipe.name,
                body = buildString {
                    append("Recipe: ").append(recipe.name)
                    append(". Serves ").append(trimNumber(recipe.servings))
                    if (ingredients.isNotEmpty()) {
                        append(". Ingredients: ").append(ingredients.joinToString(", "))
                    }
                    recipe.instructions?.takeIf { it.isNotBlank() }?.let {
                        append(". Method: ").append(it.lines().filter(String::isNotBlank).joinToString(" "))
                    }
                    recipe.sourceUrl?.takeIf { it.isNotBlank() }?.let { append(". From: ").append(it) }
                }
            )
        }

        // Future operations — the someday backlog. Worth indexing precisely because it's the part of
        // the app the user forgets they wrote; "didn't I have an idea about X" is its whole job.
        val ideaNotes = db.futureOperationDao().getAllNotes().groupBy { it.operationId }
        for (idea in db.futureOperationDao().getAll()) {
            docs += KnowledgeDocument(
                id = "lifeops:idea:${idea.id}",
                source = source,
                kind = "idea",
                title = idea.title,
                body = buildString {
                    append("Future operation idea: ").append(idea.title)
                    append(" (").append(idea.status).append(')')
                    idea.content.takeIf { it.isNotBlank() }?.let { append(". ").append(it) }
                    val notes = ideaNotes[idea.id].orEmpty().joinToString(" ") { it.content }
                    if (notes.isNotBlank()) append(". Notes: ").append(notes)
                }
            )
        }

        return docs
    }

    /** Render 2.0 as "2" but keep 2.5 as "2.5" — servings read better without the trailing .0. */
    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
