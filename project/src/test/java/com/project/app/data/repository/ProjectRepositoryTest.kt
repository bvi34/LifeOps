package com.project.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.project.app.data.db.ProjectDatabase
import com.project.app.logic.BlockType
import com.project.app.logic.DocBlock
import com.project.app.logic.LoreCategory
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
 *   really put it back.
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
        val writing = repo.addProject("The Kestrel", ProjectKind.WRITING, "  ")
        val software = repo.addProject("The app", ProjectKind.SOFTWARE, " A thing. ")

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

        repo.deleteProject(doomed)

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
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val chapter = repo.addOutlineNode(projectId, null, "Chapter one")
        val scene = repo.addOutlineNode(projectId, chapter, "The docks")

        val docId = repo.addDoc(projectId, "Scene — the docks", outlineNodeId = scene)
        val eventId = repo.addEvent(projectId, "The coronation", "Year 12", null, outlineNodeId = scene)
        val columnId = dao.getColumns(projectId).first().id
        val cardId = repo.addCard(projectId, columnId, "Rewrite it", outlineNodeId = scene, docId = docId)

        repo.deleteOutlineSubtree(projectId, chapter)

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
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.addDoc(projectId, "Scene — the docks")
        val columnId = dao.getColumns(projectId).first().id
        val cardId = repo.addCard(projectId, columnId, "Rewrite it", docId = docId)

        repo.deleteDoc(docId)

        assertNull(dao.getDoc(docId))
        assertTrue("the blocks outlived their document", dao.getBlocks(docId).isEmpty())
        assertNotNull("the card went with the document", dao.getCard(cardId))
        assertNull(dao.getCard(cardId)?.docId)
    }

    // ------------------------------------------------------------------ the stored word counts

    @Test
    fun `a document's count follows its blocks through every way of editing them`() = runTest {
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.addDoc(projectId, "Scene — the docks")
        val seeded = dao.getBlocks(docId).single()

        repo.updateBlock(docId, DocBlock(seeded.id, BlockType.PARAGRAPH, "The docks smelled of tar."))
        assertEquals(5, dao.getDoc(docId)?.wordCount)

        val second = repo.addBlock(docId, BlockType.PARAGRAPH, seeded.id)
        repo.updateBlock(docId, DocBlock(second, BlockType.PARAGRAPH, "She did not knock."))
        assertEquals(9, dao.getDoc(docId)?.wordCount)

        // Code is not prose, so it is not measured — a pasted config file cannot inflate a chapter.
        val code = repo.addBlock(docId, BlockType.CODE, second)
        repo.updateBlock(docId, DocBlock(code, BlockType.CODE, "val x = 1 + 2 + 3"))
        assertEquals(9, dao.getDoc(docId)?.wordCount)

        repo.deleteBlock(docId, second)
        assertEquals(5, dao.getDoc(docId)?.wordCount)

        // The one destructive path: pasting a chapter in replaces everything at once, and the count
        // has to be recomputed rather than added to.
        repo.replaceDocFromMarkdown(docId, "# Chapter one\n\nShe did not knock at the door.")
        assertEquals(
            listOf(BlockType.HEADING1.key, BlockType.PARAGRAPH.key),
            dao.getBlocks(docId).map { it.type }
        )
        assertEquals(9, dao.getDoc(docId)?.wordCount)
    }

    @Test
    fun `a scene's length is the sum of the documents written for it, and follows them about`() = runTest {
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val scene = repo.addOutlineNode(projectId, null, "The docks")
        val other = repo.addOutlineNode(projectId, null, "The keep")

        val draft = repo.addDoc(projectId, "Draft", outlineNodeId = scene)
        repo.replaceDocFromMarkdown(draft, "One two three four five.")
        assertEquals(5, dao.getOutlineNode(scene)?.actualWords)

        // Summed rather than taken from one document, because a scene can have a draft and its
        // rewrite, or the scene and the notes for it.
        val notes = repo.addDoc(projectId, "Notes", outlineNodeId = scene)
        repo.replaceDocFromMarkdown(notes, "Six seven eight.")
        assertEquals(8, dao.getOutlineNode(scene)?.actualWords)

        // Re-filing a document has to move its words off the scene it left as well as onto the one
        // it joined, or the outline reports the old scene as longer than anything written for it.
        repo.updateDoc(dao.getDoc(notes)!!.toModel().copy(outlineNodeId = other))
        assertEquals(5, dao.getOutlineNode(scene)?.actualWords)
        assertEquals(3, dao.getOutlineNode(other)?.actualWords)

        // A scene with nothing written for it reads as zero rather than keeping the last number it
        // happened to be given.
        repo.deleteDoc(draft)
        assertEquals(0, dao.getOutlineNode(scene)?.actualWords)
    }

    @Test
    fun `renaming a scene leaves its length alone`() = runTest {
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val scene = repo.addOutlineNode(projectId, null, "The docks")
        val docId = repo.addDoc(projectId, "Draft", outlineNodeId = scene)
        repo.replaceDocFromMarkdown(docId, "One two three four five.")

        val node = dao.getOutlineNode(scene)!!.toLogic()
        // Word counts arrive from the linked document, never from an edit box. The model carries a
        // count because the screen drew one; writing it back would let a rename reset the scene.
        repo.updateOutlineNode(node.copy(title = "The harbour", actualWords = 0, targetWords = 1200))

        assertEquals("The harbour", dao.getOutlineNode(scene)?.title)
        assertEquals(1200, dao.getOutlineNode(scene)?.targetWords)
        assertEquals(5, dao.getOutlineNode(scene)?.actualWords)
    }

    // ------------------------------------------------------------------ versions of a document

    @Test
    fun `pasting over a document keeps what it said, and the version restores it exactly`() = runTest {
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.addDoc(projectId, "Scene — the docks")
        repo.replaceDocFromMarkdown(
            docId,
            "# The docks\n\nShe did not knock.\n\n- [x] Ticked\n- [ ] Not ticked"
        )
        val before = dao.getBlocks(docId).map { Triple(it.type, it.text, it.checked) }

        // The edit this whole feature exists for: one tap, and the morning's writing is gone.
        repo.replaceDocFromMarkdown(docId, "Something else entirely.")
        assertEquals(listOf("Something else entirely."), dao.getBlocks(docId).map { it.text })

        val kept = repo.observeRevisions(docId).first()
        assertEquals(1, kept.size)
        assertEquals(RevisionReason.IMPORT, kept.single().reason)

        assertTrue(repo.restoreRevision(docId, kept.single().id))

        // Restored exactly: the types, the text and the ticked states, in order. This is why a
        // version stores blocks rather than rendered Markdown — the round trip would have dropped
        // nothing here but would not have been *guaranteed* to.
        assertEquals(before, dao.getBlocks(docId).map { Triple(it.type, it.text, it.checked) })
        assertEquals(9, dao.getDoc(docId)?.wordCount)
    }

    @Test
    fun `restoring keeps the text it is about to replace, so going back is undoable`() = runTest {
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.addDoc(projectId, "Draft")
        repo.replaceDocFromMarkdown(docId, "The first draft.")
        repo.replaceDocFromMarkdown(docId, "The second draft.")

        val toFirst = repo.observeRevisions(docId).first().single()
        repo.restoreRevision(docId, toFirst.id)
        assertEquals(listOf("The first draft."), dao.getBlocks(docId).map { it.text })

        // The restore filed the second draft on its way past, so the history is not a one-way door.
        val after = repo.observeRevisions(docId).first()
        assertEquals(RevisionReason.RESTORE, after.first().reason)
        assertEquals(listOf("The second draft."), repo.revisionBlocks(after.first().id).map { it.text })

        repo.restoreRevision(docId, after.first().id)
        assertEquals(listOf("The second draft."), dao.getBlocks(docId).map { it.text })
    }

    @Test
    fun `a version is not consumed by being restored`() = runTest {
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.addDoc(projectId, "Draft")
        repo.replaceDocFromMarkdown(docId, "The first draft.")
        repo.replaceDocFromMarkdown(docId, "The second draft.")
        val original = repo.observeRevisions(docId).first().single()

        repo.restoreRevision(docId, original.id)
        repo.replaceDocFromMarkdown(docId, "A third thing.")
        // The same version, a second time. Restoring reads a version rather than moving it.
        assertTrue(repo.restoreRevision(docId, original.id))
        assertEquals(listOf("The first draft."), dao.getBlocks(docId).map { it.text })
    }

    @Test
    fun `an empty document files no version, and neither does one that has not changed`() = runTest {
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.addDoc(projectId, "Draft")

        // A new document is one empty paragraph. Pasting into it must not file a version of nothing
        // and put it at the top of the list.
        repo.replaceDocFromMarkdown(docId, "The first draft.")
        assertTrue(repo.observeRevisions(docId).first().isEmpty())

        assertNotNull(repo.saveRevision(docId, RevisionReason.MANUAL))
        // Asked for twice with nothing typed in between: the second is a copy of the first, and
        // twenty copies of the same paragraph would push the versions that matter off the end.
        assertNull(repo.saveRevision(docId, RevisionReason.MANUAL))
        assertEquals(1, repo.observeRevisions(docId).first().size)

        // Pasting now files nothing either, and that is the same rule rather than a hole in it: the
        // version at the top already holds exactly what is about to be replaced, which is the whole
        // reason to file one. A second copy of it would buy nothing and cost a slot.
        repo.replaceDocFromMarkdown(docId, "Now it says something else.")
        assertEquals(1, repo.observeRevisions(docId).first().size)

        // Once the document says something no version holds, the next destructive edit files it.
        repo.replaceDocFromMarkdown(docId, "And now something else again.")
        val kept = repo.observeRevisions(docId).first()
        assertEquals(2, kept.size)
        assertEquals(
            listOf("Now it says something else."),
            repo.revisionBlocks(kept.first().id).map { it.text }
        )
    }

    @Test
    fun `rebuilding tables keeps a version first`() = runTest {
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.addDoc(projectId, "Stats")
        // A table that lost its line breaks — one paragraph of pipes, which `repairTables` rewrites
        // in bulk. Recoverable in principle; a version makes it recoverable in practice.
        repo.replaceDocFromMarkdown(docId, "| a | b | | --- | --- | | 1 | 2 |")

        val repaired = repo.repairTables(docId)
        if (repaired == 0) return@runTest

        val kept = repo.observeRevisions(docId).first()
        assertEquals(RevisionReason.REPAIR, kept.first().reason)
    }

    @Test
    fun `only the last few versions are kept, and the oldest are the ones that go`() = runTest {
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.addDoc(projectId, "Draft")

        repeat(Revisions.KEEP + 5) { round -> repo.replaceDocFromMarkdown(docId, "Draft number $round.") }

        val kept = repo.observeRevisions(docId).first()
        assertEquals(Revisions.KEEP, kept.size)
        // Newest first, and the oldest drafts are the ones gone: the version filed before the last
        // paste holds the paste before it.
        assertEquals(
            listOf("Draft number ${Revisions.KEEP + 3}."),
            repo.revisionBlocks(kept.first().id).map { it.text }
        )
        val texts = kept.flatMap { repo.revisionBlocks(it.id).map { block -> block.text } }
        assertTrue("an early draft survived the cap", texts.none { it == "Draft number 0." })
    }

    @Test
    fun `a version cannot be restored into another document`() = runTest {
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val mine = repo.addDoc(projectId, "Mine")
        val theirs = repo.addDoc(projectId, "Theirs")
        repo.replaceDocFromMarkdown(mine, "Mine, first.")
        repo.replaceDocFromMarkdown(mine, "Mine, second.")
        repo.replaceDocFromMarkdown(theirs, "Theirs.")

        val mineRevision = repo.observeRevisions(mine).first().single()

        // A safety feature that can overwrite the wrong document is a data-loss bug in disguise.
        assertFalse(repo.restoreRevision(theirs, mineRevision.id))
        assertEquals(listOf("Theirs."), dao.getBlocks(theirs).map { it.text })
    }

    @Test
    fun `versions go with the document they are versions of`() = runTest {
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.addDoc(projectId, "Draft")
        repo.replaceDocFromMarkdown(docId, "The first draft.")
        repo.replaceDocFromMarkdown(docId, "The second draft.")
        val revisionId = repo.observeRevisions(docId).first().single().id

        repo.deleteDoc(docId)

        // These are versions *of a document*, not a wastebasket for deleted ones — so they cascade,
        // and their blocks cascade with them rather than being left with nothing to belong to.
        assertTrue(repo.observeRevisions(docId).first().isEmpty())
        assertTrue(repo.revisionBlocks(revisionId).isEmpty())
    }

    // ------------------------------------------------------------------ the board

    @Test
    fun `deleting a column strands its cards rather than deleting them, and they can be re-filed`() = runTest {
        val projectId = repo.addProject("The app", ProjectKind.SOFTWARE, null)
        val columns = dao.getColumns(projectId)
        val backlog = columns.first().id
        val inProgress = columns[1].id

        val stranded = repo.addCard(projectId, backlog, "Rewrite the dock scene")
        val kept = repo.addCard(projectId, inProgress, "Fix the thing")

        repo.deleteColumn(projectId, backlog)

        // Losing a column is an organisational decision; losing the work that was in it is never one
        // anybody made on purpose. So this is deliberately not a cascade.
        assertNotNull(dao.getCard(stranded))
        assertEquals(backlog, dao.getCard(stranded)?.columnId)

        repo.refileOrphans(projectId, inProgress)

        assertEquals(inProgress, dao.getCard(stranded)?.columnId)
        assertEquals(inProgress, dao.getCard(kept)?.columnId)
        // Re-filed to the end rather than on top of what was already there.
        assertNotEquals(dao.getCard(kept)?.sortOrder, dao.getCard(stranded)?.sortOrder)
    }

    @Test
    fun `moving a card into the finished column stamps it, and moving it back unstamps it`() = runTest {
        val projectId = repo.addProject("The app", ProjectKind.SOFTWARE, null)
        val columns = dao.getColumns(projectId)
        val backlog = columns.first().id
        val shipped = columns.last { it.isDone }.id

        val cardId = repo.addCard(projectId, backlog, "Fix the thing")
        assertNull(dao.getCard(cardId)?.doneAt)

        repo.moveCard(projectId, cardId, shipped, 0)
        assertEquals(shipped, dao.getCard(cardId)?.columnId)
        assertNotNull("a card in the done column has no day it was finished", dao.getCard(cardId)?.doneAt)

        repo.moveCard(projectId, cardId, backlog, 0)
        assertNull("dragging work back out left it looking finished", dao.getCard(cardId)?.doneAt)
    }

    @Test
    fun `a card can only be moved into a column of its own project`() = runTest {
        val mine = repo.addProject("The app", ProjectKind.SOFTWARE, null)
        val theirs = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val myColumn = dao.getColumns(mine).first().id
        val theirColumn = dao.getColumns(theirs).first().id
        val cardId = repo.addCard(mine, myColumn, "Fix the thing")

        repo.moveCard(mine, cardId, theirColumn, 0)

        assertEquals("a card crossed into another project's board", myColumn, dao.getCard(cardId)?.columnId)
    }

    // ------------------------------------------------------------------ what the shelf reads

    @Test
    fun `a write in any section marks the project as worked on`() = runTest {
        val projectId = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val docId = repo.addDoc(projectId, "Draft")
        val columnId = dao.getColumns(projectId).first().id

        // "Last worked on" has to mean anything you did in the project rather than the last time you
        // renamed it, and the clock is too coarse to tell two writes in the same millisecond apart —
        // so each case starts from a stamp that is obviously stale.
        val writes: List<suspend () -> Unit> = listOf(
            { repo.addOutlineNode(projectId, null, "Chapter one"); Unit },
            { repo.addLoreEntry(projectId, "Kestrel", LoreCategory.CHARACTER); Unit },
            { repo.addEvent(projectId, "The coronation", "Year 12", null); Unit },
            { repo.addCard(projectId, columnId, "Rewrite it"); Unit },
            { repo.addColumn(projectId, "Blocked"); Unit },
            { repo.replaceDocFromMarkdown(docId, "She did not knock."); Unit }
        )

        writes.forEach { write ->
            dao.upsertProject(dao.getProject(projectId)!!.copy(updatedAt = STALE))
            write()
            assertNotEquals(STALE, dao.getProject(projectId)?.updatedAt)
        }
    }

    @Test
    fun `the blocks of a project are its own, and stop at its edge`() = runTest {
        val mine = repo.addProject("The Kestrel", ProjectKind.WRITING, null)
        val theirs = repo.addProject("The other one", ProjectKind.WRITING, null)
        repo.replaceDocFromMarkdown(repo.addDoc(mine, "Draft"), "Mine.")
        repo.replaceDocFromMarkdown(repo.addDoc(theirs, "Draft"), "Theirs.")

        // Blocks carry no projectId of their own, so this is the one query that has to reach across
        // a join to scope itself — and both readers of it (search, compile) take the whole corpus.
        assertEquals(listOf("Mine."), dao.blocksOfProject(mine).map { it.text })
        assertEquals(listOf("Theirs."), dao.blocksOfProject(theirs).map { it.text })
    }

    // ------------------------------------------------------------------ fixtures

    /** A project with something in every section, for the tests about what a delete takes with it. */
    private suspend fun fullyPopulatedProject(name: String): String {
        val projectId = repo.addProject(name, ProjectKind.WRITING, null)
        val chapter = repo.addOutlineNode(projectId, null, "Chapter one")
        val scene = repo.addOutlineNode(projectId, chapter, "The docks")
        val docId = repo.addDoc(projectId, "Scene — the docks", outlineNodeId = scene)
        repo.replaceDocFromMarkdown(docId, "The docks smelled of tar.\n\nShe did not knock.")
        repo.addLoreEntry(projectId, "Kestrel", LoreCategory.CHARACTER, body = "Sails the [[Straits]].")
        repo.addEvent(projectId, "The coronation", "Year 12", "Before", outlineNodeId = scene)
        repo.addCard(
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
