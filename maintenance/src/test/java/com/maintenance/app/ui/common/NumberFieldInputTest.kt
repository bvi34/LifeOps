package com.maintenance.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a decimal number field lets through as it is being typed.
 *
 * The rules are small and each one is a field that was impossible to fill in without it: a lot size
 * is 0.34 acres, the dot has to survive the keystroke before the 3, and a keyboard that offers a
 * comma there must not silently turn a third of an acre into thirty-four of them.
 */
class NumberFieldInputTest {

    @Test
    fun `a decimal is typed the way it is written`() {
        assertEquals("0.34", digitsAndOnePoint("0.34"))
        assertEquals("12", digitsAndOnePoint("12"))
        assertEquals("", digitsAndOnePoint(""))
    }

    @Test
    fun `the point survives with nothing after it yet`() {
        // "0." is what "0.34" looks like two keystrokes in. Tidying it away here is what makes a
        // decimal field impossible to type a decimal into.
        assertEquals("0.", digitsAndOnePoint("0."))
        assertEquals(".", digitsAndOnePoint("."))
    }

    @Test
    fun `a comma is the separator it was meant to be, not a thousands mark to drop`() {
        assertEquals("0.34", digitsAndOnePoint("0,34"))
    }

    @Test
    fun `only the first separator counts, and nothing else gets in`() {
        assertEquals("1.50", digitsAndOnePoint("1.5.0"))
        assertEquals("1.50", digitsAndOnePoint("1.5,0"))
        assertEquals("34", digitsAndOnePoint("34 acres"))
        assertEquals("12", digitsAndOnePoint("-12"))
    }
}
