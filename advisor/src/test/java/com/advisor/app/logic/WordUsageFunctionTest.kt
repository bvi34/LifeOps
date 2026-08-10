package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordUsageFunctionTest {

    private val fn = WordUsageFunction()

    private fun turn(user: Boolean, text: String) = ConversationTurn(fromUser = user, text = text)

    private fun task(id: String, title: String, body: String = "") =
        KnowledgeDocument("lifeops:task:$id", SourceApp.LIFEOPS, "task", title, body)

    @Test
    fun handles_the_screenshot_prompts_but_not_ordinary_questions() {
        assertTrue(fn.handles("how many times have I said fuck?"))
        assertTrue(fn.handles("Can you count my word usage in my tasks?"))
        assertFalse(fn.handles("how many tasks do I have?"))
        assertFalse(fn.handles("what's due today?"))
    }

    @Test
    fun counts_a_specific_word_across_the_users_messages() {
        val req = FunctionRequest(
            question = "how many times have I said fuck?",
            conversation = listOf(
                turn(true, "ugh fuck this bug"),
                turn(false, "I hear you"),
                turn(true, "fuck it, shipping anyway"),
                turn(true, "all good now")
            )
        )
        val result = fn.run(req)
        assertTrue(result.text, result.text.contains("2 times"))
        assertTrue(result.text.contains("our conversation"))
    }

    @Test
    fun counts_only_the_named_subject() {
        val req = FunctionRequest(
            question = "how many times have you said sorry?",
            conversation = listOf(
                turn(true, "sorry to bug you"),          // user — should not count
                turn(false, "sorry about that, fixing"), // assistant — counts
                turn(false, "no need to say sorry")      // assistant — counts
            )
        )
        val result = fn.run(req)
        assertTrue(result.text, result.text.contains("2 times"))
    }

    @Test
    fun reports_zero_matches_honestly() {
        val req = FunctionRequest(
            question = "how many times have I said zebra?",
            conversation = listOf(turn(true, "nothing about the animal here"))
        )
        assertTrue(fn.run(req).text.contains("0 matches"))
    }

    @Test
    fun word_boundaries_prevent_substring_overcounting() {
        val req = FunctionRequest(
            question = "how many times have I said cat?",
            conversation = listOf(turn(true, "the cat sat, concatenate, category, cat"))
        )
        // "cat" as a whole word appears twice; "concatenate"/"category" must not count.
        assertTrue(fn.run(req).text.contains("2 times"))
    }

    @Test
    fun summarizes_most_used_words_in_tasks() {
        val req = FunctionRequest(
            question = "count my word usage in my tasks",
            corpus = listOf(
                task("1", "Build the report", "report draft"),
                task("2", "Review report", "report review")
            ),
            grantedApps = setOf(SourceApp.LIFEOPS)
        )
        val result = fn.run(req)
        assertTrue(result.text, result.text.contains("most-used words in your tasks"))
        // "report" appears twice in each of the two tasks (title + body).
        assertTrue(result.text, result.text.contains("report — 4"))
    }

    @Test
    fun scoped_count_cites_the_matching_task_documents() {
        val req = FunctionRequest(
            question = "how many times have I used the word report in my tasks?",
            corpus = listOf(
                task("1", "Build the report", "report draft"),
                task("2", "Buy milk")
            ),
            grantedApps = setOf(SourceApp.LIFEOPS)
        )
        val result = fn.run(req)
        assertTrue(result.text, result.text.contains("2 times"))
        assertEquals(listOf("lifeops:task:1"), result.citations.map { it.id })
    }

    @Test
    fun a_scope_whose_app_is_disabled_asks_to_enable_it_rather_than_saying_zero() {
        val req = FunctionRequest(
            question = "count my word usage in my tasks",
            corpus = emptyList(),
            grantedApps = emptySet() // LifeOps not granted
        )
        val result = fn.run(req)
        assertTrue(result.text, result.text.contains("LifeOps"))
        assertTrue(result.text.contains("Permissions"))
    }
}
