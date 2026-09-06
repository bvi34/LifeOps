package com.project.app.logic

import java.time.LocalDate

/**
 * What should happen to the LifeOps task standing for a board card.
 *
 * The hand-off exists because Project knows *when* a card is due and deliberately refuses to know
 * *when you will do it* — so it hands the deadline to the app that does. A card with a date puts
 * itself on the week as a task on that day; ticking it in either place finishes it in both.
 *
 * Every decision is taken here, in pure code, from a snapshot of what is true. Nothing below reads
 * a database or a clock: the round assembles the facts and this says what to do about them, which
 * is what lets the whole hand-off be tested without LifeOps or a device. It is the same split
 * Maintenance uses for upkeep, narrowed — a card has one date and one outcome, where a schedule
 * recurs and its completion mints the next occurrence.
 */
object CardTasks {

    /** What Project has recorded about the task standing for a card. */
    data class TaskLink(
        val taskId: String?,
        /** The day the task was published for, kept so a deleted task is not endlessly re-added. */
        val publishedDue: Long?
    ) {
        companion object {
            val NONE = TaskLink(null, null)
        }
    }

    /** What LifeOps holds for a published task, after any carry-forward hop has been followed. */
    data class PublishedTask(
        val id: String,
        val title: String,
        val dueDate: LocalDate?,
        val completed: Boolean,
        val completedAtMillis: Long?,
        /** Still actionable over there — this week's list, the queue, or carried forward. */
        val open: Boolean
    )

    /** One card as the round sees it, assembled before any decision is taken. */
    data class CardSnapshot(
        val card: BoardCard,
        val projectName: String,
        /** Whether the card sits in a column the board calls finished. */
        val inDoneColumn: Boolean,
        val link: TaskLink
    )

    sealed interface Action {
        /** Nothing to do. */
        data object Idle : Action

        data class Publish(val due: LocalDate, val title: String, val note: String) : Action

        data class Reschedule(val taskId: String, val due: LocalDate, val title: String) : Action

        /** Take the task off the week — the card stopped wanting one. */
        data class Retire(val taskId: String) : Action

        /** It was ticked over there; finish the card here. */
        data class MarkDone(val taskId: String, val completedAtMillis: Long) : Action

        /** Let go of a link that points at nothing, without putting anything back. */
        data object Forget : Action
    }

    /**
     * Decide what should happen to [snapshot]'s task, given what LifeOps holds ([task], null when
     * the link points at nothing).
     *
     * [today] is passed in rather than read, for the same reason it is everywhere else in this app:
     * a decision about a date has to be reproducible to be testable.
     */
    fun decide(
        snapshot: CardSnapshot,
        task: PublishedTask?,
        today: LocalDate,
        now: Long
    ): Action {
        val card = snapshot.card
        val link = snapshot.link

        // A tick is a fact, and it outranks everything else that might be out of step — including a
        // card whose date was moved while the task sat on somebody's week waiting to be done.
        if (task != null && task.completed) {
            return Action.MarkDone(task.id, task.completedAtMillis ?: now)
        }

        // What the card is asking for. A card with no date has nothing a planner could place; one
        // already finished here has nothing left to ask; one switched off is a deadline somebody
        // keeps for themselves.
        val due = card.dueOn?.let(LocalDate::ofEpochDay)
        val wanted = due != null && card.publishToLifeOps && !snapshot.inDoneColumn

        if (!wanted) {
            return when {
                // Still standing on an open week: take it down.
                task != null && task.open -> Action.Retire(task.id)
                // Stranded in a week that has already closed and been reviewed. Deleting it would
                // edit history; the link goes and the row is left exactly where it is.
                link.taskId != null -> Action.Forget
                else -> Action.Idle
            }
        }

        // The link names a task LifeOps no longer has.
        if (link.taskId != null && task == null) return Action.Forget

        val title = title(snapshot.projectName, card.title)

        if (task == null) {
            // Published for this very day once already and it is not there now: somebody deleted it
            // on purpose. Putting it straight back would be the app arguing with them.
            if (link.publishedDue == card.dueOn) return Action.Idle
            return Action.Publish(due, title, note(due, today))
        }

        // The week it was on closed without it being done. The work still needs doing, so it goes on
        // *this* week rather than sitting in a closed one nobody can tick.
        if (!task.open) return Action.Publish(due, title, note(due, today))

        return if (task.dueDate != due || task.title != title) {
            Action.Reschedule(task.id, due, title)
        } else {
            Action.Idle
        }
    }

    /**
     * "The Kestrel: Rewrite the dock scene".
     *
     * The project leads, because a week's task list is read across a dozen unrelated things and
     * "Rewrite the dock scene" on its own is a question rather than a job.
     */
    fun title(projectName: String, cardTitle: String): String =
        "${projectName.trim()}: ${cardTitle.trim()}"

    /**
     * The note riding along on the task, so somebody looking at it in LifeOps a fortnight later
     * knows where it came from and what ticking it will do.
     */
    fun note(due: LocalDate, today: LocalDate): String {
        val standing = Due.standing(due, today)?.label ?: "Due ${due}"
        return "From Project — ${standing.replaceFirstChar { it.lowercase() }}. " +
            "Ticking this here moves the card to done there."
    }
}
