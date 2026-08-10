package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorFunctionTest {

    private val fn = CalculatorFunction()
    private fun eval(q: String) = fn.evaluate(q)
    private fun answer(q: String) = fn.run(FunctionRequest(question = q)).text

    @Test
    fun evaluates_basic_arithmetic_with_precedence() {
        assertEquals(39.5, eval("what is 12.5 * 3 + 2")!!, 1e-9)
        assertEquals(14.0, eval("2 + 3 * 4")!!, 1e-9)
        assertEquals(10.0, eval("(4 + 1) * 2")!!, 1e-9)
        assertEquals(2.5, eval("10 / 4")!!, 1e-9)
    }

    @Test
    fun handles_percentages_and_powers_and_words() {
        assertEquals(30.0, eval("what's 15% of 200")!!, 1e-9)
        assertEquals(9.0, eval("3 to the power of 2")!!, 1e-9)
        assertEquals(9.0, eval("3 squared")!!, 1e-9)
        assertEquals(60.0, eval("100 minus 40")!!, 1e-9)
        assertEquals(1000.0, eval("500 times 2")!!, 1e-9)
    }

    @Test
    fun formats_integers_without_a_trailing_point() {
        assertEquals("= 4", answer("what is 2 + 2"))
        assertEquals("= 39.5", answer("12.5 * 3 + 2"))
    }

    @Test
    fun does_not_claim_non_math_questions() {
        assertFalse(fn.handles("what is my name?"))
        assertFalse(fn.handles("how many tasks do I have?"))
        assertFalse(fn.handles("what is 5"))       // a lone number is not a calculation
        assertFalse(fn.handles("tell me about my day"))
        // A date with hyphens must not be read as 2026 - 8 - 10.
        assertFalse(fn.handles("what did I do on 2026-08-10?"))
    }

    @Test
    fun division_by_zero_is_reported_not_crashed() {
        assertTrue(fn.handles("what is 1 / 0"))
        assertTrue(answer("what is 1 / 0").contains("finite"))
    }
}
