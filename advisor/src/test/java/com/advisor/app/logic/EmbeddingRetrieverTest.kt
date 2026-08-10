package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingRetrieverTest {

    private fun doc(id: String, title: String, body: String, source: SourceApp = SourceApp.LIFEOPS, ts: Long = 0) =
        KnowledgeDocument(id, source, "test", title, body, ts)

    // A profile row that shares NO literal words with "Who am I?" — the case lexical retrieval misses.
    private val profile = doc("u1", "Profile", "Name: Brenden. Role: engineer. Pronouns: he/him")
    private val task = doc("t1", "Mow the lawn", "Task: mow the lawn. Status: pending")
    private val book = doc("b1", "Effective Kotlin", "Book by an author. Reading in the library", SourceApp.CITATION)

    private val corpus = listOf(profile, task, book)

    @Test
    fun matches_identity_question_that_shares_no_words_with_the_record() {
        val results = EmbeddingRetriever(corpus, ConceptEmbedder(), VectorCache.inMemory()).retrieve("Who am I?")
        assertTrue("expected a semantic hit", results.isNotEmpty())
        assertEquals("u1", results.first().document.id)
        // And it truly is a semantic, not lexical, match: the lexical retriever finds nothing here.
        assertTrue(Retriever(corpus).retrieve("Who am I?").isEmpty())
    }

    @Test
    fun drops_unrelated_documents_below_the_floor() {
        val results = EmbeddingRetriever(corpus, ConceptEmbedder(), VectorCache.inMemory()).retrieve("Who am I?")
        // Only the identity doc clears the similarity floor; task/book are unrelated.
        assertEquals(listOf("u1"), results.map { it.document.id })
    }

    @Test
    fun returns_empty_when_nothing_is_related() {
        val results = EmbeddingRetriever(corpus, ConceptEmbedder(), VectorCache.inMemory()).retrieve("submarine periscope")
        assertTrue(results.isEmpty())
    }

    @Test
    fun empty_query_or_corpus_returns_empty() {
        assertTrue(EmbeddingRetriever(corpus, ConceptEmbedder(), VectorCache.inMemory()).retrieve("   ").isEmpty())
        assertTrue(EmbeddingRetriever(emptyList(), ConceptEmbedder(), VectorCache.inMemory()).retrieve("who").isEmpty())
    }

    @Test
    fun unready_embedder_returns_empty() {
        val results = EmbeddingRetriever(corpus, ConceptEmbedder(isReady = false), VectorCache.inMemory()).retrieve("who")
        assertTrue(results.isEmpty())
    }

    @Test
    fun respects_top_k_and_breaks_ties_by_recency() {
        val many = listOf(
            doc("old", "Profile", "Name: person", ts = 100),
            doc("new", "Profile", "Name: person", ts = 999)
        )
        val r = EmbeddingRetriever(many, ConceptEmbedder(), VectorCache.inMemory())
        val top1 = r.retrieve("who am i", topK = 1)
        assertEquals(1, top1.size)
        assertEquals("new", top1.first().document.id) // equal cosine ⇒ newer wins
    }

    @Test
    fun documents_are_embedded_once_and_reused_from_cache() {
        val embedder = ConceptEmbedder()
        val cache = VectorCache.inMemory()
        val retriever = EmbeddingRetriever(corpus, embedder, cache)

        retriever.retrieve("who am i")
        assertEquals(3, embedder.embeddedDocTexts.size) // all docs embedded on the first pass

        retriever.retrieve("kotlin book")
        assertEquals(3, embedder.embeddedDocTexts.size) // second query re-embeds no documents
        assertEquals(2, embedder.embeddedQueries.size)  // but each query is embedded
    }

    @Test
    fun an_edited_document_is_reembedded() {
        val embedder = ConceptEmbedder()
        val cache = VectorCache.inMemory()

        EmbeddingRetriever(corpus, embedder, cache).retrieve("who am i")
        assertEquals(3, embedder.embeddedDocTexts.size)

        // Same ids, but the profile's body changed ⇒ its content hash changes ⇒ it must re-embed.
        val edited = listOf(profile.copy(body = "Name: Brenden. Role: founder"), task, book)
        EmbeddingRetriever(edited, embedder, cache).retrieve("who am i")
        assertEquals(4, embedder.embeddedDocTexts.size)
    }

    @Test
    fun switching_embedding_model_invalidates_the_cache() {
        val cache = VectorCache.inMemory()
        val v1 = ConceptEmbedder(id = "v1")
        EmbeddingRetriever(corpus, v1, cache).retrieve("who am i")
        assertEquals(3, v1.embeddedDocTexts.size)

        val v2 = ConceptEmbedder(id = "v2")
        EmbeddingRetriever(corpus, v2, cache).retrieve("who am i")
        assertEquals(3, v2.embeddedDocTexts.size) // different model id ⇒ nothing reused
    }
}
