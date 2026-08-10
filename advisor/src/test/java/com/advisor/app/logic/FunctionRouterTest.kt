package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FunctionRouterTest {

    @Test
    fun routes_a_word_usage_question_to_the_word_usage_function() {
        val handler = FunctionRouter.DEFAULT.handler("how many times have I said fuck?")
        assertEquals("word-usage", handler?.name)
    }

    @Test
    fun falls_through_for_questions_no_function_handles() {
        assertNull(FunctionRouter.DEFAULT.handler("what books am I reading?"))
        assertNull(FunctionRouter.DEFAULT.handler("how many tasks are due this week?"))
    }

    @Test
    fun first_matching_function_wins() {
        val a = object : AdvisorFunction {
            override val name = "a"
            override fun handles(question: String) = true
            override fun run(request: FunctionRequest) = FunctionResult("from a")
        }
        val b = object : AdvisorFunction {
            override val name = "b"
            override fun handles(question: String) = true
            override fun run(request: FunctionRequest) = FunctionResult("from b")
        }
        assertEquals("a", FunctionRouter(listOf(a, b)).handler("anything")?.name)
    }
}
