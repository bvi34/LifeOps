package com.project.app.logic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The round, driven against a week planner and a store that are both fakes.
 *
 * `CardTasksTest` asks what *should* happen to one card; this asks whether the round does it — and,
 * more to the point, whether it does the right thing when several cards disagree with each other or
 * when the planner answers differently from what was on file a moment ago. Those are the cases that
 * cannot be reached by reasoning about one card at a time.
 */
class CardRoundTest {

    private val today = LocalDate.of(2026, 3, 14)
    private val now = 1_700_000_000_000L

    /** A week planner that records what it was asked to do. */
    private class FakeWeek(
        var published: MutableList<Triple<String, LocalDate, String>> = mutableListOf(),
        var rescheduled: MutableList<Triple<String, LocalDate, String>> = mutableListOf(),
        var retired: MutableList<String> = mutableListOf(),
        val tasks: MutableMap<String, CardTasks.PublishedTask> = mutableMapOf(),
        /** Ids handed out in order; null means the planner declined. */
        val handOut: MutableList<String?> = mutableListOf(),
        var rescheduleSucceeds: Boolean = true
    ) : CardWeek {
        override suspend fun publish(title: String, due: LocalDate, note: String): String? {
            published += Triple(title, due, note)
            return if (handOut.isEmpty()) "t-${published.size}" else handOut.removeAt(0)
        }

        override suspend fun reschedule(taskId: String, due: LocalDate, title: String): Boolean {
            rescheduled += Triple(taskId, due, title)
            return rescheduleSucceeds
        }

        override suspend fun retire(taskId: String): Boolean {
            retired += taskId
            return true
        }

        override suspend fun state(taskId: String): CardTasks.PublishedTask? = tasks[taskId]
    }

    /** A store holding snapshots, recording the links written back and the cards finished. */
    private class FakeStore(
        var snapshots: MutableList<CardTasks.CardSnapshot>,
        val links: MutableMap<String, CardTasks.TaskLink> = mutableMapOf(),
        val completed: MutableList<Pair<String, Long>> = mutableListOf(),
        var completeSucceeds: Boolean = true
    ) : CardStore {
        override suspend fun cardSnapshots(): List<CardTasks.CardSnapshot> = snapshots

        override suspend fun setCardLink(cardId: String, taskId: String?, publishedDue: Long?) {
            links[cardId] = CardTasks.TaskLink(taskId, publishedDue)
            snapshots = snapshots.map {
                if (it.card.id == cardId) it.copy(link = links.getValue(cardId)) else it
            }.toMutableList()
        }

        override suspend fun completeFromWeek(cardId: String, completedAt: Long): Boolean {
            completed += cardId to completedAt
            return completeSucceeds
        }
    }

    private fun snapshot(
        id: String,
        due: LocalDate? = today.plusDays(3),
        title: String = "Card $id",
        project: String = "The Kestrel",
        inDone: Boolean = false,
        link: CardTasks.TaskLink = CardTasks.TaskLink.NONE
    ) = CardTasks.CardSnapshot(
        card = BoardCard(
            id = id,
            columnId = if (inDone) "done" else "doing",
            title = title,
            notes = null,
            sortOrder = 0,
            dueOn = due?.toEpochDay()
        ),
        projectName = project,
        inDoneColumn = inDone,
        link = link
    )

    private suspend fun run(week: FakeWeek, store: FakeStore, onPublished: () -> Unit = {}) =
        CardRound(week, store, onPublished).run(today, now)

    // ------------------------------------------------------------------ the ordinary pass

    @Test
    fun `a dated card is published and the link is written back`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(mutableListOf(snapshot("c1")))

        val report = run(week, store)

        assertEquals(1, report.published)
        assertEquals("The Kestrel: Card c1", week.published.single().first)
        assertEquals(today.plusDays(3), week.published.single().second)
        assertEquals(
            CardTasks.TaskLink("t-1", today.plusDays(3).toEpochDay()),
            store.links["c1"]
        )
    }

    @Test
    fun `a round that publishes nothing says so, and does not report having published`() = runTest {
        var published = false
        val week = FakeWeek()
        val store = FakeStore(mutableListOf(snapshot("c1", due = null)))

        val report = run(week, store) { published = true }

        assertTrue(week.published.isEmpty())
        assertEquals(false, report.didAnything)
        // The gate on the completion listener hangs off this: an install that never published
        // cannot own a task that was just ticked.
        assertEquals(false, published)
    }

    @Test
    fun `an install that publishes is remembered as having published`() = runTest {
        var published = false

        run(FakeWeek(), FakeStore(mutableListOf(snapshot("c1")))) { published = true }

        assertTrue(published)
    }

    // ------------------------------------------------------------------ what only a round can get wrong

    @Test
    fun `two cards cannot end up sharing one task`() = runTest {
        // Publishing adopts an open task of the same title rather than adding a second beside it,
        // which is right for a task you wrote by hand and wrong when it already belongs to another
        // card — one tick would otherwise finish both.
        val week = FakeWeek(handOut = mutableListOf("shared", "shared"))
        val store = FakeStore(
            mutableListOf(snapshot("c1", title = "Same"), snapshot("c2", title = "Same"))
        )

        val report = run(week, store)

        assertEquals(1, report.published)
        assertEquals(CardTasks.TaskLink("shared", today.plusDays(3).toEpochDay()), store.links["c1"])
        // The loser goes without a task rather than sharing one.
        assertNull(store.links["c2"]?.taskId)
    }

    @Test
    fun `a card carried into a new week re-points at the row that now exists`() = runTest {
        // A week that closes without the work being done mints a new row under a new id; the stored
        // id then names a fossil. The round follows the hop before deciding anything.
        val week = FakeWeek(
            tasks = mutableMapOf(
                "old" to CardTasks.PublishedTask(
                    id = "new",
                    title = "The Kestrel: Card c1",
                    dueDate = today.plusDays(3),
                    completed = false,
                    completedAtMillis = null,
                    open = true
                )
            )
        )
        val store = FakeStore(
            mutableListOf(snapshot("c1", link = CardTasks.TaskLink("old", today.plusDays(3).toEpochDay())))
        )

        val report = run(week, store)

        assertEquals("new", store.links["c1"]?.taskId)
        // And having re-pointed, it finds nothing else to do — the new row already says the right
        // thing.
        assertEquals(false, report.didAnything)
        assertTrue(week.published.isEmpty())
    }

    @Test
    fun `a task that vanishes between the read and the write is forgotten, not retried forever`() = runTest {
        val week = FakeWeek(
            rescheduleSucceeds = false,
            tasks = mutableMapOf(
                "t1" to CardTasks.PublishedTask("t1", "stale title", today, false, null, open = true)
            )
        )
        val store = FakeStore(
            mutableListOf(snapshot("c1", link = CardTasks.TaskLink("t1", today.toEpochDay())))
        )

        val report = run(week, store)

        assertEquals(1, report.forgotten)
        assertNull(store.links["c1"]?.taskId)
        // The day it was published for is kept, which is what stops the next round putting back a
        // task somebody deleted.
        assertEquals(today.toEpochDay(), store.links["c1"]?.publishedDue)
    }

    @Test
    fun `a tick finishes the card and the round says it did`() = runTest {
        val week = FakeWeek(
            tasks = mutableMapOf(
                "t1" to CardTasks.PublishedTask("t1", "x", today, completed = true, completedAtMillis = 42L, open = false)
            )
        )
        val store = FakeStore(mutableListOf(snapshot("c1", link = CardTasks.TaskLink("t1", 0L))))

        val report = run(week, store)

        assertEquals(1, report.completed)
        assertEquals("c1" to 42L, store.completed.single())
    }

    @Test
    fun `a card already finished here counts the tick once`() = runTest {
        // The store reports that nothing moved, so a second round over the same tick does not count
        // it again.
        val week = FakeWeek(
            tasks = mutableMapOf(
                "t1" to CardTasks.PublishedTask("t1", "x", today, completed = true, completedAtMillis = 42L, open = false)
            )
        )
        val store = FakeStore(
            mutableListOf(snapshot("c1", link = CardTasks.TaskLink("t1", 0L))),
            completeSucceeds = false
        )

        assertEquals(0, run(week, store).completed)
    }

    @Test
    fun `a card taken off the week loses both halves of its link`() = runTest {
        // Both, because a card switched back on should put itself on the week again rather than
        // think it already has.
        val week = FakeWeek(
            tasks = mutableMapOf(
                "t1" to CardTasks.PublishedTask("t1", "x", today, false, null, open = true)
            )
        )
        val store = FakeStore(
            mutableListOf(snapshot("c1", inDone = true, link = CardTasks.TaskLink("t1", today.toEpochDay())))
        )

        val report = run(week, store)

        assertEquals(1, report.retired)
        assertEquals(listOf("t1"), week.retired)
        assertEquals(CardTasks.TaskLink(null, null), store.links["c1"])
    }

    @Test
    fun `a planner that declines still records the attempt, so the round stops asking`() = runTest {
        val week = FakeWeek(handOut = mutableListOf(null))
        val store = FakeStore(mutableListOf(snapshot("c1")))

        val report = run(week, store)

        assertEquals(0, report.published)
        // Recorded as published-for-that-day with no task, which is what stops every later round
        // hammering a week that is not going to give it one.
        assertEquals(CardTasks.TaskLink(null, today.plusDays(3).toEpochDay()), store.links["c1"])
    }

    @Test
    fun `cards from different projects are all reconciled in one pass`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(
            mutableListOf(
                snapshot("c1", project = "The Kestrel"),
                snapshot("c2", project = "The app"),
                snapshot("c3", due = null)
            )
        )

        val report = run(week, store)

        assertEquals(2, report.published)
        assertEquals(
            listOf("The Kestrel: Card c1", "The app: Card c2"),
            week.published.map { it.first }
        )
    }
}
