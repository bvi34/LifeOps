package com.advisor.app.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregateFunctionTest {

    private val fn = AggregateFunction()

    private fun task(id: String, title: String, body: String) =
        KnowledgeDocument("lifeops:task:$id", SourceApp.LIFEOPS, "task", title, body)

    private fun milestone(id: String, title: String, body: String) =
        KnowledgeDocument("lifeops:milestone:$id", SourceApp.LIFEOPS, "milestone", title, body)

    private fun book(id: String, title: String) =
        KnowledgeDocument("citation:book:$id", SourceApp.CITATION, "book", title, "Book: $title")

    private val tasks = listOf(
        task("1", "A", "Task: A. Status: done. Priority: high. Estimate: 30 min"),
        task("2", "B", "Task: B. Status: todo. Priority: low. Estimate: 45 min"),
        task("3", "C", "Task: C. Status: done. Priority: medium")
    )
    private val milestones = listOf(
        milestone("1", "5k", "Milestone: 5k (5 pts). Achieved: 2026-01-01"),
        milestone("2", "10k", "Milestone: 10k (10 pts). Achieved: 2026-02-01")
    )

    private fun req(q: String, corpus: List<KnowledgeDocument>, granted: Set<SourceApp> = setOf(SourceApp.LIFEOPS)) =
        FunctionRequest(question = q, corpus = corpus, grantedApps = granted)

    @Test
    fun counts_tasks() {
        assertTrue(fn.handles("how many tasks do I have?"))
        assertTrue(fn.run(req("how many tasks do I have?", tasks)).text.contains("You have 3 tasks"))
    }

    @Test
    fun counts_tasks_filtered_by_status() {
        val text = fn.run(req("how many tasks are done?", tasks)).text
        assertTrue(text, text.contains("2 of your 3 tasks are done"))
    }

    @Test
    fun sums_task_estimate_minutes() {
        val text = fn.run(req("how much time do my tasks take?", tasks)).text
        // 30 + 45 = 75 min across the two estimated tasks.
        assertTrue(text, text.contains("75 min"))
        assertTrue(text, text.contains("1h 15m"))
    }

    @Test
    fun averages_task_estimates() {
        val text = fn.run(req("what's my average task estimate?", tasks)).text
        assertTrue(text, text.contains("37.5 min"))
    }

    @Test
    fun totals_milestone_points() {
        assertTrue(fn.handles("how many points have I earned?"))
        val text = fn.run(req("how many points have I earned?", milestones)).text
        assertTrue(text, text.contains("15 pts"))
    }

    @Test
    fun counts_books_from_citation() {
        val corpus = listOf(book("a", "Dune"), book("b", "Neuromancer"))
        val text = fn.run(req("how many books do I have?", corpus, granted = setOf(SourceApp.CITATION))).text
        assertTrue(text, text.contains("You have 2 books"))
    }

    @Test
    fun counts_memories_without_a_permission() {
        val req = FunctionRequest(
            question = "how many memories do I have?",
            memories = listOf(
                MemoryRecord(id = "m1", content = "one"),
                MemoryRecord(id = "m2", content = "two")
            )
        )
        assertTrue(fn.run(req).text.contains("You have 2 memories"))
    }

    @Test
    fun gates_numeric_rollups_on_the_lifeops_permission() {
        val text = fn.run(req("how much time do my tasks take?", tasks, granted = emptySet())).text
        assertTrue(text, text.contains("LifeOps"))
    }

    @Test
    fun does_not_claim_word_or_math_questions() {
        assertFalse(fn.handles("how many times have I said fuck?"))
        assertFalse(fn.handles("what is 2 + 2?"))
        assertFalse(fn.handles("what's running low?"))
    }
}
