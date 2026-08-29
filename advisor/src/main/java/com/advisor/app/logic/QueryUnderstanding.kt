package com.advisor.app.logic

/**
 * Small, deterministic natural-language normalization shared by retrieval and grounding.
 *
 * Advisor often receives conversational phrasing ("what should I tackle?", "do we need food?",
 * "that novel") while the local app rows use product words (task, grocery, book). This object keeps
 * those mappings explicit and JVM-testable so the assistant can understand everyday language without
 * sending anything off-device.
 */
object QueryUnderstanding {

    private val CONTRACTIONS = listOf(
        "what's" to "what is", "whats" to "what is",
        "who's" to "who is", "whos" to "who is",
        "where's" to "where is", "wheres" to "where is",
        "when's" to "when is", "whens" to "when is",
        "how's" to "how is", "hows" to "how is",
        "i'm" to "i am", "im" to "i am",
        "i've" to "i have", "ive" to "i have",
        "don't" to "do not", "dont" to "do not",
        "can't" to "cannot", "cant" to "cannot",
        "won't" to "will not", "wont" to "will not",
        "you're" to "you are", "youre" to "you are"
    )

    private val SYNONYMS: Map<String, Set<String>> = mapOf(
        "todo" to setOf("task", "tasks"),
        "todos" to setOf("task", "tasks"),
        "to-do" to setOf("task", "tasks"),
        "chore" to setOf("task", "tasks"),
        "chores" to setOf("task", "tasks"),
        "errand" to setOf("task"),
        "errands" to setOf("task", "tasks"),
        "tackle" to setOf("task", "priority"),
        "due" to setOf("deadline", "task"),
        "urgent" to setOf("priority", "deadline"),
        "goal" to setOf("operation", "milestone"),
        "goals" to setOf("operations", "milestones"),

        "food" to setOf("grocery", "groceries", "pantry", "meal"),
        "shop" to setOf("grocery", "groceries"),
        "shopping" to setOf("grocery", "groceries"),
        "buy" to setOf("grocery", "groceries"),
        "cook" to setOf("recipe", "meal", "ingredient"),
        "cooking" to setOf("recipe", "meal", "ingredient"),
        "dinner" to setOf("meal", "recipe"),
        "lunch" to setOf("meal", "recipe"),
        "breakfast" to setOf("meal", "recipe"),
        "running" to setOf("low"),
        "low" to setOf("stock", "pantry"),

        "novel" to setOf("book", "reading"),
        "novels" to setOf("books", "reading"),
        "story" to setOf("book", "reading"),
        "stories" to setOf("books", "reading"),
        "quote" to setOf("highlight", "note"),
        "quotes" to setOf("highlights", "notes"),
        "library" to setOf("book", "books")
    )

    private val POLITE_FILLERS = setOf(
        "please", "pls", "hey", "hi", "hello", "okay", "ok", "so", "actually", "maybe",
        "kinda", "kind", "sorta", "sort", "just", "really", "quick", "quickly", "tell", "show",
        "give", "let", "lets", "help", "need", "want", "wanna", "gonna"
    )

    fun normalize(text: String): String {
        var out = text.lowercase()
        for ((from, to) in CONTRACTIONS) {
            out = out.replace(Regex("\\b" + Regex.escape(from) + "\\b"), to)
        }
        return out
    }

    fun expandTerms(tokens: Iterable<String>): Set<String> {
        val expanded = LinkedHashSet<String>()
        for (token in tokens) {
            if (token in POLITE_FILLERS) continue
            expanded += token
            SYNONYMS[token]?.let { expanded += it }
        }
        return expanded
    }
}
