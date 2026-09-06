package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What should happen to the LifeOps task standing for a card.
 *
 * Every branch here is a way the two sides can be out of step, and each of them is ordinary rather
 * than exotic: a week closes without the work being done, somebody deletes the task, a card is
 * dragged into Done, a date is moved, a project is renamed. The decision is pure so all of it can
 * be asked without LifeOps, a database or a clock.
 */
class CardTasksTest {

    private val today = LocalDate.of(2026, 3, 14)
    private val now = 1_700_000_000_000L

    private fun card(
        due: LocalDate? = today.plusDays(3),
        title: String = "Rewrite the dock scene",
        publish: Boolean = true
    ) = BoardCard(
        id = "c1",
        columnId = "doing",
        title = title,
        notes = null,
        sortOrder = 0,
        dueOn = due?.toEpochDay(),
        publishToLifeOps = publish
    )

    private fun snapshot(
        card: BoardCard = card(),
        inDone: Boolean = false,
        link: CardTasks.TaskLink = CardTasks.TaskLink.NONE
    ) = CardTasks.CardSnapshot(card, "The Kestrel", inDone, link)

    private fun task(
        id: String = "t1",
        title: String = "The Kestrel: Rewrite the dock scene",
        due: LocalDate? = today.plusDays(3),
        completed: Boolean = false,
        open: Boolean = true
    ) = CardTasks.PublishedTask(id, title, due, completed, if (completed) now else null, open)

    private fun decide(
        snapshot: CardTasks.CardSnapshot,
        task: CardTasks.PublishedTask? = null
    ) = CardTasks.decide(snapshot, task, today, now)

    // ------------------------------------------------------------------ publishing

    @Test
    fun `a dated card with no task gets one, titled with its project`() {
        val action = decide(snapshot())

        assertEquals(
            CardTasks.Action.Publish(
                due = today.plusDays(3),
                title = "The Kestrel: Rewrite the dock scene",
                note = CardTasks.note(today.plusDays(3), today)
            ),
            action
        )
    }

    @Test
    fun `a card with no date is never published`() {
        // There is nothing a planner could place. Most cards are this.
        assertEquals(CardTasks.Action.Idle, decide(snapshot(card(due = null))))
    }

    @Test
    fun `a card switched off is never published`() {
        assertEquals(CardTasks.Action.Idle, decide(snapshot(card(publish = false))))
    }

    @Test
    fun `a card already finished here is never published`() {
        assertEquals(CardTasks.Action.Idle, decide(snapshot(inDone = true)))
    }

    // ------------------------------------------------------------------ a tick outranks everything

    @Test
    fun `a ticked task finishes the card, whatever else is out of step`() {
        // Date moved, title changed, card switched off — none of it matters. It was done.
        val action = decide(
            snapshot(card(due = today.plusDays(40), publish = false), link = CardTasks.TaskLink("t1", 0L)),
            task(completed = true, open = false)
        )

        assertEquals(CardTasks.Action.MarkDone("t1", now), action)
    }

    @Test
    fun `a tick with no recorded time is dated now rather than dropped`() {
        val action = decide(
            snapshot(link = CardTasks.TaskLink("t1", 0L)),
            CardTasks.PublishedTask("t1", "x", null, completed = true, completedAtMillis = null, open = false)
        )

        assertEquals(CardTasks.Action.MarkDone("t1", now), action)
    }

    // ------------------------------------------------------------------ taking one back off

    @Test
    fun `dragging a card into done takes its task off the week`() {
        val action = decide(snapshot(inDone = true, link = CardTasks.TaskLink("t1", 0L)), task())

        assertEquals(CardTasks.Action.Retire("t1"), action)
    }

    @Test
    fun `dropping the date, or the switch, takes the task off too`() {
        assertEquals(
            CardTasks.Action.Retire("t1"),
            decide(snapshot(card(due = null), link = CardTasks.TaskLink("t1", 0L)), task())
        )
        assertEquals(
            CardTasks.Action.Retire("t1"),
            decide(snapshot(card(publish = false), link = CardTasks.TaskLink("t1", 0L)), task())
        )
    }

    @Test
    fun `a task stranded in a closed week is forgotten rather than deleted`() {
        // Deleting it would edit a week that has already been closed and reviewed.
        val action = decide(
            snapshot(inDone = true, link = CardTasks.TaskLink("t1", 0L)),
            task(open = false)
        )

        assertEquals(CardTasks.Action.Forget, action)
    }

    // ------------------------------------------------------------------ keeping it in step

    @Test
    fun `moving the date moves the task`() {
        val moved = today.plusDays(10)
        val action = decide(
            snapshot(card(due = moved), link = CardTasks.TaskLink("t1", today.plusDays(3).toEpochDay())),
            task()
        )

        assertEquals(
            CardTasks.Action.Reschedule("t1", moved, "The Kestrel: Rewrite the dock scene"),
            action
        )
    }

    @Test
    fun `renaming the card renames the task`() {
        val action = decide(
            snapshot(card(title = "Rewrite the harbour scene"), link = CardTasks.TaskLink("t1", 0L)),
            task()
        )

        assertEquals(
            CardTasks.Action.Reschedule("t1", today.plusDays(3), "The Kestrel: Rewrite the harbour scene"),
            action
        )
    }

    @Test
    fun `a task that already says the right thing is left alone`() {
        assertEquals(
            CardTasks.Action.Idle,
            decide(snapshot(link = CardTasks.TaskLink("t1", 0L)), task())
        )
    }

    // ------------------------------------------------------------------ the two ways a link goes stale

    @Test
    fun `a link pointing at a task LifeOps no longer has is forgotten`() {
        val action = decide(snapshot(link = CardTasks.TaskLink("t1", 0L)), task = null)

        assertEquals(CardTasks.Action.Forget, action)
    }

    @Test
    fun `a task deleted on purpose is not put straight back`() {
        // Published for this very day once already and it is not there now: somebody took it off
        // their week deliberately, and re-adding it would be the app arguing with them.
        val action = decide(
            snapshot(link = CardTasks.TaskLink(null, today.plusDays(3).toEpochDay())),
            task = null
        )

        assertEquals(CardTasks.Action.Idle, action)
    }

    @Test
    fun `moving the date after deleting the task publishes again`() {
        // The remembered day no longer matches, so this is a new deadline rather than the one that
        // was refused — and it deserves a task.
        val action = decide(
            snapshot(card(due = today.plusDays(9)), link = CardTasks.TaskLink(null, today.plusDays(3).toEpochDay())),
            task = null
        )

        assertTrue(action is CardTasks.Action.Publish)
        assertEquals(today.plusDays(9), (action as CardTasks.Action.Publish).due)
    }

    @Test
    fun `work that outlived its week goes onto this one`() {
        // The week closed without it being done. The card still wants doing, so the task is
        // published again rather than left in a week nobody can tick.
        val action = decide(
            snapshot(link = CardTasks.TaskLink("t1", today.plusDays(3).toEpochDay())),
            task(open = false)
        )

        assertTrue(action is CardTasks.Action.Publish)
    }

    // ------------------------------------------------------------------ what it says

    @Test
    fun `the project leads the title, because a week is read across a dozen unrelated things`() {
        assertEquals(
            "The Kestrel: Rewrite the dock scene",
            CardTasks.title("  The Kestrel  ", "  Rewrite the dock scene  ")
        )
    }

    @Test
    fun `the note says where it came from and what ticking it will do`() {
        val note = CardTasks.note(today.plusDays(3), today)

        assertTrue(note, note.startsWith("From Project — due in 3 days."))
        assertTrue(note, note.endsWith("Ticking this here moves the card to done there."))
    }
}
