package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerTextTest {

    @Test
    fun a_finished_answer_has_every_directive_removed() {
        val raw = """
            You're reading Dune [1].
            @relevance(2): object_mismatch
            @remember(user): prefers science fiction
            @memorize: started Dune in August #reading
        """.trimIndent()
        assertEquals("You're reading Dune [1].", AnswerText.finished(raw))
    }

    @Test
    fun prose_after_a_directive_survives() {
        val raw = "First line.\n@memorize: a fact\nSecond line."
        assertEquals("First line.\nSecond line.", AnswerText.finished(raw))
    }

    /**
     * The case streaming introduces: a directive that has begun but has no newline yet, so the
     * whole-line strip patterns cannot see it.
     */
    @Test
    fun a_directive_still_being_written_is_hidden() {
        assertEquals("You're reading Dune [1].", AnswerText.inProgress("You're reading Dune [1].\n@memo"))
        assertEquals("You're reading Dune [1].", AnswerText.inProgress("You're reading Dune [1].\n@remember(user): pref"))
    }

    @Test
    fun an_answer_that_is_only_a_partial_directive_shows_nothing() {
        assertEquals("", AnswerText.inProgress("@memo"))
    }

    @Test
    fun an_at_sign_mid_sentence_is_prose_not_a_directive() {
        assertEquals("Email me at a@b.com", AnswerText.inProgress("Email me at a@b.com"))
    }

    @Test
    fun in_progress_matches_finished_once_the_directive_line_is_complete() {
        val raw = "You're reading Dune [1].\n@memorize: started Dune #reading\n"
        assertEquals(AnswerText.finished(raw), AnswerText.inProgress(raw))
        assertEquals("You're reading Dune [1].", AnswerText.inProgress(raw))
    }

    @Test
    fun ordinary_prose_is_untouched_either_way() {
        val raw = "It looks like today you have two things due [1][2]."
        assertEquals(raw, AnswerText.finished(raw))
        assertEquals(raw, AnswerText.inProgress(raw))
    }
}
