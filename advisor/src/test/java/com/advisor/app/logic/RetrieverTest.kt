package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrieverTest {

    private fun doc(id: String, title: String, body: String, source: SourceApp = SourceApp.LIFEOPS, ts: Long = 0) =
        KnowledgeDocument(id, source, "test", title, body, ts)

    private val corpus = listOf(
        doc("t1", "Mow the lawn", "Task: Mow the lawn. Status: pending. Aspect: Body"),
        doc("t2", "Read Kotlin book", "Task: Read Kotlin book. Status: completed. Aspect: Craft"),
        doc("b1", "Effective Kotlin", "Book: Effective Kotlin by Marcin. Reading state: reading", SourceApp.CITATION),
        doc("p1", "Flour", "Pantry item: Flour. In stock: 1 kg. Running low.", SourceApp.LOGISTICS)
    )

    @Test
    fun ranks_documents_containing_query_terms_first() {
        val results = Retriever(corpus).retrieve("kotlin book")
        assertTrue("expected at least one hit", results.isNotEmpty())
        // Both Kotlin docs should rank above unrelated ones; the top hit must be a Kotlin doc.
        assertTrue(results.first().document.id in setOf("t2", "b1"))
        assertTrue(results.all { it.score > 0.0 })
    }

    @Test
    fun returns_empty_for_no_overlap() {
        val results = Retriever(corpus).retrieve("submarine periscope")
        assertTrue(results.isEmpty())
    }

    @Test
    fun stopwords_only_query_returns_empty() {
        val results = Retriever(corpus).retrieve("what is the how do i")
        assertTrue(results.isEmpty())
    }

    @Test
    fun title_match_outranks_body_only_match() {
        val docs = listOf(
            doc("title", "Flour supply", "notes about the kitchen"),
            doc("body", "Kitchen", "we might need flour eventually maybe")
        )
        val results = Retriever(docs).retrieve("flour")
        assertEquals("title", results.first().document.id)
    }

    @Test
    fun respects_top_k_limit() {
        val many = (1..20).map { doc("d$it", "task number $it", "flour task $it") }
        val results = Retriever(many).retrieve("flour", topK = 5)
        assertEquals(5, results.size)
    }

    @Test
    fun recency_breaks_score_ties() {
        val docs = listOf(
            doc("old", "flour", "flour", ts = 100),
            doc("new", "flour", "flour", ts = 999)
        )
        val results = Retriever(docs).retrieve("flour")
        assertEquals("new", results.first().document.id)
    }
}
