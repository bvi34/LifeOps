package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryFacetsTest {

    @Test
    fun reading_now_infers_book_and_reading_state() {
        val f = QueryFacets.infer("what am I reading?")
        assertEquals(setOf(ObjectType.BOOK), f.objectTypes)
        assertEquals(KnowledgeFacets.READING, f.state)
        assertTrue(f.hasOpinion)
    }

    @Test
    fun to_read_and_finished_infer_their_states() {
        assertEquals(KnowledgeFacets.TO_READ, QueryFacets.infer("what's on my to-read list?").state)
        assertEquals(KnowledgeFacets.DONE, QueryFacets.infer("what books have I read?").state)
    }

    @Test
    fun explicit_state_name_phrasing_infers_the_state() {
        // The screenshot case: the literal state name, not a conversational phrasing.
        assertEquals(KnowledgeFacets.READING, QueryFacets.infer("What Books are in a READING status?").state)
        assertEquals(KnowledgeFacets.TO_READ, QueryFacets.infer("which books are TO_READ?").state)
        assertEquals(KnowledgeFacets.DONE, QueryFacets.infer("show books in a DONE status").state)
    }

    @Test
    fun task_words_infer_task_type() {
        val f = QueryFacets.infer("what tasks are still pending?")
        assertTrue(ObjectType.TASK in f.objectTypes)
        assertEquals(KnowledgeFacets.TODO, f.state)
    }

    @Test
    fun a_task_progress_cue_never_becomes_a_book_state() {
        // "in progress" is a task cue; with no book word present it must not stamp a reading state.
        val f = QueryFacets.infer("what's in progress this week?")
        assertFalse(ObjectType.BOOK in f.objectTypes)
        assertNull(f.state)
    }

    @Test
    fun a_broad_question_has_no_opinion() {
        val f = QueryFacets.infer("what should I focus on this week?")
        assertTrue(f.objectTypes.isEmpty())
        assertNull(f.state)
        assertFalse(f.hasOpinion)
    }

    @Test
    fun recipe_words_infer_recipe_type() {
        val f = QueryFacets.infer("what recipes use beef?")
        assertTrue(ObjectType.RECIPE in f.objectTypes)
        // A recipe carries no lifecycle, so nothing should be stamped on the question either.
        assertNull(f.state)
    }

    @Test
    fun someday_words_infer_the_shelved_idea_type() {
        assertTrue(ObjectType.IDEA in QueryFacets.infer("what ideas did I shelve?").objectTypes)
        assertTrue(ObjectType.IDEA in QueryFacets.infer("anything in my someday list?").objectTypes)
    }

    @Test
    fun a_future_operation_question_wants_both_ideas_and_operations() {
        // "future operation" is literally both words; collecting both types is the point.
        val f = QueryFacets.infer("what future operations have I written down?")
        assertTrue(ObjectType.IDEA in f.objectTypes)
        assertTrue(ObjectType.OPERATION in f.objectTypes)
    }

    @Test
    fun the_old_word_project_still_names_an_operation() {
        // Operations were called Projects until the suite grew a Project app of its own. Questions
        // keep arriving in the old word for as long as the user still thinks in it.
        assertTrue(ObjectType.OPERATION in QueryFacets.infer("which projects are still open?").objectTypes)
        assertTrue(ObjectType.IDEA in QueryFacets.infer("what future projects have I written down?").objectTypes)
    }

    @Test
    fun the_word_project_wants_both_kinds_of_project() {
        // LifeOps calls it an Operation and the Project app calls its own thing a project. A
        // question asked in that word must accept whichever of them the user actually has.
        val f = QueryFacets.infer("how are my projects going?")
        assertTrue(ObjectType.OPERATION in f.objectTypes)
        assertTrue(ObjectType.PROJECT in f.objectTypes)
    }

    @Test
    fun project_words_infer_the_shelf_types() {
        assertTrue(ObjectType.OUTLINE_PIECE in QueryFacets.infer("which scenes are still to write?").objectTypes)
        assertTrue(ObjectType.LORE_ENTRY in QueryFacets.infer("what lore have I written?").objectTypes)
        assertTrue(ObjectType.TIMELINE_EVENT in QueryFacets.infer("what's on the timeline?").objectTypes)
        assertTrue(ObjectType.BOARD_CARD in QueryFacets.infer("what cards are on the board?").objectTypes)
    }

    @Test
    fun the_drafting_ladder_is_inferred_apart_from_the_task_one() {
        assertEquals(KnowledgeFacets.DRAFTED, QueryFacets.infer("which chapters are drafted?").state)
        assertEquals(KnowledgeFacets.DOING, QueryFacets.infer("what scene am I drafting?").state)
        assertEquals(KnowledgeFacets.CUT, QueryFacets.infer("which scenes did I cut?").state)
    }

    @Test
    fun maintenance_words_infer_the_register_types() {
        assertTrue(ObjectType.ASSET in QueryFacets.infer("what's the mileage on the car?").objectTypes)
        assertTrue(ObjectType.UPKEEP_PLAN in QueryFacets.infer("what upkeep is coming up?").objectTypes)
        assertTrue(ObjectType.COVERAGE in QueryFacets.infer("when does the insurance renew?").objectTypes)
        assertTrue(ObjectType.LOAN in QueryFacets.infer("how much is left on the mortgage?").objectTypes)
        assertTrue(ObjectType.RECALL in QueryFacets.infer("any open recalls?").objectTypes)
    }

    @Test
    fun the_due_states_are_inferred_for_what_the_house_owes() {
        assertEquals(KnowledgeFacets.OVERDUE, QueryFacets.infer("what upkeep is overdue?").state)
        assertEquals(KnowledgeFacets.DUE_SOON, QueryFacets.infer("what upkeep is coming up?").state)
    }

    @Test
    fun document_words_infer_the_document_type() {
        assertTrue(ObjectType.DOCUMENT in QueryFacets.infer("do we have the furnace manual?").objectTypes)
        assertTrue(ObjectType.DOCUMENT in QueryFacets.infer("which documents did we file this year?").objectTypes)
    }
}
