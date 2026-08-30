package com.people.app.partner

/**
 * What changed on a partner's week since we last looked.
 *
 * There is no merge here, and that absence is the design. [com.people.app.sync.PersonMerge] next
 * door reconciles a record two peers each hold half of, so it needs field-wise precedence, a clock
 * and a tie-break. A partner's week needs none of that, because **it has one owner**. They publish
 * it whole on every round; we hold a copy. There is nothing to reconcile, no clock to trust, and no
 * way for the two sides to disagree — which also means a task's absence is authoritative, so
 * removals need no tombstones the way [com.people.app.sync.PersonPacket] does.
 *
 * What remains is the interesting half: saying what moved, in the words the person would use, so the
 * app can tell them what their partner did rather than silently swapping one list for another.
 */
object PartnerWeekDiff {

    enum class ChangeKind { ADDED, EDITED, COMPLETED, REOPENED, REMOVED }

    data class Change(val kind: ChangeKind, val taskId: String, val title: String)

    /**
     * What [incoming] does to [previous].
     *
     * Order is the reading order of the week, not the order the changes were noticed, so a list of
     * them reads down the days like the week does.
     */
    fun diff(previous: List<SharedTask>, incoming: List<SharedTask>): List<Change> {
        val before = previous.associateBy { it.taskId }
        val after = incoming.associateBy { it.taskId }
        val changes = ArrayList<Change>()

        for (task in sorted(incoming)) {
            val old = before[task.taskId]
            when {
                old == null -> changes += Change(ChangeKind.ADDED, task.taskId, task.title)
                old == task -> Unit
                task.done && !old.done -> changes += Change(ChangeKind.COMPLETED, task.taskId, task.title)
                !task.done && old.done -> changes += Change(ChangeKind.REOPENED, task.taskId, task.title)
                else -> changes += Change(ChangeKind.EDITED, task.taskId, task.title)
            }
        }

        for (task in sorted(previous)) {
            if (task.taskId !in after) changes += Change(ChangeKind.REMOVED, task.taskId, task.title)
        }

        return changes
    }

    /**
     * Undated work last, then by day, then by title. Both sides sort identically, so "the third one
     * down" means the same thing in both people's hands.
     *
     * Note this sorts on the record's *contents* rather than on any position the publisher chose:
     * LifeOps' own week ordering is built from aspects, priorities and resource values that were
     * deliberately left off this wire, so there is nothing to carry over and no point pretending
     * otherwise.
     */
    fun sorted(tasks: List<SharedTask>): List<SharedTask> = tasks.sortedWith(ORDER)

    private val ORDER = compareBy<SharedTask>(
        { it.dueDate == null },
        { it.dueDate.orEmpty() },
        { it.title.lowercase() },
        { it.taskId }
    )
}
