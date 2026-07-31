package com.lifeops.app.util

import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskStatus
import java.time.LocalDate

/**
 * Builds the "happening today" rotation for the This Week event banner: calendar events (busy
 * blocks) occurring on the date, followed by still-open tasks due that day. Pure and Android-free
 * so the selection and ordering rules stay JVM-testable, matching BusyBlocks/BestTime/Recurrence.
 */
object TodayEvents {

    enum class Kind { CALENDAR, TASK_DUE }

    data class Item(
        val label: String,
        val detail: String?,
        val kind: Kind,
        val taskId: String? = null
    )

    /**
     * Today's timeline: busy blocks active on [date] first — sorted by start time, each labelled
     * with its clock range — then tasks still due on [date]. Completed/skipped tasks and tasks with
     * no due date (or a due date other than [date]) are excluded: the banner surfaces only what
     * still needs attention today.
     */
    fun forDate(tasks: List<Task>, blocks: List<BusyBlock>, date: LocalDate): List<Item> {
        val dateStr = date.toString()
        val calendarItems = blocks
            .filter { BusyBlocks.occursOn(it, date) }
            .sortedBy { it.startMinutes }
            .map { block ->
                Item(
                    label = block.title,
                    detail = "${formatClock(block.startMinutes)}–${formatClock(block.endMinutes)}",
                    kind = Kind.CALENDAR
                )
            }
        val dueItems = tasks
            .filter { it.status == TaskStatus.PENDING && it.dueDate == dateStr }
            .map { task ->
                Item(
                    label = task.title,
                    detail = "Due today",
                    kind = Kind.TASK_DUE,
                    taskId = task.id
                )
            }
        return calendarItems + dueItems
    }

    /** Minutes-past-midnight → 12-hour clock, e.g. 540 → "9:00 AM", 1035 → "5:15 PM". */
    fun formatClock(minutes: Int): String {
        val normalized = ((minutes % (24 * 60)) + 24 * 60) % (24 * 60)
        val hour24 = normalized / 60
        val minute = normalized % 60
        val hour12 = (hour24 % 12).let { if (it == 0) 12 else it }
        val suffix = if (hour24 < 12) "AM" else "PM"
        return "%d:%02d %s".format(hour12, minute, suffix)
    }
}
