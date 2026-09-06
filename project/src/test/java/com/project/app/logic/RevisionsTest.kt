package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules about which versions of a document are worth keeping.
 *
 * All three exist to stop the history filling with rows nobody would ever restore — an empty
 * document, a copy of the version already at the top, or a version from four hundred edits ago —
 * because a list of twenty useless versions is the same as no history at all when you are looking
 * for the one from before the paste.
 */
class RevisionsTest {

    private fun block(type: BlockType, text: String, checked: Boolean = false) =
        DocBlock("id-${text.hashCode()}-${type.key}", type, text, checked)

    private fun revision(id: String, savedAt: Long) =
        DocRevision(id = id, docId = "d1", reason = RevisionReason.MANUAL, wordCount = 0, savedAt = savedAt)

    // ------------------------------------------------------------------ worth keeping

    @Test
    fun `a document with nothing written in it is not worth a version`() {
        assertFalse(Revisions.worthKeeping(emptyList()))
        // What a brand-new document holds: one empty paragraph, so the cursor has somewhere to go.
        assertFalse(Revisions.worthKeeping(listOf(block(BlockType.PARAGRAPH, ""))))
        assertFalse(
            Revisions.worthKeeping(
                listOf(block(BlockType.PARAGRAPH, "   "), block(BlockType.HEADING1, ""))
            )
        )
    }

    @Test
    fun `one written line is enough, and so is a divider`() {
        assertTrue(Revisions.worthKeeping(listOf(block(BlockType.PARAGRAPH, "She did not knock."))))
        assertTrue(
            Revisions.worthKeeping(
                listOf(block(BlockType.PARAGRAPH, ""), block(BlockType.PARAGRAPH, "Tar."))
            )
        )
        // A divider carries no text and is still something somebody put there on purpose — a
        // document that is a scene break and nothing else has structure worth not losing.
        assertTrue(Revisions.worthKeeping(listOf(block(BlockType.DIVIDER, ""))))
    }

    // ------------------------------------------------------------------ differ

    @Test
    fun `the same text is the same version, whatever its blocks are called`() {
        val a = listOf(block(BlockType.HEADING1, "Chapter one"), block(BlockType.PARAGRAPH, "Tar."))
        // Ids are minted per snapshot, so comparing them would report every version as different
        // from every other and the de-duplication would never fire.
        val b = a.map { it.copy(id = "quite-different-${it.text}") }

        assertFalse(Revisions.differ(a, b))
    }

    @Test
    fun `retyping, rewording, reordering and ticking are all changes`() {
        val base = listOf(block(BlockType.PARAGRAPH, "Tar."), block(BlockType.TODO, "Knock", false))

        assertTrue(
            "a block's type changed",
            Revisions.differ(listOf(base[0].copy(type = BlockType.QUOTE), base[1]), base)
        )
        assertTrue(
            "a block's text changed",
            Revisions.differ(listOf(base[0].copy(text = "Rope."), base[1]), base)
        )
        assertTrue(
            "a to-do was ticked",
            Revisions.differ(listOf(base[0], base[1].copy(checked = true)), base)
        )
        assertTrue("the blocks were reordered", Revisions.differ(base.reversed(), base))
        assertTrue("a block was added", Revisions.differ(base + block(BlockType.PARAGRAPH, "More."), base))
        assertTrue("a block was deleted", Revisions.differ(base.take(1), base))
        assertFalse("nothing changed", Revisions.differ(base, base))
    }

    // ------------------------------------------------------------------ the cap

    @Test
    fun `nothing is pruned until the cap is passed`() {
        val revisions = (1..Revisions.KEEP).map { revision("r$it", savedAt = it.toLong()) }
        assertTrue(Revisions.prunable(revisions).isEmpty())
    }

    @Test
    fun `the oldest go first, and exactly enough of them go`() {
        val revisions = (1..Revisions.KEEP + 3).map { revision("r$it", savedAt = it.toLong()) }

        // Oldest is r1; three over the cap means r1, r2 and r3 — reported oldest-last is fine, but
        // it must be those three and only those three.
        assertEquals(setOf("r1", "r2", "r3"), Revisions.prunable(revisions).toSet())
        assertEquals(3, Revisions.prunable(revisions).size)
    }

    @Test
    fun `two versions filed in the same millisecond still prune deterministically`() {
        // The clock has millisecond resolution and a restore files a version immediately before
        // rewriting the document, so a tie is ordinary rather than exotic. Ordering has to be total
        // or the same call could drop a different version each time it ran.
        val tied = listOf(revision("a", 5L), revision("b", 5L), revision("c", 5L))

        val first = Revisions.prunable(tied, keep = 2)
        val second = Revisions.prunable(tied.reversed(), keep = 2)

        assertEquals(listOf("a"), first)
        assertEquals("the answer depended on the order the rows arrived in", first, second)
    }

    @Test
    fun `a cap of zero keeps nothing, and a negative one is read as zero`() {
        val revisions = listOf(revision("a", 1L), revision("b", 2L))

        assertEquals(setOf("a", "b"), Revisions.prunable(revisions, keep = 0).toSet())
        assertEquals(setOf("a", "b"), Revisions.prunable(revisions, keep = -5).toSet())
    }
}
