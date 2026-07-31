package com.lifeops.app.util

import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TodayEventsTest {

    private val MON = 1 shl 0 // bit 0 = Monday
    private val today = LocalDate.parse("2026-07-20") // a Monday

    private fun task(
        id: String,
        title: String,
        due: String?,
        status: TaskStatus = TaskStatus.PENDING
    ) = Task(id = id, weekId = "w", title = title, dueDate = due, status = status, createdAt = "")

    private fun weekly(title: String, days: Int, start: Int, end: Int) =
        BusyBlock(id = title, title = title, startMinutes = start, endMinutes = end, daysMask = days, createdAt = "")

    private fun oneOff(title: String, date: String, start: Int, end: Int) =
        BusyBlock(id = title, title = title, startMinutes = start, endMinutes = end, daysMask = 0, specificDate = date, createdAt = "")

    @Test
    fun `calendar events come first sorted by start time, then due tasks`() {
        val tasks = listOf(task("t1", "Pay rent", "2026-07-20"))
        val blocks = listOf(
            weekly("Standup", MON, 600, 630),   // 10:00
            weekly("Gym", MON, 540, 600)         // 09:00 — earlier
        )
        val items = TodayEvents.forDate(tasks, blocks, today)
        assertEquals(listOf("Gym", "Standup", "Pay rent"), items.map { it.label })
        assertEquals(TodayEvents.Kind.CALENDAR, items[0].kind)
        assertEquals(TodayEvents.Kind.TASK_DUE, items[2].kind)
        assertEquals("t1", items[2].taskId)
        assertEquals("Due today", items[2].detail)
    }

    @Test
    fun `only pending tasks due today are included`() {
        val tasks = listOf(
            task("t1", "Due today", "2026-07-20"),
            task("t2", "Done today", "2026-07-20", status = TaskStatus.COMPLETED),
            task("t3", "Due tomorrow", "2026-07-21"),
            task("t4", "No due date", null)
        )
        val items = TodayEvents.forDate(tasks, emptyList(), today)
        assertEquals(listOf("Due today"), items.map { it.label })
    }

    @Test
    fun `one-off block only shows on its date`() {
        val blocks = listOf(oneOff("Dentist", "2026-07-20", 660, 720))
        assertEquals(listOf("Dentist"), TodayEvents.forDate(emptyList(), blocks, today).map { it.label })
        assertTrue(TodayEvents.forDate(emptyList(), blocks, today.plusDays(1)).isEmpty())
    }

    @Test
    fun `calendar detail shows the clock range`() {
        val blocks = listOf(weekly("Standup", MON, 540, 600))
        assertEquals("9:00 AM–10:00 AM", TodayEvents.forDate(emptyList(), blocks, today).first().detail)
    }

    @Test
    fun `formatClock renders 12-hour times`() {
        assertEquals("12:00 AM", TodayEvents.formatClock(0))
        assertEquals("9:00 AM", TodayEvents.formatClock(540))
        assertEquals("12:30 PM", TodayEvents.formatClock(750))
        assertEquals("5:15 PM", TodayEvents.formatClock(1035))
    }
}
