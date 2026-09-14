package com.project.app.connection

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.operations.connectkit.ConnectionError
import com.operations.connectkit.ConnectionParams
import com.operations.connectkit.ConnectionResult
import com.project.app.data.db.ProjectDatabase
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.LoreCategory
import com.project.app.logic.OutlineStatus
import com.project.app.logic.ProjectKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Project's routes, dispatched for real against a real database.
 *
 * Tested end to end rather than by unit-testing handlers, because the interesting failures are the
 * ones that live between the parts: an address that reaches the wrong dispatcher, a name that
 * resolves to two projects, a date that will not parse, a card id from another board. A handler
 * tested in isolation would answer all of those with whatever its test set up.
 *
 * These are also the routes a *language model* will be calling, one day, from a sentence somebody
 * spoke. That raises the stakes on every refusal below: the difference between "no project called
 * that" and quietly writing into the nearest one is the difference between a tool and a hazard.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectConnectionsTest {

    private lateinit var db: ProjectDatabase
    private lateinit var repo: ProjectRepository
    private lateinit var dispatcher: com.operations.connectkit.ConnectionDispatcher

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).build()
        repo = ProjectRepository(db.projectDao())
        dispatcher = ProjectConnections.buildDispatcher(repo)
    }

    @After
    fun tearDown() = db.close()

    private val dao get() = db.projectDao()

    private suspend fun call(address: String, vararg params: Pair<String, Any?>): ConnectionResult =
        dispatcher.dispatch(address, ConnectionParams.of(*params))

    private fun ConnectionResult.id(): String =
        (this as ConnectionResult.Success)["id"] as String

    private fun ConnectionResult.errorOrNull(): ConnectionError? =
        (this as? ConnectionResult.Failure)?.error

    // ------------------------------------------------------------------ the address itself

    @Test
    fun `Project answers for its own application segment and nobody else's`() = runTest {
        // The scheme reserved this segment from the start; this is the first peer to use it.
        assertEquals(
            ConnectionError.UNKNOWN_APPLICATION,
            call("/v1/LifeOps/local/project/create", "name" to "x").errorOrNull()
        )
        assertEquals(
            ConnectionError.UNSUPPORTED_VERSION,
            call("/v2/Project/local/project/create", "name" to "x").errorOrNull()
        )
        assertEquals(
            ConnectionError.MALFORMED_ADDRESS,
            call("/v1/Project/local/project").errorOrNull()
        )
        assertEquals(
            ConnectionError.ROUTE_NOT_FOUND,
            call("/v1/Project/local/project/incinerate").errorOrNull()
        )
        assertEquals(
            ConnectionError.UNKNOWN_CONNECTION,
            call("/v1/Project/telepathy/project/create", "name" to "x").errorOrNull()
        )
    }

    @Test
    fun `a missing required parameter is a typed failure, not an exception`() = runTest {
        assertEquals(
            ConnectionError.INVALID_PARAMS,
            call("/v1/Project/local/project/create").errorOrNull()
        )
    }

    // ------------------------------------------------------------------ the shelf

    @Test
    fun `a project can be created, and comes with the board its kind implies`() = runTest {
        val id = call(
            "/v1/Project/local/project/create",
            "name" to "The Kestrel", "kind" to "writing", "summary" to "A smuggler"
        ).id()

        assertEquals("The Kestrel", dao.getProject(id)?.name)
        assertEquals("A smuggler", dao.getProject(id)?.summary)
        // The route goes through the same repository the screen does, so the seeded board comes
        // along rather than being something the UI happened to add.
        assertEquals(
            listOf("Ideas", "Drafting", "Revising", "Done"),
            dao.getColumns(id).map { it.name }
        )
    }

    @Test
    fun `an unknown kind is general rather than a refusal`() = runTest {
        // Kind is vocabulary. Refusing to make somebody a project because they said "novel" is
        // pedantry, and General is a real answer.
        val id = call("/v1/Project/local/project/create", "name" to "x", "kind" to "novel").id()

        assertEquals(ProjectKind.GENERAL.key, dao.getProject(id)?.kind)
    }

    @Test
    fun `a project can be archived and brought back by name`() = runTest {
        call("/v1/Project/local/project/create", "name" to "The Kestrel").id()

        call("/v1/Project/local/project/archive", "project" to "The Kestrel")
        assertTrue(dao.allProjects().single().archived)

        call("/v1/Project/local/project/archive", "project" to "The Kestrel", "archived" to false)
        assertTrue(!dao.allProjects().single().archived)
    }

    // ------------------------------------------------------------------ naming the target

    @Test
    fun `a project is found by name or by id`() = runTest {
        val id = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)

        assertTrue(call("/v1/Project/local/card/create", "project" to "the kestrel", "title" to "a").isSuccess)
        assertTrue(call("/v1/Project/local/card/create", "project" to id, "title" to "b").isSuccess)
    }

    @Test
    fun `a name that matches nothing is a NOT_FOUND rather than a new project`() = runTest {
        repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)

        val result = call("/v1/Project/local/card/create", "project" to "The Peregrine", "title" to "a")

        assertEquals(ConnectionError.NOT_FOUND, result.errorOrNull())
        assertTrue("a card was filed somewhere", dao.allCards().isEmpty())
    }

    @Test
    fun `a name that matches two projects writes to neither, and says which`() = runTest {
        repo.shelf.addProject("Draft", ProjectKind.WRITING, null)
        repo.shelf.addProject("draft", ProjectKind.WRITING, null)

        val result = call("/v1/Project/local/card/create", "project" to "Draft", "title" to "a")

        assertEquals(ConnectionError.INVALID_PARAMS, result.errorOrNull())
        assertTrue((result as ConnectionResult.Failure).message.contains("Use its id"))
        assertTrue("a card was filed into one of them", dao.allCards().isEmpty())
    }

    // ------------------------------------------------------------------ cards

    @Test
    fun `a card with no column named starts where work starts`() = runTest {
        val projectId = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)

        val cardId = call(
            "/v1/Project/local/card/create", "project" to "The app", "title" to "Fix the thing"
        ).id()

        assertEquals(dao.getColumns(projectId).first().id, dao.getCard(cardId)?.columnId)
        assertNull("an unasked-for deadline appeared", dao.getCard(cardId)?.dueOn)
    }

    @Test
    fun `a card can be created with a deadline, which reaches the LifeOps week`() = runTest {
        repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)

        val cardId = call(
            "/v1/Project/local/card/create",
            "project" to "The Kestrel", "title" to "Rewrite the dock scene", "dueOn" to "2026-03-14"
        ).id()

        assertEquals(LocalDate.of(2026, 3, 14).toEpochDay(), dao.getCard(cardId)?.dueOn)
        // Which is what makes it publishable — the hand-off round picks this card up.
        assertTrue(repo.cardSnapshots().any { it.card.id == cardId })
    }

    @Test
    fun `a date that will not parse is refused rather than dropped`() = runTest {
        repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)

        val result = call(
            "/v1/Project/local/card/create",
            "project" to "The Kestrel", "title" to "x", "dueOn" to "next Tuesday"
        )

        assertEquals(ConnectionError.INVALID_PARAMS, result.errorOrNull())
        // A card silently created without the deadline that was the whole point of it is worse than
        // being asked to say the date again.
        assertTrue(dao.allCards().isEmpty())
    }

    @Test
    fun `a named column is used, and an unknown one is refused`() = runTest {
        val projectId = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)
        val review = dao.getColumns(projectId).first { it.name == "Review" }

        val cardId = call(
            "/v1/Project/local/card/create",
            "project" to "The app", "title" to "Fix it", "column" to "review"
        ).id()
        assertEquals(review.id, dao.getCard(cardId)?.columnId)

        assertEquals(
            ConnectionError.NOT_FOUND,
            call(
                "/v1/Project/local/card/create",
                "project" to "The app", "title" to "x", "column" to "Limbo"
            ).errorOrNull()
        )
    }

    @Test
    fun `a card can be moved, finished and deleted by id alone`() = runTest {
        val projectId = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)
        val columns = dao.getColumns(projectId)
        val cardId = call("/v1/Project/local/card/create", "project" to "The app", "title" to "Fix it").id()

        call("/v1/Project/local/card/move", "id" to cardId, "column" to "Review")
        assertEquals(columns.first { it.name == "Review" }.id, dao.getCard(cardId)?.columnId)

        call("/v1/Project/local/card/complete", "id" to cardId)
        assertEquals(columns.first { it.isDone }.id, dao.getCard(cardId)?.columnId)
        assertNotNull(dao.getCard(cardId)?.doneAt)

        call("/v1/Project/local/card/delete", "id" to cardId)
        assertNull(dao.getCard(cardId))
    }

    @Test
    fun `finishing a card through a route leaves its published task for the round to retire`() = runTest {
        val projectId = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)
        val columnId = dao.getColumns(projectId).first().id
        val cardId = repo.board.addCard(projectId, columnId, "Fix it", dueOn = 20_000L)
        repo.setCardLink(cardId, taskId = "task-1", publishedDue = 20_000L)

        call("/v1/Project/local/card/complete", "id" to cardId)

        // Clearing it here would strand the task on somebody's week with nothing left pointing at
        // it. The hand-off round sees a finished card and takes it down — one place, one decision.
        assertEquals("task-1", dao.getCard(cardId)?.lifeOpsTaskId)
    }

    @Test
    fun `a card id that is not there is a NOT_FOUND on every card route`() = runTest {
        listOf(
            call("/v1/Project/local/card/move", "id" to "nope", "column" to "Done"),
            call("/v1/Project/local/card/complete", "id" to "nope"),
            call("/v1/Project/local/card/delete", "id" to "nope")
        ).forEach { assertEquals(ConnectionError.NOT_FOUND, it.errorOrNull()) }
    }

    // ------------------------------------------------------------------ the other sections

    @Test
    fun `an outline row can be added and given a status`() = runTest {
        repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)

        val chapter = call(
            "/v1/Project/local/outline/add", "project" to "The Kestrel", "title" to "Chapter one"
        ).id()
        val scene = call(
            "/v1/Project/local/outline/add",
            "project" to "The Kestrel", "title" to "The docks", "parentId" to chapter
        ).id()

        assertEquals(chapter, dao.getOutlineNode(scene)?.parentId)

        call("/v1/Project/local/outline/setStatus", "id" to scene, "status" to "drafted")
        assertEquals(OutlineStatus.DRAFTED.key, dao.getOutlineNode(scene)?.status)

        assertEquals(
            ConnectionError.INVALID_PARAMS,
            call("/v1/Project/local/outline/setStatus", "id" to scene, "status" to "brilliant").errorOrNull()
        )
        assertEquals(
            ConnectionError.NOT_FOUND,
            call("/v1/Project/local/outline/setStatus", "id" to "nope", "status" to "done").errorOrNull()
        )
    }

    @Test
    fun `a document is created empty, ready to write in`() = runTest {
        repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)

        val docId = call(
            "/v1/Project/local/doc/create", "project" to "The Kestrel", "title" to "Scene — the docks"
        ).id()

        assertEquals("Scene — the docks", dao.getDoc(docId)?.title)
        // One empty paragraph, the same as a document made from the screen: somewhere to put the
        // cursor, and no prose this route invented.
        assertEquals(listOf(""), dao.getBlocks(docId).map { it.text })
    }

    @Test
    fun `no route can rewrite writing that already exists`() = runTest {
        // The line every route in this package sits on, asserted rather than merely intended: a
        // caller working from a misheard sentence can add things and organise them, and cannot
        // change a word of what is already written.
        listOf(
            "/v1/Project/local/doc/replace",
            "/v1/Project/local/doc/append",
            "/v1/Project/local/block/update",
            "/v1/Project/local/doc/restore",
            "/v1/Project/local/outline/delete",
            "/v1/Project/local/project/delete"
        ).forEach { address ->
            assertEquals(
                "$address is routed",
                ConnectionError.ROUTE_NOT_FOUND,
                dispatcher.dispatch(address, ConnectionParams.EMPTY).errorOrNull()
            )
        }
    }

    @Test
    fun `a lore entry can be created with a line about it`() = runTest {
        repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)

        val id = call(
            "/v1/Project/local/lore/create",
            "project" to "The Kestrel", "name" to "Kestrel", "category" to "character",
            "summary" to "The smuggler", "body" to "Sails the [[Straits]]."
        ).id()

        val entry = dao.getLore(dao.allProjects().single().id).single { it.id == id }
        assertEquals("Kestrel", entry.name)
        assertEquals(LoreCategory.CHARACTER.key, entry.category)
        assertEquals("Sails the [[Straits]].", entry.body)
    }

    @Test
    fun `a timeline event keeps the free-text when it was given`() = runTest {
        repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)

        val id = call(
            "/v1/Project/local/timeline/add",
            "project" to "The Kestrel", "title" to "The coronation",
            "when" to "the spring after the fire", "era" to "Before"
        ).id()

        val event = dao.getTimeline(dao.allProjects().single().id).single { it.id == id }
        // Not parsed, not refused: the timeline reads a label as a date where it can and keeps the
        // author's order regardless.
        assertEquals("the spring after the fire", event.whenLabel)
        assertEquals("Before", event.era)
    }
}
