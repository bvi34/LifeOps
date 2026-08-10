package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRetrieverTest {

    private fun doc(id: String, title: String, body: String, source: SourceApp = SourceApp.LIFEOPS) =
        KnowledgeDocument(id, source, "test", title, body)

    private val profile = doc("u1", "Profile", "Name: Brenden. Role: engineer")
    private val flour = doc("p1", "Flour", "Pantry item: flour. Running low", SourceApp.LOGISTICS)
    private val corpus = listOf(profile, flour)

    @Test
    fun falls_back_to_lexical_when_no_embedder() {
        val hybrid = HybridRetriever() // Embedder.NONE
        assertFalse(hybrid.isSemantic)

        // Lexical works on shared words…
        assertEquals("p1", hybrid.retrieve(corpus, "flour").first().document.id)
        // …and, being lexical, misses the no-shared-words identity question.
        assertTrue(hybrid.retrieve(corpus, "Who am I?").isEmpty())
    }

    @Test
    fun uses_semantic_retrieval_when_an_embedder_is_ready() {
        val hybrid = HybridRetriever(ConceptEmbedder(), VectorCache.inMemory())
        assertTrue(hybrid.isSemantic)

        // The case the lexical path could not do: match identity with no shared words.
        assertEquals("u1", hybrid.retrieve(corpus, "Who am I?").first().document.id)
    }

    @Test
    fun revocation_is_honoured_because_corpus_is_passed_per_call() {
        val hybrid = HybridRetriever(ConceptEmbedder(), VectorCache.inMemory())
        // A later call with the app's rows removed (as the repository does when it's revoked) sees none.
        assertTrue(hybrid.retrieve(emptyList(), "Who am I?").isEmpty())
    }
}
