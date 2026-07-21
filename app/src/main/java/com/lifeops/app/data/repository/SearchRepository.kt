package com.lifeops.app.data.repository

import com.lifeops.app.data.db.LifeOpsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Groups a global-search hit; [label] is the section header shown in the results list. */
enum class SearchCategory(val label: String) {
    TASK("Tasks"),
    PROJECT("Projects"),
    PERSON("People"),
    COUNTER("Counters"),
    BOOK("Books"),
    RECIPE("Recipes"),
    FUTURE_PROJECT("Future Projects")
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
 * Unified search across the app's named entities. Reads each domain's full list and filters in
 * memory — fine for a single-user local database, and it avoids a per-domain LIKE query to keep
 * one substring/casing rule everywhere. Results are capped per category so a broad query can't
 * flood the list.
 */
class SearchRepository(private val db: LifeOpsDatabase) {

    suspend fun search(rawQuery: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = rawQuery.trim()
        if (q.length < MIN_QUERY) return@withContext emptyList()

        fun matches(vararg fields: String?) = fields.any { it?.contains(q, ignoreCase = true) == true }

        val results = mutableListOf<SearchResult>()

        db.taskDao().getAll()
            .filter { matches(it.title) }
            .sortedByDescending { it.createdAt }
            .take(PER_CATEGORY)
            .forEach {
                results += SearchResult(SearchCategory.TASK, it.id, it.title, it.status.replaceFirstChar(Char::uppercase), "this_week")
            }

        db.projectDao().getAll()
            .filter { matches(it.title) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.PROJECT, it.id, it.title, null, "project_detail/${it.id}") }

        db.personDao().getAll()
            .filter { matches(it.name) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.PERSON, it.id, it.name, null, "person_detail/${it.id}") }

        db.counterDao().getAllSync()
            .filter { !it.isArchived && matches(it.name) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.COUNTER, it.id, it.name, null, "counter_detail/${it.id}") }

        db.bookDao().getAll()
            .filter { matches(it.title, it.author) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.BOOK, it.id, it.title, it.author, "book_detail/${it.id}") }

        db.recipeDao().getAll()
            .filter { matches(it.name) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.RECIPE, it.id, it.name, null, "recipe_detail/${it.id}") }

        db.futureProjectDao().getAll()
            .filter { matches(it.title) }
            .take(PER_CATEGORY)
            .forEach { results += SearchResult(SearchCategory.FUTURE_PROJECT, it.id, it.title, null, "future_project_detail/${it.id}") }

        results
    }

    companion object {
        private const val MIN_QUERY = 2
        private const val PER_CATEGORY = 20
    }
}
