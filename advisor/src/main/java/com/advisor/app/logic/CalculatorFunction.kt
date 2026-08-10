package com.advisor.app.logic

import java.math.BigDecimal
import java.math.RoundingMode

private val TRIGGER = Regex(
    """(?i)\b(calculate|compute|evaluate|solve|work out|figure out|what is|what'?s|how much is|how much are|""" +
        """plus|minus|times|divided by|multiplied by|to the power of|squared|cubed|percent)\b|%"""
)

/**
 * A plain arithmetic calculator: "what is 12.5 * 3 + 2", "calculate 15% of 200", "3/4 to the power of 2".
 * It recognises a math request, evaluates it with a small **recursive-descent parser** (no `eval`, no
 * scripting — just `+ - * / ^`, parentheses, decimals, and percent), and reports the result.
 *
 * It only claims a question it can actually evaluate (the parse has to succeed and contain an operator),
 * so "what is my name?" falls straight through to normal answering. Pure and JVM-testable.
 */
class CalculatorFunction : AdvisorFunction {

    override val name: String = "calculator"

    override fun handles(question: String): Boolean = evaluate(question) != null

    override fun run(request: FunctionRequest): FunctionResult {
        val value = evaluate(request.question)
            ?: return FunctionResult("I couldn't parse that as a calculation.")
        if (value.isNaN() || value.isInfinite()) {
            return FunctionResult("That doesn't have a finite answer — check for a division by zero.")
        }
        return FunctionResult("= ${format(value)}")
    }

    /** Extract and evaluate the arithmetic in [question]; null when it isn't a computable expression. */
    fun evaluate(question: String): Double? {
        var s = question.lowercase().trim().removeSuffix("=").removeSuffix("?").trim()
        // Only treat this as math when it's clearly a calculation — an explicit ask ("calculate", "what
        // is"), a word/percent operator, or a string that is *only* an expression. This keeps date- and
        // id-like text ("what did I do on 2026-08-10") from being read as 2026 - 8 - 10.
        val isPureExpression = s.matches(Regex("""[0-9+\-*/^(). ]+"""))
        if (!isPureExpression && !TRIGGER.containsMatchIn(s)) return null
        // 1,000 → 1000 so thousands separators don't split a number.
        s = s.replace(Regex("""(?<=\d),(?=\d)"""), "")
        // "15% of 200" → (15/100*200); a bare "15%" → (15/100).
        s = s.replace(Regex("""([\d.]+)\s*(?:%|percent)\s+of\s+([\d.]+)""")) { m ->
            "(${m.groupValues[1]}/100*${m.groupValues[2]})"
        }
        s = s.replace(Regex("""([\d.]+)\s*(?:%|percent)""")) { m -> "(${m.groupValues[1]}/100)" }
        // Word operators.
        s = s.replace(Regex("""\bplus\b"""), "+")
            .replace(Regex("""\bminus\b"""), "-")
            .replace(Regex("""\b(?:times|multiplied by)\b"""), "*")
            .replace(Regex("""\b(?:divided by|over)\b"""), "/")
            .replace(Regex("""\bto the power of\b"""), "^")
            .replace(Regex("""\bsquared\b"""), "^2")
            .replace(Regex("""\bcubed\b"""), "^3")
        // Keep only expression characters; words collapse to spaces.
        val expr = s.replace(Regex("""[^0-9+\-*/^(). ]"""), " ").trim()
        if (expr.none { it.isDigit() }) return null
        if (!Regex("""[+\-*/^]""").containsMatchIn(expr)) return null // must be a calculation, not a lone number
        return runCatching { Parser(expr).parse() }.getOrNull()
    }

    /** Trim 3.0 → "3" but keep 2.5; round to 6 dp so float noise doesn't leak. */
    private fun format(value: Double): String {
        if (value == Math.floor(value) && !value.isInfinite() && kotlin.math.abs(value) < 1e15) {
            return value.toLong().toString()
        }
        return BigDecimal(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    /**
     * Recursive-descent evaluator over `+ - * / ^`, unary sign, and parentheses. Throws on any malformed
     * input, which [evaluate] turns into "not a calculation".
     */
    private class Parser(text: String) {
        private val tokens = tokenize(text)
        private var pos = 0

        fun parse(): Double {
            val v = expr()
            if (pos != tokens.size) throw IllegalArgumentException("trailing tokens")
            return v
        }

        // expr := term (('+'|'-') term)*
        private fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    "+" -> { next(); v += term() }
                    "-" -> { next(); v -= term() }
                    else -> return v
                }
            }
        }

        // term := power (('*'|'/') power)*
        private fun term(): Double {
            var v = power()
            while (true) {
                when (peek()) {
                    "*" -> { next(); v *= power() }
                    "/" -> { next(); v /= power() }
                    else -> return v
                }
            }
        }

        // power := unary ('^' power)?   (right-associative)
        private fun power(): Double {
            val base = unary()
            return if (peek() == "^") { next(); Math.pow(base, power()) } else base
        }

        // unary := ('+'|'-') unary | primary
        private fun unary(): Double = when (peek()) {
            "+" -> { next(); unary() }
            "-" -> { next(); -unary() }
            else -> primary()
        }

        // primary := number | '(' expr ')'
        private fun primary(): Double {
            val t = peek() ?: throw IllegalArgumentException("unexpected end")
            if (t == "(") {
                next()
                val v = expr()
                if (peek() != ")") throw IllegalArgumentException("missing )")
                next()
                return v
            }
            next()
            return t.toDoubleOrNull() ?: throw IllegalArgumentException("expected number, got $t")
        }

        private fun peek(): String? = tokens.getOrNull(pos)
        private fun next() { pos++ }

        companion object {
            private fun tokenize(text: String): List<String> {
                val tokens = ArrayList<String>()
                var i = 0
                while (i < text.length) {
                    val c = text[i]
                    when {
                        c.isWhitespace() -> i++
                        c.isDigit() || c == '.' -> {
                            val start = i
                            while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                            tokens += text.substring(start, i)
                        }
                        c in "+-*/^()" -> { tokens += c.toString(); i++ }
                        else -> throw IllegalArgumentException("bad char $c")
                    }
                }
                return tokens
            }
        }
    }
}
