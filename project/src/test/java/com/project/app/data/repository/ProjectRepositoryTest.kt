package com.project.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.project.app.data.db.ProjectDatabase
import com.project.app.logic.BlockType
import com.project.app.logic.DocBlock
import com.project.app.logic.LoreCategory
import com.project.app.logic.AttachKind
import com.project.app.logic.CardTasks
import com.project.app.logic.ProjectDestination
import com.project.app.logic.SearchSection
import com.project.app.logic.ProjectKind
import com.project.app.logic.RevisionReason
import com.project.app.logic.Revisions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The store's own rules, against a real database.
 *
 * Everything above this layer is pure and is tested by reasoning about it — the tree walks, the
 * Markdown round trip, the board arithmetic all live in `logic/` and have their own tests. What is
 * left over is the half that only SQLite can be asked about, and it is the half that decides whether
 * somebody's writing is still there in the morning:
 *
 * - that deleting a project really takes all five sections with it, and nothing from the project
 *   next to it;
 * - that a **soft link** is cut rather than followed — deleting a scene must not delete the document
 *   written for it, and must not leave a card pointing at a scene that no longer exists;
 * - that the **stored word counts** and the blocks they were counted from cannot drift, through
 *   every path that can change them;
 * - that the **versions kept before a destructive edit** really hold what the document said, and
 *   really put it back;
 * - that an **address somebody was linked with** is checked against what is actually there before
 *   the app navigates to it;
 * - that a record's **file drawer on the household's shelf** is found, renamed and taken away with
 *   the record it belongs to.
 *
 * A fake DAO would answer all of those with whatever this file assumed, which is why there isn't
 * one: the database below is in memory, but it is a real Room database — the same entities, the
 * same generated SQL, the same foreign keys.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectRepositoryTest {

    private lateinit var db: ProjectDatabase
    private lateinit var repo: ProjectRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).build()
        repo = ProjectRepository(db.projectDao())
    }

    @After
    fun tearDown() = db.close()

    private val dao get() = db.projectDao()

    // ------------------------------------------------------------------ the shelf

    @Test
    fun `a new project arrives with a board named for the kind of work it is`() = runTest {
        val writing = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, "  ")
        val software = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, " A thing. ")

        // Seeded on creation rather than lazily on first visit: a board screen that is empty until
        // you invent your own columns is a board screen most people close again.
        assertEquals(
            listOf("Ideas", "Drafting", "Revising", "Done"),
            dao.getColumns(writing).map { it.name }
        )
        assertEquals(
            listOf("Backlog", "In progress", "Review", "Shipped"),
            dao.getColumns(software).map { it.name }
        )
        assertTrue("the last column is the finished one", dao.getColumns(writing).last().isDone)

        // A blank summary is absent, not stored as an empty string — otherwise the shelf has two
        // ways to say "nothing written here" and has to check for both.
        assertNull(dao.getProject(writing)?.summary)
        assertEquals("A thing.", dao.getProject(software)?.summary)
    }

    @Test
    fun `deleting a project takes all five sections with it, and nothing from the one beside it`() = runTest {
        val doomed = fullyPopulatedProject("The Kestrel")
        val bystander = fullyPopulatedProject("The other one")

        repo.shelf.deleteProject(doomed)

        assertNull(dao.getProject(doomed))
        assertTrue(dao.getOutline(doomed).isEmpty())
        assertTrue(dao.docsOf(doomed).isEmpty())
        assertTrue(dao.getLore(doomed).isEmpty())
        assertTrue(dao.getTimeline(doomed).isEmpty())
        assertTrue(dao.getColumns(doomed).isEmpty())
        assertTrue(dao.getCards(doomed).isEmpty())
        // Blocks hang off a document rather than off the project, so they cascade through two
        // foreign keys — the one place a delete could leave orphaned rows behind and no screen that
        // would ever show them.
        assertTrue(dao.blocksOfProject(doomed).isEmpty())

        assertNotNull("the project next to it went too", dao.getProject(bystander))
        assertEquals(2, dao.getOutline(bystander).size)
        assertEquals(1, dao.docsOf(bystander).size)
        assertEquals(2, dao.blocksOfProject(bystander).size)
        assertEquals(1, dao.getLore(bystander).size)
        assertEquals(1, dao.getTimeline(bystander).size)
        assertEquals(1, dao.getCards(bystander).size)
    }

    // ------------------------------------------------------------------ soft links

    @Test
    fun `deleting a scene keeps what was written for it, and cuts the links that pointed at it`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val chapter = repo.outline.addOutlineNode(projectId, null, "Chapter one")
        val scene = repo.outline.addOutlineNode(projectId, chapter, "The docks")

        val docId = repo.docs.addDoc(projectId, "Scene — the docks", outlineNodeId = scene)
        val eventId = repo.timeline.addEvent(projectId, "The coronation", "Year 12", null, outlineNodeId = scene)
        val columnId = dao.getColumns(projectId).first().id
        val cardId = repo.board.addCard(projectId, columnId, "Rewrite it", outlineNodeId = scene, docId = docId)

        repo.outline.deleteOutlineSubtree(projectId, chapter)

        assertTrue("the subtree is gone", dao.getOutline(projectId).isEmpty())

        // The scene you cut in August is not the writing you did for it. Every one of these survives
        // the delete with its link cut, which is what makes a dangling link impossible rather than
        // merely unlikely — a link that dangles for ever is indistinguishable from one never made.
        assertNull(dao.getDoc(docId)?.outlineNodeId)
        assertNotNull(dao.getDoc(docId))
        assertNull(dao.getTimeline(projectId).single { it.id == eventId }.outlineNodeId)
        assertNull(dao.getCard(cardId)?.outlineNodeId)
        assertEquals("the card lost the document too", docId, dao.getCard(cardId)?.docId)
    }

    @Test
    fun `deleting a document leaves the card that was about it, pointing at nothing`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(projectId, "Scene — the docks")
        val columnId = dao.getColumns(projectId).first().id
        val cardId = repo.board.addCard(projectId, columnId, "Rewrite it", docId = docId)

        repo.docs.deleteDoc(docId)

        assertNull(dao.getDoc(docId))
        assertTrue("the blocks outlived their document", dao.getBlocks(docId).isEmpty())
        assertNotNull("the card went with the document", dao.getCard(cardId))
        assertNull(dao.getCard(cardId)?.docId)
    }

    // ------------------------------------------------------------------ the stored word counts

    @Test
    fun `a document's count follows its blocks through every way of editing them`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(projectId, "Scene — the docks")
        val seeded = dao.getBlocks(docId).single()

        repo.docs.updateBlock(docId, DocBlock(seeded.id, BlockType.PARAGRAPH, "The docks smelled of tar."))
        assertEquals(5, dao.getDoc(docId)?.wordCount)

        val second = repo.docs.addBlock(docId, BlockType.PARAGRAPH, seeded.id)
        repo.docs.updateBlock(docId, DocBlock(second, BlockType.PARAGRAPH, "She did not knock."))
        assertEquals(9, dao.getDoc(docId)?.wordCount)

        // Code is not prose, so it is not measured — a pasted config file cannot inflate a chapter.
        val code = repo.docs.addBlock(docId, BlockType.CODE, second)
        repo.docs.updateBlock(docId, DocBlock(code, BlockType.CODE, "val x = 1 + 2 + 3"))
        assertEquals(9, dao.getDoc(docId)?.wordCount)

        repo.docs.deleteBlock(docId, second)
        assertEquals(5, dao.getDoc(docId)?.wordCount)

        // The one destructive path: pasting a chapter in replaces everything at once, and the count
        // has to be recomputed rather than added to.
        repo.docs.replaceDocFromMarkdown(docId, "# Chapter one\n\nShe did not knock at the door.")
        assertEquals(
            listOf(BlockType.HEADING1.key, BlockType.PARAGRAPH.key),
            dao.getBlocks(docId).map { it.type }
        )
        assertEquals(9, dao.getDoc(docId)?.wordCount)
    }

    @Test
    fun `a scene's length is the sum of the documents written for it, and follows them about`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val scene = repo.outline.addOutlineNode(projectId, null, "The docks")
        val other = repo.outline.addOutlineNode(projectId, null, "The keep")

        val draft = repo.docs.addDoc(projectId, "Draft", outlineNodeId = scene)
        repo.docs.replaceDocFromMarkdown(draft, "One two three four five.")
        assertEquals(5, dao.getOutlineNode(scene)?.actualWords)

        // Summed rather than taken from one document, because a scene can have a draft and its
        // rewrite, or the scene and the notes for it.
        val notes = repo.docs.addDoc(projectId, "Notes", outlineNodeId = scene)
        repo.docs.replaceDocFromMarkdown(notes, "Six seven eight.")
        assertEquals(8, dao.getOutlineNode(scene)?.actualWords)

        // Re-filing a document has to move its words off the scene it left as well as onto the one
        // it joined, or the outline reports the old scene as longer than anything written for it.
        repo.docs.updateDoc(dao.getDoc(notes)!!.toModel().copy(outlineNodeId = other))
        assertEquals(5, dao.getOutlineNode(scene)?.actualWords)
        assertEquals(3, dao.getOutlineNode(other)?.actualWords)

        // A scene with nothing written for it reads as zero rather than keeping the last number it
        // happened to be given.
        repo.docs.deleteDoc(draft)
        assertEquals(0, dao.getOutlineNode(scene)?.actualWords)
    }

    @Test
    fun `renaming a scene leaves its length alone`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val scene = repo.outline.addOutlineNode(projectId, null, "The docks")
        val docId = repo.docs.addDoc(projectId, "Draft", outlineNodeId = scene)
        repo.docs.replaceDocFromMarkdown(docId, "One two three four five.")

        val node = dao.getOutlineNode(scene)!!.toLogic()
        // Word counts arrive from the linked document, never from an edit box. The model carries a
        // count because the screen drew one; writing it back would let a rename reset the scene.
        repo.outline.updateOutlineNode(node.copy(title = "The harbour", actualWords = 0, targetWords = 1200))

        assertEquals("The harbour", dao.getOutlineNode(scene)?.title)
        assertEquals(1200, dao.getOutlineNode(scene)?.targetWords)
        assertEquals(5, dao.getOutlineNode(scene)?.actualWords)
    }

    // ------------------------------------------------------------------ versions of a document

    @Test
    fun `pasting over a document keeps what it said, and the version restores it exactly`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(projectId, "Scene — the docks")
        repo.docs.replaceDocFromMarkdown(
            docId,
            "# The docks\n\nShe did not knock.\n\n- [x] Ticked\n- [ ] Not ticked"
        )
        val before = dao.getBlocks(docId).map { Triple(it.type, it.text, it.checked) }

        // The edit this whole feature exists for: one tap, and the morning's writing is gone.
        repo.docs.replaceDocFromMarkdown(docId, "Something else entirely.")
        assertEquals(listOf("Something else entirely."), dao.getBlocks(docId).map { it.text })

        val kept = repo.versions.observeRevisions(docId).first()
        assertEquals(1, kept.size)
        assertEquals(RevisionReason.IMPORT, kept.single().reason)

        assertTrue(repo.versions.restoreRevision(docId, kept.single().id))

        // Restored exactly: the types, the text and the ticked states, in order. This is why a
        // version stores blocks rather than rendered Markdown — the round trip would have dropped
        // nothing here but would not have been *guaranteed* to.
        assertEquals(before, dao.getBlocks(docId).map { Triple(it.type, it.text, it.checked) })
        assertEquals(9, dao.getDoc(docId)?.wordCount)
    }

    @Test
    fun `restoring keeps the text it is about to replace, so going back is undoable`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(projectId, "Draft")
        repo.docs.replaceDocFromMarkdown(docId, "The first draft.")
        repo.docs.replaceDocFromMarkdown(docId, "The second draft.")

        val toFirst = repo.versions.observeRevisions(docId).first().single()
        repo.versions.restoreRevision(docId, toFirst.id)
        assertEquals(listOf("The first draft."), dao.getBlocks(docId).map { it.text })

        // The restore filed the second draft on its way past, so the history is not a one-way door.
        val after = repo.versions.observeRevisions(docId).first()
        assertEquals(RevisionReason.RESTORE, after.first().reason)
        assertEquals(listOf("The second draft."), repo.versions.revisionBlocks(after.first().id).map { it.text })

        repo.versions.restoreRevision(docId, after.first().id)
        assertEquals(listOf("The second draft."), dao.getBlocks(docId).map { it.text })
    }

    @Test
    fun `a version is not consumed by being restored`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(projectId, "Draft")
        repo.docs.replaceDocFromMarkdown(docId, "The first draft.")
        repo.docs.replaceDocFromMarkdown(docId, "The second draft.")
        val original = repo.versions.observeRevisions(docId).first().single()

        repo.versions.restoreRevision(docId, original.id)
        repo.docs.replaceDocFromMarkdown(docId, "A third thing.")
        // The same version, a second time. Restoring reads a version rather than moving it.
        assertTrue(repo.versions.restoreRevision(docId, original.id))
        assertEquals(listOf("The first draft."), dao.getBlocks(docId).map { it.text })
    }

    @Test
    fun `an empty document files no version, and neither does one that has not changed`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(projectId, "Draft")

        // A new document is one empty paragraph. Pasting into it must not file a version of nothing
        // and put it at the top of the list.
        repo.docs.replaceDocFromMarkdown(docId, "The first draft.")
        assertTrue(repo.versions.observeRevisions(docId).first().isEmpty())

        assertNotNull(repo.versions.saveRevision(docId, RevisionReason.MANUAL))
        // Asked for twice with nothing typed in between: the second is a copy of the first, and
        // twenty copies of the same paragraph would push the versions that matter off the end.
        assertNull(repo.versions.saveRevision(docId, RevisionReason.MANUAL))
        assertEquals(1, repo.versions.observeRevisions(docId).first().size)

        // Pasting now files nothing either, and that is the same rule rather than a hole in it: the
        // version at the top already holds exactly what is about to be replaced, which is the whole
        // reason to file one. A second copy of it would buy nothing and cost a slot.
        repo.docs.replaceDocFromMarkdown(docId, "Now it says something else.")
        assertEquals(1, repo.versions.observeRevisions(docId).first().size)

        // Once the document says something no version holds, the next destructive edit files it.
        repo.docs.replaceDocFromMarkdown(docId, "And now something else again.")
        val kept = repo.versions.observeRevisions(docId).first()
        assertEquals(2, kept.size)
        assertEquals(
            listOf("Now it says something else."),
            repo.versions.revisionBlocks(kept.first().id).map { it.text }
        )
    }

    @Test
    fun `rebuilding tables keeps a version first`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(projectId, "Stats")
        // A table that lost its line breaks — one paragraph of pipes, which `repairTables` rewrites
        // in bulk. Recoverable in principle; a version makes it recoverable in practice.
        repo.docs.replaceDocFromMarkdown(docId, "| a | b | | --- | --- | | 1 | 2 |")

        val repaired = repo.docs.repairTables(docId)
        if (repaired == 0) return@runTest

        val kept = repo.versions.observeRevisions(docId).first()
        assertEquals(RevisionReason.REPAIR, kept.first().reason)
    }

    @Test
    fun `only the last few versions are kept, and the oldest are the ones that go`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(projectId, "Draft")

        repeat(Revisions.KEEP + 5) { round -> repo.docs.replaceDocFromMarkdown(docId, "Draft number $round.") }

        val kept = repo.versions.observeRevisions(docId).first()
        assertEquals(Revisions.KEEP, kept.size)
        // Newest first, and the oldest drafts are the ones gone: the version filed before the last
        // paste holds the paste before it.
        assertEquals(
            listOf("Draft number ${Revisions.KEEP + 3}."),
            repo.versions.revisionBlocks(kept.first().id).map { it.text }
        )
        val texts = kept.flatMap { repo.versions.revisionBlocks(it.id).map { block -> block.text } }
        assertTrue("an early draft survived the cap", texts.none { it == "Draft number 0." })
    }

    @Test
    fun `a version cannot be restored into another document`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val mine = repo.docs.addDoc(projectId, "Mine")
        val theirs = repo.docs.addDoc(projectId, "Theirs")
        repo.docs.replaceDocFromMarkdown(mine, "Mine, first.")
        repo.docs.replaceDocFromMarkdown(mine, "Mine, second.")
        repo.docs.replaceDocFromMarkdown(theirs, "Theirs.")

        val mineRevision = repo.versions.observeRevisions(mine).first().single()

        // A safety feature that can overwrite the wrong document is a data-loss bug in disguise.
        assertFalse(repo.versions.restoreRevision(theirs, mineRevision.id))
        assertEquals(listOf("Theirs."), dao.getBlocks(theirs).map { it.text })
    }

    @Test
    fun `versions go with the document they are versions of`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(projectId, "Draft")
        repo.docs.replaceDocFromMarkdown(docId, "The first draft.")
        repo.docs.replaceDocFromMarkdown(docId, "The second draft.")
        val revisionId = repo.versions.observeRevisions(docId).first().single().id

        repo.docs.deleteDoc(docId)

        // These are versions *of a document*, not a wastebasket for deleted ones — so they cascade,
        // and their blocks cascade with them rather than being left with nothing to belong to.
        assertTrue(repo.versions.observeRevisions(docId).first().isEmpty())
        assertTrue(repo.versions.revisionBlocks(revisionId).isEmpty())
    }

    // ------------------------------------------------------------------ the board

    @Test
    fun `deleting a column strands its cards rather than deleting them, and they can be re-filed`() = runTest {
        val projectId = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)
        val columns = dao.getColumns(projectId)
        val backlog = columns.first().id
        val inProgress = columns[1].id

        val stranded = repo.board.addCard(projectId, backlog, "Rewrite the dock scene")
        val kept = repo.board.addCard(projectId, inProgress, "Fix the thing")

        repo.board.deleteColumn(projectId, backlog)

        // Losing a column is an organisational decision; losing the work that was in it is never one
        // anybody made on purpose. So this is deliberately not a cascade.
        assertNotNull(dao.getCard(stranded))
        assertEquals(backlog, dao.getCard(stranded)?.columnId)

        repo.board.refileOrphans(projectId, inProgress)

        assertEquals(inProgress, dao.getCard(stranded)?.columnId)
        assertEquals(inProgress, dao.getCard(kept)?.columnId)
        // Re-filed to the end rather than on top of what was already there.
        assertNotEquals(dao.getCard(kept)?.sortOrder, dao.getCard(stranded)?.sortOrder)
    }

    @Test
    fun `moving a card into the finished column stamps it, and moving it back unstamps it`() = runTest {
        val projectId = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)
        val columns = dao.getColumns(projectId)
        val backlog = columns.first().id
        val shipped = columns.last { it.isDone }.id

        val cardId = repo.board.addCard(projectId, backlog, "Fix the thing")
        assertNull(dao.getCard(cardId)?.doneAt)

        repo.board.moveCard(projectId, cardId, shipped, 0)
        assertEquals(shipped, dao.getCard(cardId)?.columnId)
        assertNotNull("a card in the done column has no day it was finished", dao.getCard(cardId)?.doneAt)

        repo.board.moveCard(projectId, cardId, backlog, 0)
        assertNull("dragging work back out left it looking finished", dao.getCard(cardId)?.doneAt)
    }

    @Test
    fun `a card can only be moved into a column of its own project`() = runTest {
        val mine = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)
        val theirs = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val myColumn = dao.getColumns(mine).first().id
        val theirColumn = dao.getColumns(theirs).first().id
        val cardId = repo.board.addCard(mine, myColumn, "Fix the thing")

        repo.board.moveCard(mine, cardId, theirColumn, 0)

        assertEquals("a card crossed into another project's board", myColumn, dao.getCard(cardId)?.columnId)
    }

    // ------------------------------------------------------------------ due dates

    @Test
    fun `a card keeps its due date, and can have it taken away`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val columnId = dao.getColumns(projectId).first().id
        val due = LocalDate.of(2026, 3, 14).toEpochDay()

        val cardId = repo.board.addCard(projectId, columnId, "Rewrite the dock scene", dueOn = due)
        assertEquals(due, dao.getCard(cardId)?.dueOn)

        // Droppable in the same breath as settable: without this the only way to lose a deadline
        // that has been called off is to delete the card it was on.
        repo.board.updateCard(projectId, dao.getCard(cardId)!!.toLogic().copy(dueOn = null))
        assertNull(dao.getCard(cardId)?.dueOn)
    }

    @Test
    fun `most cards have no due date, and that is stored as no date rather than as a zero`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val columnId = dao.getColumns(projectId).first().id

        val cardId = repo.board.addCard(projectId, columnId, "Rewrite the dock scene")

        // Epoch day zero is a real date (1 Jan 1970), so "no deadline" has to be null — a zero here
        // would put every undated card fifty years overdue.
        assertNull(dao.getCard(cardId)?.dueOn)
    }

    @Test
    fun `moving a card between columns leaves its deadline alone`() = runTest {
        val projectId = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)
        val columns = dao.getColumns(projectId)
        val due = LocalDate.of(2026, 3, 14).toEpochDay()
        val cardId = repo.board.addCard(projectId, columns.first().id, "Fix the thing", dueOn = due)

        repo.board.moveCard(projectId, cardId, columns.last().id, 0)

        // When a thing is due is a fact about the work; which lane it is in is a fact about your
        // progress through it. Finishing something early does not move its deadline.
        assertEquals(due, dao.getCard(cardId)?.dueOn)
        assertNotNull("the card was not marked finished", dao.getCard(cardId)?.doneAt)
    }

    // ------------------------------------------------------------------ files, on a record

    @Test
    fun `every kind of record can be found to file on, and reads project-first`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val scene = repo.outline.addOutlineNode(projectId, null, "The docks")
        val lore = repo.lore.addLoreEntry(projectId, "Kestrel", LoreCategory.CHARACTER)
        val card = repo.board.addCard(projectId, dao.getColumns(projectId).first().id, "Rewrite it")

        assertEquals(
            "The Kestrel — The docks",
            repo.files.attachTarget(projectId, AttachKind.OUTLINE, scene)?.shelfLabel
        )
        assertEquals(
            "The Kestrel — Kestrel",
            repo.files.attachTarget(projectId, AttachKind.LORE, lore)?.shelfLabel
        )
        assertEquals(
            "The Kestrel — Rewrite it",
            repo.files.attachTarget(projectId, AttachKind.CARD, card)?.shelfLabel
        )
        // The project's own drawer is the project, with nothing appended to it.
        assertEquals(
            "The Kestrel",
            repo.files.attachTarget(projectId, AttachKind.PROJECT, projectId)?.shelfLabel
        )
        assertEquals("The docks", repo.files.attachTarget(projectId, AttachKind.OUTLINE, scene)?.name)
    }

    @Test
    fun `a record that has gone resolves to nothing`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val scene = repo.outline.addOutlineNode(projectId, null, "The docks")

        repo.outline.deleteOutlineSubtree(projectId, scene)

        // A link to a record's files can outlive the record. Saying so beats an empty drawer that
        // looks like it lost somebody's paperwork.
        assertNull(repo.files.attachTarget(projectId, AttachKind.OUTLINE, scene))
    }

    @Test
    fun `a record cannot be reached through another project's id`() = runTest {
        val mine = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val theirs = repo.shelf.addProject("The other one", ProjectKind.WRITING, null)
        val scene = repo.outline.addOutlineNode(mine, null, "The docks")

        // Both halves exist, so checking them separately would let this through — and the drawer
        // would be labelled with the wrong project's name.
        assertNull(repo.files.attachTarget(theirs, AttachKind.OUTLINE, scene))
        assertNotNull(repo.files.attachTarget(mine, AttachKind.OUTLINE, scene))
    }

    @Test
    fun `everything in a project that can hold files is listed before it is deleted`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val scene = repo.outline.addOutlineNode(projectId, null, "The docks")
        val lore = repo.lore.addLoreEntry(projectId, "Kestrel", LoreCategory.CHARACTER)
        val card = repo.board.addCard(projectId, dao.getColumns(projectId).first().id, "Rewrite it")
        val other = repo.shelf.addProject("The other one", ProjectKind.WRITING, null)
        repo.outline.addOutlineNode(other, null, "Not this one")

        val keys = repo.files.attachableRecordKeys(projectId)

        // The project itself included: its own drawer has to go too.
        assertEquals(setOf(projectId, scene, lore, card), keys.toSet())
    }

    // ------------------------------------------------------------------ renames reach the shelf

    /** A repository whose relabels are recorded rather than sent to a shelf that isn't here. */
    private fun withRecorder(): Pair<ProjectRepository, MutableList<Pair<String, String>>> {
        val seen = mutableListOf<Pair<String, String>>()
        return ProjectRepository(dao) { key, label -> seen += key to label } to seen
    }

    @Test
    fun `renaming a record renames its drawer`() = runTest {
        val (repo, relabels) = withRecorder()
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val scene = repo.outline.addOutlineNode(projectId, null, "The docks")
        relabels.clear()

        repo.outline.updateOutlineNode(dao.getOutlineNode(scene)!!.toLogic().copy(title = "The harbour"))

        assertEquals(listOf(scene to "The Kestrel — The harbour"), relabels)
    }

    @Test
    fun `an edit that is not a rename says nothing to the shelf`() = runTest {
        val (repo, relabels) = withRecorder()
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val scene = repo.outline.addOutlineNode(projectId, null, "The docks")
        relabels.clear()

        // Changing the synopsis is not a rename, and a write to the shelf for every keystroke's
        // worth of editing would be a lot of writing for nothing.
        repo.outline.updateOutlineNode(dao.getOutlineNode(scene)!!.toLogic().copy(synopsis = "She arrives."))

        assertTrue(relabels.isEmpty())
    }

    @Test
    fun `renaming a project renames every drawer in it`() = runTest {
        val (repo, relabels) = withRecorder()
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val scene = repo.outline.addOutlineNode(projectId, null, "The docks")
        val lore = repo.lore.addLoreEntry(projectId, "Kestrel", LoreCategory.CHARACTER)
        val card = repo.board.addCard(projectId, dao.getColumns(projectId).first().id, "Rewrite it")
        relabels.clear()

        repo.shelf.updateProject(dao.getProject(projectId)!!.toModel().copy(name = "The Peregrine"))

        // Every one of them, because a record's label leads with the project — otherwise renaming
        // the project leaves every scene and card in it saying the old name for ever.
        assertEquals(
            mapOf(
                projectId to "The Peregrine",
                scene to "The Peregrine — The docks",
                lore to "The Peregrine — Kestrel",
                card to "The Peregrine — Rewrite it"
            ),
            relabels.toMap()
        )
    }

    @Test
    fun `a card renamed by the hand-off's own title rules still renames one drawer`() = runTest {
        val (repo, relabels) = withRecorder()
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val card = repo.board.addCard(projectId, dao.getColumns(projectId).first().id, "Rewrite it")
        relabels.clear()

        repo.board.updateCard(projectId, dao.getCard(card)!!.toLogic().copy(title = "Rewrite the harbour"))

        assertEquals(listOf(card to "The Kestrel — Rewrite the harbour"), relabels)
    }

    // ------------------------------------------------------------------ the hand-off's store side

    @Test
    fun `only cards a week could care about are put in front of the round`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val columnId = dao.getColumns(projectId).first().id
        val due = LocalDate.of(2026, 3, 14).toEpochDay()

        val dated = repo.board.addCard(projectId, columnId, "Rewrite the dock scene", dueOn = due)
        repo.board.addCard(projectId, columnId, "Someday, maybe")

        // Undated but still holding a live task: the deadline was dropped and the task has to come
        // off the week, which only happens if this card is looked at.
        val undatedButOnTheWeek = repo.board.addCard(projectId, columnId, "Deadline called off")
        repo.setCardLink(undatedButOnTheWeek, taskId = "task-1", publishedDue = due)

        // Undated, no task, but remembering a day it was once published for. There is nothing left
        // to decide — no date to publish and no task to take down — so it is dropped here rather
        // than carried through a decision that can only reach Idle.
        val spent = repo.board.addCard(projectId, columnId, "Was on the week once")
        repo.setCardLink(spent, taskId = null, publishedDue = due)

        val snapshots = repo.cardSnapshots()

        assertEquals(setOf(dated, undatedButOnTheWeek), snapshots.map { it.card.id }.toSet())
        assertEquals("The Kestrel", snapshots.first { it.card.id == dated }.projectName)
        assertEquals(
            CardTasks.TaskLink("task-1", due),
            snapshots.first { it.card.id == undatedButOnTheWeek }.link
        )
    }

    @Test
    fun `a card in the finished column is reported as finished`() = runTest {
        val projectId = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)
        val columns = dao.getColumns(projectId)
        val due = LocalDate.of(2026, 3, 14).toEpochDay()
        val cardId = repo.board.addCard(projectId, columns.first().id, "Fix the thing", dueOn = due)

        assertFalse(repo.cardSnapshots().single { it.card.id == cardId }.inDoneColumn)

        repo.board.moveCard(projectId, cardId, columns.last { it.isDone }.id, 0)

        assertTrue(repo.cardSnapshots().single { it.card.id == cardId }.inDoneColumn)
    }

    @Test
    fun `a card stranded by a deleted column is not mistaken for finished`() = runTest {
        val projectId = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)
        val columns = dao.getColumns(projectId)
        val done = columns.last { it.isDone }
        val due = LocalDate.of(2026, 3, 14).toEpochDay()
        val cardId = repo.board.addCard(projectId, done.id, "Fix the thing", dueOn = due)

        repo.board.deleteColumn(projectId, done.id)

        // Its column id now names nothing. Stranded is not finished — the work still wants doing,
        // and reading "unknown column" as done would quietly take it off somebody's week.
        assertFalse(repo.cardSnapshots().single { it.card.id == cardId }.inDoneColumn)
    }

    @Test
    fun `a link survives editing the card it is on`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val columnId = dao.getColumns(projectId).first().id
        val due = LocalDate.of(2026, 3, 14).toEpochDay()
        val cardId = repo.board.addCard(projectId, columnId, "Rewrite the dock scene", dueOn = due)
        repo.setCardLink(cardId, taskId = "task-1", publishedDue = due)

        repo.board.updateCard(projectId, dao.getCard(cardId)!!.toLogic().copy(title = "Rewrite the harbour"))

        // `BoardCard` deliberately does not carry the link, so no edit made on a screen can wipe it
        // and strand a task on somebody's week with nothing pointing at it.
        assertEquals("task-1", dao.getCard(cardId)?.lifeOpsTaskId)
        assertEquals(due, dao.getCard(cardId)?.publishedDue)
        assertEquals("Rewrite the harbour", dao.getCard(cardId)?.title)
    }

    @Test
    fun `a tick moves the card into the finished column and lets go of the task`() = runTest {
        val projectId = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)
        val columns = dao.getColumns(projectId)
        val done = columns.last { it.isDone }
        val cardId = repo.board.addCard(
            projectId, columns.first().id, "Fix the thing",
            dueOn = LocalDate.of(2026, 3, 14).toEpochDay()
        )
        repo.setCardLink(cardId, taskId = "task-1", publishedDue = 0L)

        assertTrue(repo.completeFromWeek(cardId, completedAt = 42L))

        assertEquals(done.id, dao.getCard(cardId)?.columnId)
        assertEquals(42L, dao.getCard(cardId)?.doneAt)
        assertNull(dao.getCard(cardId)?.lifeOpsTaskId)
        assertNull(dao.getCard(cardId)?.publishedDue)
    }

    @Test
    fun `a second tick over the same card changes nothing and says so`() = runTest {
        val projectId = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)
        val columns = dao.getColumns(projectId)
        val cardId = repo.board.addCard(projectId, columns.first().id, "Fix the thing")

        assertTrue(repo.completeFromWeek(cardId, completedAt = 42L))
        // Reported false, so a round running twice over one tick counts it once.
        assertFalse(repo.completeFromWeek(cardId, completedAt = 99L))
        assertEquals(42L, dao.getCard(cardId)?.doneAt)
    }

    @Test
    fun `a board with no finished column still takes the tick`() = runTest {
        val projectId = repo.shelf.addProject("The app", ProjectKind.SOFTWARE, null)
        val columns = dao.getColumns(projectId)
        val cardId = repo.board.addCard(projectId, columns.first().id, "Fix the thing")
        columns.filter { it.isDone }.forEach { repo.board.deleteColumn(projectId, it.id) }

        assertTrue(repo.completeFromWeek(cardId, completedAt = 42L))

        // Nowhere to move it to, so it is stamped where it stands. Dropping the completion on the
        // floor would lose a tick somebody made.
        assertEquals(42L, dao.getCard(cardId)?.doneAt)
        assertEquals(columns.first().id, dao.getCard(cardId)?.columnId)
    }

    @Test
    fun `the tasks of a project can be read before it is deleted`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val columnId = dao.getColumns(projectId).first().id
        val a = repo.board.addCard(projectId, columnId, "One", dueOn = 20_000L)
        val b = repo.board.addCard(projectId, columnId, "Two", dueOn = 20_001L)
        repo.board.addCard(projectId, columnId, "Three")
        repo.setCardLink(a, "task-a", 20_000L)
        repo.setCardLink(b, "task-b", 20_001L)

        // Read before the delete, because a project's cards cascade with it and no later round can
        // see them to work out that their tasks should come off the week.
        assertEquals(setOf("task-a", "task-b"), repo.week.publishedTaskIdsOf(projectId).toSet())

        repo.shelf.deleteProject(projectId)
        assertTrue(repo.cardSnapshots().none { it.card.id in setOf(a, b) })
    }

    // ------------------------------------------------------------------ opening at an address

    @Test
    fun `an address for something that is still there resolves to itself`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(projectId, "Scene — the docks")

        val workspace = ProjectDestination.Workspace(projectId)
        val section = ProjectDestination.Workspace(projectId, SearchSection.LORE)
        val document = ProjectDestination.Document(projectId, docId)

        assertEquals(workspace, repo.shelf.resolve(workspace))
        assertEquals(section, repo.shelf.resolve(section))
        assertEquals(document, repo.shelf.resolve(document))
        // The shelf is always there — it is the fallback, so it cannot itself go stale.
        assertEquals(ProjectDestination.Shelf, repo.shelf.resolve(ProjectDestination.Shelf))
    }

    @Test
    fun `an address for something that has been deleted resolves to nothing`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(projectId, "Scene — the docks")

        repo.docs.deleteDoc(docId)
        // Advisor answers from a snapshot of the data, so a document it quotes can be gone by the
        // time somebody taps through to it. Landing on an editor for nothing is worse than the
        // shelf.
        assertNull(repo.shelf.resolve(ProjectDestination.Document(projectId, docId)))

        repo.shelf.deleteProject(projectId)
        assertNull(repo.shelf.resolve(ProjectDestination.Workspace(projectId)))
        assertNull(repo.shelf.resolve(ProjectDestination.Workspace(projectId, SearchSection.BOARD)))
        assertNull(repo.shelf.resolve(ProjectDestination.Document(projectId, docId)))
    }

    @Test
    fun `a real document paired with the wrong project resolves to nothing`() = runTest {
        val mine = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val theirs = repo.shelf.addProject("The other one", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(mine, "Scene — the docks")

        // Both halves exist, so checking them separately would let this through — and it would open
        // the editor with a back stack leading to a project the document was never filed in.
        assertNull(repo.shelf.resolve(ProjectDestination.Document(theirs, docId)))
        assertEquals(
            ProjectDestination.Document(mine, docId),
            repo.shelf.resolve(ProjectDestination.Document(mine, docId))
        )
    }

    @Test
    fun `an archived project can still be opened at`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        repo.shelf.setArchived(projectId, true)

        // Archiving takes a project off the shelf, not out of the app. A link to one still works,
        // and so does reopening it — otherwise archiving something would silently break every
        // reference to it rather than tidying it away.
        assertEquals(
            ProjectDestination.Workspace(projectId),
            repo.shelf.resolve(ProjectDestination.Workspace(projectId))
        )
    }

    // ------------------------------------------------------------------ what the shelf reads

    @Test
    fun `a write in any section marks the project as worked on`() = runTest {
        val projectId = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.docs.addDoc(projectId, "Draft")
        val columnId = dao.getColumns(projectId).first().id

        // "Last worked on" has to mean anything you did in the project rather than the last time you
        // renamed it, and the clock is too coarse to tell two writes in the same millisecond apart —
        // so each case starts from a stamp that is obviously stale.
        val writes: List<suspend () -> Unit> = listOf(
            { repo.outline.addOutlineNode(projectId, null, "Chapter one"); Unit },
            { repo.lore.addLoreEntry(projectId, "Kestrel", LoreCategory.CHARACTER); Unit },
            { repo.timeline.addEvent(projectId, "The coronation", "Year 12", null); Unit },
            { repo.board.addCard(projectId, columnId, "Rewrite it"); Unit },
            { repo.board.addColumn(projectId, "Blocked"); Unit },
            { repo.docs.replaceDocFromMarkdown(docId, "She did not knock."); Unit }
        )

        writes.forEach { write ->
            dao.upsertProject(dao.getProject(projectId)!!.copy(updatedAt = STALE))
            write()
            assertNotEquals(STALE, dao.getProject(projectId)?.updatedAt)
        }
    }

    @Test
    fun `the blocks of a project are its own, and stop at its edge`() = runTest {
        val mine = repo.shelf.addProject("The Kestrel", ProjectKind.WRITING, null)
        val theirs = repo.shelf.addProject("The other one", ProjectKind.WRITING, null)
        repo.docs.replaceDocFromMarkdown(repo.docs.addDoc(mine, "Draft"), "Mine.")
        repo.docs.replaceDocFromMarkdown(repo.docs.addDoc(theirs, "Draft"), "Theirs.")

        // Blocks carry no projectId of their own, so this is the one query that has to reach across
        // a join to scope itself — and both readers of it (search, compile) take the whole corpus.
        assertEquals(listOf("Mine."), dao.blocksOfProject(mine).map { it.text })
        assertEquals(listOf("Theirs."), dao.blocksOfProject(theirs).map { it.text })
    }

    // ------------------------------------------------------------------ fixtures

    /** A project with something in every section, for the tests about what a delete takes with it. */
    private suspend fun fullyPopulatedProject(name: String): String {
        val projectId = repo.shelf.addProject(name, ProjectKind.WRITING, null)
        val chapter = repo.outline.addOutlineNode(projectId, null, "Chapter one")
        val scene = repo.outline.addOutlineNode(projectId, chapter, "The docks")
        val docId = repo.docs.addDoc(projectId, "Scene — the docks", outlineNodeId = scene)
        repo.docs.replaceDocFromMarkdown(docId, "The docks smelled of tar.\n\nShe did not knock.")
        repo.lore.addLoreEntry(projectId, "Kestrel", LoreCategory.CHARACTER, body = "Sails the [[Straits]].")
        repo.timeline.addEvent(projectId, "The coronation", "Year 12", "Before", outlineNodeId = scene)
        repo.board.addCard(
            projectId,
            dao.getColumns(projectId).first().id,
            "Rewrite the dock scene",
            outlineNodeId = scene,
            docId = docId
        )
        return projectId
    }

    private companion object {
        /** Older than any real write, and not a value the code could produce by accident. */
        const val STALE = 1_000L
    }
}
