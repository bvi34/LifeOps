package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelevanceDirectivesTest {

    @Test
    fun parses_ref_and_category() {
        val votes = RelevanceDirectives.parse(
            "Here's what fits.\n@relevance(2): object_mismatch\n@relevance(3): state_mismatch"
        )
        assertEquals(2, votes.size)
        assertEquals(RelevanceVote(2, RelevanceCategory.OBJECT_TYPE_MISMATCH), votes[0])
        assertEquals(RelevanceVote(3, RelevanceCategory.STATE_MISMATCH), votes[1])
    }

    @Test
    fun accepts_category_synonyms_and_spacing() {
        assertEquals(RelevanceCategory.OBJECT_TYPE_MISMATCH,
            RelevanceDirectives.parse("@relevance(1): wrong type").single().category)
        assertEquals(RelevanceCategory.IRRELEVANT,
            RelevanceDirectives.parse("@relevance( 4 ):unrelated").single().category)
        assertEquals(RelevanceCategory.RELEVANT,
            RelevanceDirectives.parse("@RELEVANCE(1): KEEP").single().category)
    }

    @Test
    fun ignores_malformed_or_unknown_verdicts() {
        assertTrue(RelevanceDirectives.parse("@relevance(2): banana").isEmpty())
        assertTrue(RelevanceDirectives.parse("@relevance(x): irrelevant").isEmpty())
        assertTrue(RelevanceDirectives.parse("no directives here").isEmpty())
    }

    @Test
    fun strips_directive_lines_but_keeps_prose() {
        val text = "You're reading Dune.\n@relevance(2): object_mismatch\nWant a summary?"
        val stripped = RelevanceDirectives.strip(text)
        assertTrue(stripped, stripped.contains("You're reading Dune."))
        assertTrue(stripped, stripped.contains("Want a summary?"))
        assertFalse(stripped, stripped.contains("@relevance"))
    }

    // --- the repository loop, exercised at the logic level ---

    private fun book(id: String, title: String, state: String) =
        KnowledgeDocument("citation:book:$id", SourceApp.CITATION, "book", title,
            "Book: $title. Reading state: $state")

    @Test
    fun model_votes_reground_the_answer_set() {
        val reading = book("a", "Dune", "READING")
        val alsoReading = book("b", "Neuromancer", "READING")
        val chunks = listOf(RetrievedChunk(reading, 1.0), RetrievedChunk(alsoReading, 0.9))

        // Baseline: both are books being read → both ground the answer.
        val base = RelevanceEngine.assess("what am I reading?", chunks)
        assertEquals(2, base.grounding.size)

        // The model, seeing them, decides [2] doesn't actually fit — exactly what the repository does:
        // map the citation number back to its document id and reassess.
        val votes = RelevanceDirectives.parse("You're on Dune.\n@relevance(2): irrelevant")
        val refToId = base.grounding.mapIndexed { i, c -> (i + 1) to c.document.id }.toMap()
        val overrides = votes.associate { refToId.getValue(it.ref) to it.category }

        val refined = RelevanceEngine.reassess(base, overrides)
        assertEquals(listOf("citation:book:a"), refined.grounding.map { it.document.id })
    }
}
