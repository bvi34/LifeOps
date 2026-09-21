package com.lifeops.app.ui.screens.thisweek

import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Category
import com.lifeops.app.data.model.Priority
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskStatus

/**
 * One aspect's slice of the week.
 *
 * [aspectId] is what the tasks *say* they are filed under; [aspect] is what that id resolved to,
 * and is null both for a task with no aspect at all and for one naming an aspect the app can no
 * longer see. The two are kept apart because the list identifies a group by the id — see
 * [WeekTaskGrouping.aspectKey] for why that matters.
 */
data class GroupedTasks(
    val aspectId: String?,
    val aspect: Aspect?,
    val aspectColor: String,
    val categories: List<CategoryGroup>
)

data class CategoryGroup(
    val categoryId: String?,
    val category: Category?,
    val tasks: List<Task>,
    val dominantPriority: Priority?
)

/**
 * How the week's tasks are grouped for the This Week list, and how each row in it is identified.
 *
 * Kept Android-free so both halves are unit-testable on the JVM — the key half especially, because
 * a duplicate key is not a cosmetic bug: Compose rejects a lazy list that hands it the same key
 * twice, and the screen crashes as it draws.
 */
object WeekTaskGrouping {

    /**
     * Stands in for "no aspect" / "no category" in a list key, and the separator between the two.
     *
     * An id is a UUID: never empty, and never containing a bar. So nothing can be mistaken for the
     * absent-id sentinel, and no pair of ids can run together into another pair's key.
     */
    private const val NONE = ""
    private const val SEP = "|"

    /**
     * The key identifying an aspect header.
     *
     * Built from the group's [GroupedTasks.aspectId] rather than from the aspect it resolved to: a
     * task naming an aspect that isn't there reads as "Uncategorized" like an aspectless one, but it
     * is still its own group, and keying both off the resolved (null) aspect would hand the list two
     * rows with one key.
     */
    fun aspectKey(group: GroupedTasks): String = "aspect$SEP${group.aspectId ?: NONE}"

    /**
     * The key identifying a category header, namespaced by the aspect group it sits in.
     *
     * A category id alone is not unique down the list. An aspectless task can carry one (a task
     * filed from an operation that has a category but no aspect, say), which puts the same category
     * under both "Uncategorized" and its own aspect; a category re-parented in settings does the
     * same to the tasks left behind. And every group holding a category-less bucket would otherwise
     * emit the identical "no category" key. Pairing the two ids makes the key unique by
     * construction, because within a group the category ids are distinct by grouping.
     */
    fun categoryKey(group: GroupedTasks, catGroup: CategoryGroup): String =
        "cat$SEP${group.aspectId ?: NONE}$SEP${catGroup.categoryId ?: NONE}"

    /**
     * Group the week's [tasks] by aspect, then by category, applying the list's filters and sort.
     *
     * Queued (future-week) tasks are left out — they live in the Planning tab's Future Tasks screen,
     * not here. [today] is a parameter so the overdue filter is deterministic under test.
     */
    fun group(
        tasks: List<Task>,
        aspects: Map<String, Aspect>,
        categories: Map<String, Category>,
        sortOrder: SortOrder,
        searchQuery: String,
        overdueOnly: Boolean = false,
        today: String = java.time.LocalDate.now().toString()
    ): List<GroupedTasks> {
        var filtered = tasks.filter { it.status != TaskStatus.QUEUED }
        if (searchQuery.isNotBlank()) {
            filtered = filtered.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
        if (overdueOnly) {
            filtered = filtered.filter { task ->
                task.status == TaskStatus.PENDING &&
                    task.dueDate != null && task.dueDate <= today
            }
        }

        val byAspect = filtered.groupBy { it.aspectId }
        return byAspect.map { (aspectId, aspectTasks) ->
            val aspect = aspectId?.let { aspects[it] }
            val byCategory = aspectTasks.groupBy { it.categoryId }
            val categoryGroups = byCategory.map { (catId, catTasks) ->
                val sorted = sortTasks(catTasks, sortOrder)
                val dominantPriority = catTasks
                    .filter { it.status == TaskStatus.PENDING }
                    .maxByOrNull { it.priority.baseValue }
                    ?.priority
                CategoryGroup(catId, catId?.let { categories[it] }, sorted, dominantPriority)
            }
            GroupedTasks(aspectId, aspect, aspect?.color ?: DEFAULT_ASPECT_COLOR, categoryGroups)
        }
    }

    private const val DEFAULT_ASPECT_COLOR = "#6200EE"

    private fun sortTasks(tasks: List<Task>, order: SortOrder): List<Task> = when (order) {
        SortOrder.DEFAULT -> tasks
        SortOrder.DUE_DATE_ASC -> tasks.sortedWith(
            compareBy<Task> { it.dueDate == null }.thenBy { it.dueDate }
        )
        SortOrder.DUE_DATE_DESC -> tasks.sortedWith(
            compareBy<Task> { it.dueDate == null }.thenByDescending { it.dueDate }
        )
        SortOrder.PRIORITY_HIGH -> tasks.sortedByDescending { it.priority.baseValue }
        SortOrder.PRIORITY_LOW -> tasks.sortedBy { it.priority.baseValue }
        SortOrder.PLANNING -> tasks.sortedBy { it.sortOrder }
    }
}
