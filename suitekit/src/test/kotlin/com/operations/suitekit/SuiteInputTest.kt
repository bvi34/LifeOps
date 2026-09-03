package com.operations.suitekit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a number field lets through as it is being typed.
 *
 * The rules are small and each one is a field that was impossible to fill in without it: a lot size
 * is 0.34 acres, the dot has to survive the keystroke before the 3, and a keyboard that offers a
 * comma there must not silently turn a third of an acre into thirty-four of them.
 *
 * Every case here is a *prefix* case. That is the whole discipline of a filter: it runs between two
 * keystrokes, so it has to accept the halfway states, and anything that only makes sense about
 * finished text belongs in a validator instead.
 */
class SuiteInputTest {

    @Test
    fun `a decimal is typed the way it is written`() {
        assertEquals("0.34", SuiteInput.decimal("0.34"))
        assertEquals("12", SuiteInput.decimal("12"))
        assertEquals("", SuiteInput.decimal(""))
    }

    @Test
    fun `the point survives with nothing after it yet`() {
        // "0." is what "0.34" looks like two keystrokes in. Tidying it away here is what makes a
        // decimal field impossible to type a decimal into.
        assertEquals("0.", SuiteInput.decimal("0."))
        assertEquals(".", SuiteInput.decimal("."))
    }

    @Test
    fun `a comma is the separator it was meant to be, not a thousands mark to drop`() {
        assertEquals("0.34", SuiteInput.decimal("0,34"))
    }

    @Test
    fun `only the first separator counts, and nothing else gets in`() {
        assertEquals("1.50", SuiteInput.decimal("1.5.0"))
        assertEquals("1.50", SuiteInput.decimal("1.5,0"))
        assertEquals("34", SuiteInput.decimal("34 acres"))
        assertEquals("12", SuiteInput.decimal("-12"))
    }

    @Test
    fun `a whole number is digits and nothing else`() {
        assertEquals("2026", SuiteInput.digits("2026"))
        assertEquals("2026", SuiteInput.digits("20x26"))
        assertEquals("12", SuiteInput.digits("-12"))
        assertEquals("", SuiteInput.digits("abc"))
    }

    @Test
    fun `a signed field keeps a leading minus, including on its own`() {
        // "-" is the first keystroke of every negative number. A filter that dropped it would make
        // the field impossible to type one into — the same trap as the lone decimal point.
        assertEquals("-", SuiteInput.signedDigits("-"))
        assertEquals("-12", SuiteInput.signedDigits("-12"))
        assertEquals("-4.5", SuiteInput.signedDecimal("-4.5"))
        assertEquals("-", SuiteInput.signedDecimal("-"))
    }

    @Test
    fun `a minus is only a sign at the front`() {
        // 12-3 is a typo, not a subtraction.
        assertEquals("123", SuiteInput.signedDigits("12-3"))
        assertEquals("-123", SuiteInput.signedDigits("-12-3"))
    }

    @Test
    fun `the shape selector agrees with the four filters it stands for`() {
        assertEquals(SuiteInput.digits("-1.5"), SuiteInput.filter("-1.5", decimals = false, signed = false))
        assertEquals(SuiteInput.decimal("-1.5"), SuiteInput.filter("-1.5", decimals = true, signed = false))
        assertEquals(SuiteInput.signedDigits("-1.5"), SuiteInput.filter("-1.5", decimals = false, signed = true))
        assertEquals(SuiteInput.signedDecimal("-1.5"), SuiteInput.filter("-1.5", decimals = true, signed = true))
    }
}
