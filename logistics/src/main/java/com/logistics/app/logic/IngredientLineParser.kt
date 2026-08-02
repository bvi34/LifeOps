package com.logistics.app.logic

import com.logistics.app.data.model.ParsedIngredient

/**
 * Splits a free-form recipe ingredient line ("2 cups all-purpose flour", "1/2 tsp salt",
 * "3 large eggs", "salt to taste") into a leading quantity, an optional unit, and the remaining
 * name. Framework-free and unit-tested. It is intentionally forgiving: anything it can't confidently
 * split falls through as name-only, so no ingredient is ever lost.
 */
object IngredientLineParser {

    // Words we treat as measurement units. Anything else after the number (e.g. "large") stays part
    // of the name, since it describes the item, not an amount.
    private val UNITS = setOf(
        "cup", "cups", "c",
        "tablespoon", "tablespoons", "tbsp", "tbsp.", "tbs", "tb",
        "teaspoon", "teaspoons", "tsp", "tsp.",
        "ounce", "ounces", "oz", "oz.",
        "pound", "pounds", "lb", "lbs", "lb.", "lbs.",
        "gram", "grams", "g", "kg", "kilogram", "kilograms",
        "milliliter", "milliliters", "ml", "liter", "liters", "l",
        "pinch", "pinches", "dash", "dashes",
        "clove", "cloves", "can", "cans", "package", "packages", "pkg", "pkg.",
        "slice", "slices", "stick", "sticks",
        "quart", "quarts", "pint", "pints", "gallon", "gallons",
        "jar", "jars", "bottle", "bottles", "bag", "bags", "box", "boxes",
        "handful", "sprig", "sprigs", "head", "heads", "bunch", "bunches"
    )

    private val UNICODE_FRACTIONS = mapOf(
        '¼' to 0.25, '½' to 0.5, '¾' to 0.75,
        '⅓' to (1.0 / 3.0), '⅔' to (2.0 / 3.0),
        '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875
    )

    fun parse(raw: String): ParsedIngredient {
        val line = raw.trim()
        if (line.isEmpty()) return ParsedIngredient(null, null, "", raw)

        val tokens = line.split(Regex("\\s+")).toMutableList()

        // 1) Leading quantity: a run of numeric/fraction tokens ("1", "1/2", "1 1/2", "½", "1½").
        var quantity: Double? = null
        var consumed = 0
        val qtyBuilder = StringBuilder()
        while (consumed < tokens.size) {
            val t = tokens[consumed]
            val v = parseNumberToken(t)
            if (v == null) break
            quantity = (quantity ?: 0.0) + v
            qtyBuilder.append(t)
            consumed++
            // Stop after a whole+fraction pair like "1 1/2"; if the next token is also numeric we
            // keep folding (handles "1 1/2" and lone fractions alike).
        }

        // 2) Optional unit immediately after the quantity.
        var unit: String? = null
        if (quantity != null && consumed < tokens.size) {
            val candidate = tokens[consumed].trim('.', ',').lowercase()
            if (candidate in UNITS) {
                unit = tokens[consumed].trim('.', ',')
                consumed++
            }
        }

        val name = tokens.drop(consumed).joinToString(" ").trim().trim('-', ',', ' ')
        // If we stripped everything (line was just a number), keep the original as the name.
        val finalName = if (name.isBlank()) line else name
        return ParsedIngredient(quantity, unit, finalName, raw)
    }

    /** Parse one token as a number: integer, decimal, "1/2", "1½", or a lone unicode fraction. */
    private fun parseNumberToken(token: String): Double? {
        val t = token.trim()
        if (t.isEmpty()) return null

        // Lone unicode fraction or leading-digit + unicode fraction ("1½").
        if (t.length >= 1 && t.last() in UNICODE_FRACTIONS) {
            val frac = UNICODE_FRACTIONS[t.last()]!!
            val whole = t.dropLast(1)
            if (whole.isEmpty()) return frac
            val w = whole.toDoubleOrNull() ?: return null
            return w + frac
        }
        // "a/b" fraction.
        if (t.contains('/')) {
            val parts = t.split('/')
            if (parts.size == 2) {
                val num = parts[0].toDoubleOrNull()
                val den = parts[1].toDoubleOrNull()
                if (num != null && den != null && den != 0.0) return num / den
            }
            return null
        }
        // plain number (allow a trailing comma from "2, ")
        return t.trimEnd(',').toDoubleOrNull()
    }
}
