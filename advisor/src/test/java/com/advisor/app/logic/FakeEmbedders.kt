package com.advisor.app.logic

/**
 * A deterministic stand-in for a real embedding model, for JVM tests. It maps words to a small set of
 * *concept axes* (synonyms share an axis) and returns a vector counting how many of a text's words hit
 * each axis. That gives the property that matters: two texts about the same thing embed close even when
 * they share no literal words ("Who am I?" and "Name: …" both land on the identity axis), so the tests
 * exercise real semantic behaviour without a GGUF.
 */
class ConceptEmbedder(
    override val id: String = "concept-v1",
    override val isReady: Boolean = true
) : Embedder {

    /** Doc texts passed through [embedAll] — lets tests assert what actually got (re)embedded. */
    val embeddedDocTexts = mutableListOf<String>()

    /** Queries passed through [embed]. */
    val embeddedQueries = mutableListOf<String>()

    override fun embed(text: String): FloatArray {
        embeddedQueries += text
        return vectorize(text)
    }

    override fun embedAll(texts: List<String>): List<FloatArray> {
        embeddedDocTexts += texts
        return texts.map { vectorize(it) }
    }

    private fun vectorize(text: String): FloatArray {
        val v = FloatArray(AXES.size)
        for (token in text.lowercase().split(Regex("[^a-z0-9]+"))) {
            val axis = AXIS_OF[token] ?: continue
            v[axis] += 1f
        }
        return v
    }

    companion object {
        // Concept axes; every word in a group maps to that group's index.
        private val AXES: List<Set<String>> = listOf(
            setOf("who", "i", "am", "me", "my", "myself", "identity", "name", "called", "person",
                "brenden", "pronouns", "role", "engineer"),                       // 0: identity
            setOf("task", "tasks", "todo", "lawn", "mow", "chore", "pending"),    // 1: tasks
            setOf("book", "books", "read", "reading", "kotlin", "author", "library"), // 2: books
            setOf("pantry", "grocery", "flour", "food", "stock", "kitchen")       // 3: food
        )

        private val AXIS_OF: Map<String, Int> = buildMap {
            AXES.forEachIndexed { axis, words -> words.forEach { put(it, axis) } }
        }
    }
}
