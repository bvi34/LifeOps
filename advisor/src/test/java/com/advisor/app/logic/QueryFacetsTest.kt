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
}
