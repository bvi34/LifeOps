package com.lifeops.app.data.repository

import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Groups a global-search hit; [label] is the section header shown in the results list. */
enum class SearchCategory(val label: String) {
    TASK("Tasks"),
    NOTE("Notes"),
    PROJECT("Projects"),
    PERSON("People"),
    COUNTER("Counters"),
    BOOK("Books"),
    RECIPE("Recipes"),
    FUTURE_PROJECT("Future Projects"),
    RUNBOOK("Runbooks"),
    TEMPLATE("Task Templates"),
    ACTIVITY("Activities"),
    FOOD("Foods"),
    WEATHER_LOCATION("Weather Locations")
}

/**
 * A single result. [route] is the nav destination to open on tap; null means informational only.
 * Tasks have no standalone detail route (they open in the This Week sheet), so they point at the
 * This Week tab as a best-effort jump rather than deep-linking an arbitrary week's task.
 */
data class SearchResult(
    val category: SearchCategory,
    val id: String,
    val title: String,
    val subtitle: String?,
    val route: String?
)

/**
 * Unified search across the app's named entities and note content. Reads each domain's full list
 * and filters in memory — fine for a single-user local database, and it avoids a per-domain LIKE
 * query to keep one substring/casing rule everywhere. Results are capped per category so a broad
 * query can't flood the list.
 */
class SearchRepository(private val db: LifeOpsDatabase) {

    suspend fun search(rawQuery: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = rawQuery.trim()
        if (q.length < MIN_QUERY) return@withContext emptyList()

        fun matches(vararg fields: String?) = fields.any { it?.contains(q, ignoreCase = true) == true }
        fun statusLabel(status: String) = status
            .split('_')
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        fun snippet(text: String, contextName: String): String {
            val clean = text.replace(Regex("\\s+"), " ").trim()
            val excerpt = if (clean.length <= SNIPPET_LENGTH) clean else clean.take(SNIPPET_LENGTH).trimEnd() + "…"
            return "$contextName · $excerpt"
        }

        val results = mutableListOf<SearchResult>()
        val weekById = db.weekDao().getAllSync().associateBy { it.id }
        val tasks = db.taskDao().getAll()
        val taskById = tasks.associateBy { it.id }
        val books = db.bookDao().getAll()
        val bookById = books.associateBy { it.id }
        val people = db.personDao().getAll()
        val personById = people.associateBy { it.id }
        val futureProjects = db.futureProjectDao().getAll()
        val futureProjectById = futureProjects.associateBy { it.id }
        fun taskSubtitle(taskId: String, prefix: String? = null): String? {
            val task = taskById[taskId] ?: return prefix
            val weekLabel = weekById[task.weekId]?.startDate?.let { "Week of ${DateUtil.formatDate(it)}" }
            return listOfNotNull(prefix, statusLabel(task.status), weekLabel).joinToString(" · ")
        }

        tasks
            .filter { matches(it.title) }
            .sortedByDescending { it.createdAt }
            .take(PER_CATEGORY)
            .forEach {
                results += SearchResult(SearchCategory.TASK, it.id, it.title, taskSubtitle(it.id), "this_week")
            }

        db.taskNoteDao().getAll()
            .filter { matches(it.content) }
            .sortedByDescending { it.createdAt }
            .take(PER_CATEGORY)
            .forEach { note ->
                val task = taskById[note.taskId]
                results += SearchResult(
                    SearchCategory.NOTE,
                    "task_note_${note.id}",
                    task?.title ?: "Task note",
                    snippet(note.content, taskSubtitle(note.taskId, "Task note") ?: "Task note"),
                    "this_week"
                )
            }

        db.projectDao().getAll()
            .filter { matches(it.title) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.PROJECT, it.id, it.title, null, "project_detail/${it.id}") }

        people
            .filter { matches(it.name) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.PERSON, it.id, it.name, null, "person_detail/${it.id}") }

        db.personDao().getAllNotes()
            .filter { matches(it.content) }
            .sortedByDescending { it.createdAt }
            .take(PER_CATEGORY)
            .forEach { note ->
                val person = personById[note.personId]
                results += SearchResult(
                    SearchCategory.NOTE,
                    "person_note_${note.id}",
                    person?.name ?: "Person note",
                    snippet(note.content, "Person note"),
                    person?.let { "person_detail/${it.id}" }
                )
            }

        db.counterDao().getAllSync()
            .filter { !it.isArchived && matches(it.name) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.COUNTER, it.id, it.name, null, "counter_detail/${it.id}") }

        books
            .filter { matches(it.title, it.author) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.BOOK, it.id, it.title, it.author, "book_detail/${it.id}") }

        db.bookDao().getAllNotes()
            .filter { matches(it.content) }
            .sortedByDescending { it.createdAt }
            .take(PER_CATEGORY)
            .forEach { note ->
                val book = bookById[note.bookId]
                results += SearchResult(
                    SearchCategory.NOTE,
                    "book_note_${note.id}",
                    book?.title ?: "Book note",
                    snippet(note.content, "Book note"),
                    book?.let { "book_detail/${it.id}" }
                )
            }

        db.recipeDao().getAll()
            .filter { matches(it.name) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.RECIPE, it.id, it.name, null, "recipe_detail/${it.id}") }

        futureProjects
            .filter { matches(it.title) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.FUTURE_PROJECT, it.id, it.title, null, "future_project_detail/${it.id}") }

        db.futureProjectDao().getAllNotes()
            .filter { matches(it.content) }
            .sortedByDescending { it.createdAt }
            .take(PER_CATEGORY)
            .forEach { note ->
                val project = futureProjectById[note.projectId]
                results += SearchResult(
                    SearchCategory.NOTE,
                    "future_project_note_${note.id}",
                    project?.title ?: "Future-project note",
                    snippet(note.content, "Future-project note"),
                    project?.let { "future_project_detail/${it.id}" }
                )
            }

        db.runbookDao().getAll()
            .filter { matches(it.name) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.RUNBOOK, it.id, it.name, null, "runbooks") }

        db.templateDao().getAll()
            .filter { matches(it.name) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.TEMPLATE, it.id, it.name, null, "templates") }

        db.activityTemplateDao().getAll()
            .filter { matches(it.name) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.ACTIVITY, it.id, it.name, null, "activities") }

        db.foodItemDao().getAll()
            .filter { matches(it.name, it.brand) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.FOOD, it.id, it.name, it.brand, "this_week") }

        db.weatherDao().getAllLocations()
            .filter { matches(it.name) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.WEATHER_LOCATION, it.id, it.name, null, "weather") }

        results
    }

    companion object {
        private const val MIN_QUERY = 2
        private const val PER_CATEGORY = 20
        private const val SNIPPET_LENGTH = 96
    }
}
