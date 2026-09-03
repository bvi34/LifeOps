package com.operations.suitekit

/**
 * What a number field lets through while somebody is still typing into it.
 *
 * These are filters, not validators, and the difference is the whole design. A filter runs on every
 * keystroke and must therefore accept every *prefix* of a valid answer — including the ones that
 * are not answers yet. `"0."` is what `"0.34"` looks like halfway through, and `"-"` is what `"-5"`
 * looks like after one key; a filter that tidied either away would make its field impossible to type
 * into. Deciding whether the finished text means anything is the caller's job, and it happens later.
 */
object SuiteInput {

    /** Digits only — a mileage, a year, an interval. */
    fun digits(text: String): String = text.filter { it.isDigit() }

    /**
     * Digits, and a leading minus.
     *
     * The minus survives on its own, because that is the first keystroke of every negative number,
     * and it is only honoured at the front: `12-3` is a typo, not a subtraction.
     */
    fun signedDigits(text: String): String {
        val negative = text.startsWith("-")
        val body = digits(text)
        return if (negative) "-$body" else body
    }

    /**
     * The digits, and the first separator typed, as a point.
     *
     * Two details it would be easy to get wrong. The point **survives with nothing after it**,
     * because "0." is what "0.34" looks like halfway through typing. And a **comma counts as the
     * separator**: half the world's keyboards offer one there, and dropping it would silently turn
     * 0,34 acres into 34.
     */
    fun decimal(text: String): String {
        var pointed = false
        return buildString {
            text.forEach { char ->
                when {
                    char.isDigit() -> append(char)
                    (char == '.' || char == ',') && !pointed -> {
                        pointed = true
                        append('.')
                    }
                }
            }
        }
    }

    /** A decimal that may be negative — a temperature, a balance that has gone the wrong way. */
    fun signedDecimal(text: String): String {
        val negative = text.startsWith("-")
        val body = decimal(text)
        return if (negative) "-$body" else body
    }

    /** The filter for a given field shape, so the field itself has no `when` in it. */
    fun filter(text: String, decimals: Boolean, signed: Boolean): String = when {
        decimals && signed -> signedDecimal(text)
        decimals -> decimal(text)
        signed -> signedDigits(text)
        else -> digits(text)
    }
}
