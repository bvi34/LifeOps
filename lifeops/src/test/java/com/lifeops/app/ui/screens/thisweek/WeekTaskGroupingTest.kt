package com.lifeops.app.ui.screens.thisweek

import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Category
import com.lifeops.app.data.model.Priority
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list keys are the thing under test as much as the grouping is: Compose rejects a lazy list
 * that is handed the same key twice, so a collision here is a crash on the week's main screen.
 */
class WeekTaskGroupingTest {

    private fun aspect(id: String, name: String = id) =
        Aspect(id = id, name = name, color = "#336699", icon = "star")

    private fun category(id: String, aspectId: String, name: String = id) =
        Category(id = id, aspectId = aspectId, name = name)

    private fun task(
        id: String,
        aspectId: String? = null,
        categoryId: String? = null,
        status: TaskStatus = TaskStatus.PENDING,
        priority: Priority = Priority.MEDIUM,
        dueDate: String? = null,
        sortOrder: Int = 0
    ) = Task(
        id = id,
        weekId = "w1",
        title = "Task $id",
        aspectId = aspectId,
        categoryId = categoryId,
        priority = priority,
        dueDate = dueDate,
        status = status,
        createdAt = "2026-09-01T00:00:00Z",
        sortOrder = sortOrder
    )

    /** Every key the This Week list would emit for [groups], headers and rows alike. */
    private fun listKeys(groups: List<GroupedTasks>): List<String> = buildList {
        for (group in groups) {
            add(WeekTaskGrouping.aspectKey(group))
            for (catGroup in group.categories) {
                if (catGroup.category != null || group.categories.size > 1) {
                    add(WeekTaskGrouping.categoryKey(group, catGroup))
                }
                catGroup.tasks.forEach { add(it.id) }
            }
        }
    }

    private fun assertKeysUnique(groups: List<GroupedTasks>) {
        val keys = listKeys(groups)
        val duplicates = keys.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("duplicate list keys: $duplicates", duplicates.isEmpty())
    }

    // --- Keys ------------------------------------------------------------------------------

    @Test
    fun `an aspectless task carrying a category does not collide with that category's own aspect`() {
        // How this arrives in real data: a task filed from an operation that has a category but no
        // aspect (Advisor copies both across), or the same pair posted on the task/create route.
        val aspects = mapOf("a1" to aspect("a1"))
        val categories = mapOf("c1" to category("c1", "a1"))
        val groups = WeekTaskGrouping.group(
            tasks = listOf(
                task("t1", aspectId = "a1", categoryId = "c1"),
                task("t2", aspectId = null, categoryId = "c1")
            ),
            aspects = aspects,
            categories = categories,
            sortOrder = SortOrder.DEFAULT,
            searchQuery = ""
        )

        assertEquals(2, groups.size)
        assertKeysUnique(groups)
    }

    @Test
    fun `two aspects each holding a category-less bucket keep distinct keys`() {
        val aspects = mapOf("a1" to aspect("a1"), "a2" to aspect("a2"))
        val categories = mapOf("c1" to category("c1", "a1"), "c2" to category("c2", "a2"))
        val groups = WeekTaskGrouping.group(
            tasks = listOf(
                task("t1", aspectId = "a1", categoryId = "c1"),
                task("t2", aspectId = "a1", categoryId = null),
                task("t3", aspectId = "a2", categoryId = "c2"),
                task("t4", aspectId = "a2", categoryId = null),
                task("t5", aspectId = null, categoryId = null)
            ),
            aspects = aspects,
            categories = categories,
            sortOrder = SortOrder.DEFAULT,
            searchQuery = ""
        )

        assertEquals(3, groups.size)
        assertKeysUnique(groups)
    }

    @Test
    fun `a task naming a missing aspect is its own group, not the aspectless one`() {
        val groups = WeekTaskGrouping.group(
            tasks = listOf(
                task("t1", aspectId = "gone"),
                task("t2", aspectId = null)
            ),
            aspects = emptyMap(),
            categories = emptyMap(),
            sortOrder = SortOrder.DEFAULT,
            searchQuery = ""
        )

        assertEquals(2, groups.size)
        // Both read as "Uncategorized" on screen — the resolved aspect is null for each — but they
        // are two rows and must carry two keys.
        assertTrue(groups.all { it.aspect == null })
        assertNotEquals(
            WeekTaskGrouping.aspectKey(groups[0]),
            WeekTaskGrouping.aspectKey(groups[1])
        )
        assertKeysUnique(groups)
    }

    @Test
    fun `a category key cannot be forged by running two ids together`() {
        // "a" + "b-c" and "a-b" + "c" are the pair a naive join would flatten into one key.
        val aspects = mapOf("a" to aspect("a"), "a-b" to aspect("a-b"))
        val categories = mapOf("b-c" to category("b-c", "a"), "c" to category("c", "a-b"))
        val groups = WeekTaskGrouping.group(
            tasks = listOf(
                task("t1", aspectId = "a", categoryId = "b-c"),
                task("t2", aspectId = "a-b", categoryId = "c")
            ),
            aspects = aspects,
            categories = categories,
            sortOrder = SortOrder.DEFAULT,
            searchQuery = ""
        )

        assertKeysUnique(groups)
    }

    // --- Grouping --------------------------------------------------------------------------

    @Test
    fun `an aspectless group falls back to the default colour and resolves no aspect`() {
        val groups = WeekTaskGrouping.group(
            tasks = listOf(task("t1")),
            aspects = mapOf("a1" to aspect("a1")),
            categories = emptyMap(),
            sortOrder = SortOrder.DEFAULT,
            searchQuery = ""
        )

        assertEquals(1, groups.size)
        assertNull(groups[0].aspectId)
        assertNull(groups[0].aspect)
        assertEquals("#6200EE", groups[0].aspectColor)
    }

    @Test
    fun `queued tasks are left to the Future Tasks screen`() {
        val groups = WeekTaskGrouping.group(
            tasks = listOf(task("t1", status = TaskStatus.QUEUED), task("t2")),
            aspects = emptyMap(),
            categories = emptyMap(),
            sortOrder = SortOrder.DEFAULT,
            searchQuery = ""
        )

        assertEquals(listOf("t2"), groups.flatMap { g -> g.categories.flatMap { it.tasks.map { t -> t.id } } })
    }

    @Test
    fun `search filters by title and overdue keeps only pending work due by today`() {
        val tasks = listOf(
            task("t1", dueDate = "2026-09-01"),
            task("t2", dueDate = "2026-09-30"),
            task("t3", dueDate = "2026-09-01", status = TaskStatus.COMPLETED)
        )

        val overdue = WeekTaskGrouping.group(
            tasks = tasks,
            aspects = emptyMap(),
            categories = emptyMap(),
            sortOrder = SortOrder.DEFAULT,
            searchQuery = "",
            overdueOnly = true,
            today = "2026-09-20"
        )
        assertEquals(listOf("t1"), overdue.flatMap { g -> g.categories.flatMap { it.tasks.map { t -> t.id } } })

        val searched = WeekTaskGrouping.group(
            tasks = tasks,
            aspects = emptyMap(),
            categories = emptyMap(),
            sortOrder = SortOrder.DEFAULT,
            searchQuery = "task t2"
        )
        assertEquals(listOf("t2"), searched.flatMap { g -> g.categories.flatMap { it.tasks.map { t -> t.id } } })
    }

    @Test
    fun `a bucket's dominant priority comes from its pending work only`() {
        val groups = WeekTaskGrouping.group(
            tasks = listOf(
                task("t1", priority = Priority.LOW),
                task("t2", priority = Priority.CRITICAL, status = TaskStatus.COMPLETED)
            ),
            aspects = emptyMap(),
            categories = emptyMap(),
            sortOrder = SortOrder.DEFAULT,
            searchQuery = ""
        )

        assertEquals(Priority.LOW, groups.single().categories.single().dominantPriority)
    }

    @Test
    fun `sort orders apply within a bucket`() {
        val tasks = listOf(
            task("t1", priority = Priority.LOW, dueDate = null, sortOrder = 2),
            task("t2", priority = Priority.CRITICAL, dueDate = "2026-09-05", sortOrder = 0),
            task("t3", priority = Priority.MEDIUM, dueDate = "2026-09-02", sortOrder = 1)
        )
        fun ids(order: SortOrder) = WeekTaskGrouping
            .group(tasks, emptyMap(), emptyMap(), order, "")
            .single().categories.single().tasks.map { it.id }

        assertEquals(listOf("t3", "t2", "t1"), ids(SortOrder.DUE_DATE_ASC))
        assertEquals(listOf("t2", "t3", "t1"), ids(SortOrder.DUE_DATE_DESC))
        assertEquals(listOf("t2", "t3", "t1"), ids(SortOrder.PRIORITY_HIGH))
        assertEquals(listOf("t1", "t3", "t2"), ids(SortOrder.PRIORITY_LOW))
        assertEquals(listOf("t2", "t3", "t1"), ids(SortOrder.PLANNING))
    }
}
