package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelevanceEngineTest {

    private fun book(id: String, title: String, state: String) =
        KnowledgeDocument("citation:book:$id", SourceApp.CITATION, "book", title,
            "Book: $title. Source: EPUB. Reading state: $state")

    private fun pantry(id: String, title: String) =
        KnowledgeDocument("logistics:pantry:$id", SourceApp.LOGISTICS, "pantry", title,
            "Pantry item: $title. In stock: 2 Bottle. Category: Pantry")

    private fun task(id: String, title: String, status: String) =
        KnowledgeDocument("lifeops:task:$id", SourceApp.LIFEOPS, "task", title,
            "Task: $title. Status: $status. Priority: medium")

    private fun chunk(doc: KnowledgeDocument, score: Double = 1.0) = RetrievedChunk(doc, score)

    private val reading = book("a", "Rise of the undead Empress", "READING")
    private val toRead = book("b", "Run Your Own Mail Server", "TO_READ")
    private val done = book("c", "Effective Kotlin", "DONE")
    private val miracleWhip = pantry("1", "Miracle Whip Dressing")

    @Test
    fun what_am_i_reading_keeps_only_the_in_progress_book() {
        val result = RelevanceEngine.assess(
            "what am I reading?",
            listOf(chunk(miracleWhip), chunk(toRead), chunk(reading), chunk(done))
        )
        assertEquals(listOf("citation:book:a"), result.grounding.map { it.document.id })
    }

    @Test
    fun the_pantry_item_is_named_an_object_type_mismatch() {
        val result = RelevanceEngine.assess("what am I reading?", listOf(chunk(miracleWhip), chunk(reading)))
        val whip = result.assessments.first { it.document.id == "logistics:pantry:1" }
        assertEquals(RelevanceCategory.OBJECT_TYPE_MISMATCH, whip.category)
    }

    @Test
    fun a_to_read_book_is_a_state_mismatch_for_reading_now() {
        val result = RelevanceEngine.assess("what am I reading?", listOf(chunk(toRead), chunk(reading)))
        val queued = result.assessments.first { it.document.id == "citation:book:b" }
        assertEquals(RelevanceCategory.STATE_MISMATCH, queued.category)
    }

    @Test
    fun a_broad_question_leaves_the_ranked_list_intact() {
        // No facet opinion → everything is uncertain, so grounding == the retrieved list, unchanged.
        val chunks = listOf(chunk(reading), chunk(miracleWhip), chunk(task("t", "Mow lawn", "pending")))
        val result = RelevanceEngine.assess("what should I focus on today?", chunks)
        assertEquals(chunks.map { it.document.id }, result.grounding.map { it.document.id })
        assertTrue(result.assessments.all { it.category == RelevanceCategory.UNCERTAIN })
    }

    @Test
    fun nothing_in_the_asked_state_grounds_nothing() {
        // Asked what I'm reading, but only to-read/done on hand → honest empty grounding, not a dump.
        val result = RelevanceEngine.assess("what am I reading?", listOf(chunk(toRead), chunk(done), chunk(miracleWhip)))
        assertTrue(result.grounding.isEmpty())
        assertTrue(result.rejected.size == 3)
        assertTrue(result.filterNote().isNotBlank())
    }

    @Test
    fun the_llm_can_override_a_verdict() {
        // Model decides the to-read book actually IS relevant (e.g. user just started it) — its feedback wins.
        val base = RelevanceEngine.assess("what am I reading?", listOf(chunk(toRead), chunk(reading)))
        val overridden = RelevanceEngine.reassess(
            base, mapOf("citation:book:b" to RelevanceCategory.RELEVANT)
        )
        assertTrue("citation:book:b" in overridden.grounding.map { it.document.id })
    }

    @Test
    fun task_state_filtering_drops_completed_when_asking_whats_left() {
        val open = task("t1", "Mow lawn", "pending")
        val closed = task("t2", "File taxes", "completed")
        val result = RelevanceEngine.assess("what tasks are still to do?", listOf(chunk(open), chunk(closed)))
        assertEquals(listOf("lifeops:task:t1"), result.grounding.map { it.document.id })
        val doneTask = result.assessments.first { it.document.id == "lifeops:task:t2" }
        assertEquals(RelevanceCategory.STATE_MISMATCH, doneTask.category)
    }

    @Test
    fun off_type_hits_are_dropped_even_without_a_state_cue() {
        // "show me my books" → object type BOOK, no state; the pantry item still goes.
        val result = RelevanceEngine.assess("show me my books", listOf(chunk(miracleWhip), chunk(reading), chunk(toRead)))
        assertFalse("logistics:pantry:1" in result.grounding.map { it.document.id })
        assertTrue("citation:book:a" in result.grounding.map { it.document.id })
        assertTrue("citation:book:b" in result.grounding.map { it.document.id })
    }
}
