package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FunctionRouterTest {

    @Test
    fun routes_each_question_to_the_right_capability() {
        val r = FunctionRouter.DEFAULT
        assertEquals("calculator", r.handler("what is 12 * 3?")?.name)
        assertEquals("word-usage", r.handler("how many times have I said fuck?")?.name)
        assertEquals("inventory", r.handler("what's running low?")?.name)
        assertEquals("aggregate", r.handler("how many tasks do I have?")?.name)
    }

    @Test
    fun falls_through_for_questions_no_function_handles() {
        assertNull(FunctionRouter.DEFAULT.handler("what books am I reading?"))
        assertNull(FunctionRouter.DEFAULT.handler("what should I focus on today?"))
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
