package com.logistics.app.logic

import com.logistics.app.data.model.ParsedRecipe

/**
 * Turns the flat text of a **screenshotted** recipe into a [ParsedRecipe].
 *
 * A recipe link hands us schema.org data — named fields, no guessing ([RecipeLinkParser]). A
 * screenshot hands us whatever the OCR read, top to bottom, with the page's furniture still in it:
 * a title, "Prep time 10 min", a *Print* button, the ingredient list, the method. So this parser is
 * a **layout** reader rather than a format reader, and it is deliberately forgiving — a line it
 * can't classify falls into the ingredient list rather than being dropped, because a wrong line the
 * user can delete beats a missing one they have to notice.
 *
 * What it looks for, in order:
 *
 * 1. **Section headers.** "Ingredients" and "Directions"/"Instructions"/"Method"/"Steps" are the two
 *    words nearly every recipe layout prints, and they split the page into three regions: the
 *    title block, the ingredients, and the method.
 * 2. **Servings**, from "Serves 4", "4 servings", "Yield: 4", "Makes 12" — anywhere on the page,
 *    since layouts put it above the title, under it, or beside the times.
 * 3. **The title**, the first line of the title block that isn't page furniture (times, ratings, a
 *    "Print", "Save" or "Jump to recipe" button) and doesn't read like an ingredient.
 *
 * When there is no "Ingredients" header at all — a cropped screenshot of just the list — every line
 * that *starts with a quantity* is taken as an ingredient, which is what those crops look like.
 *
 * Framework-free and unit-tested, like the rest of `logic/`: the OCR that feeds it is the only part
 * that needs a device.
 */
object RecipeTextParser {

    /** The heading above the list. Trailing text is allowed ("Ingredients for the sauce", "(8)"). */
    private val INGREDIENT_HEADER = Regex(
        "^(ingredients?|what you'?ll need|you'?ll need|shopping list)\\b.*$",
        RegexOption.IGNORE_CASE
    )

    /** The heading above the method — the second-least standardised word in recipe layouts. */
    private val METHOD_HEADER = Regex(
        "^(directions?|instructions?|method|steps|preparation|procedure|how to make)\\b.*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Page furniture. These lines are real text on the page and pure noise in a recipe: times,
     * ratings, share buttons, the site's own chrome. They are dropped *after* servings have been
     * read off them, since "Serves 4" is both noise and the one number worth keeping.
     */
    private val NOISE = Regex(
        "^(prep(aration)?\\s*time|cook(ing)?\\s*time|total\\s*time|active\\s*time|rest(ing)?\\s*time|" +
            "chill\\s*time|bake\\s*time|serves|servings?|yields?|makes|portions?|calories|nutrition|" +
            "print|save|share|pin|jump to|rate this|course|cuisine|category|difficulty|author|" +
            "adapted from|photo|advertisement|ingredients checklist)\\b.*$",
        RegexOption.IGNORE_CASE
    )

    /** Bullets, checkboxes and dashes recipe layouts hang off the front of a line. */
    private const val BULLETS = "•·▪●○◦*-–—▢□☐✓✔"

    /** "1. ", "2) ", "Step 3:" — numbering the method prints and the recipe doesn't need. */
    private val STEP_NUMBER = Regex("^(step\\s*)?\\d{1,2}\\s*[.):\\-]\\s+", RegexOption.IGNORE_CASE)

    /** A line that opens with an amount, which is what an ingredient looks like without its header. */
    private val QUANTITY_LEAD = Regex("^([0-9]|[¼½¾⅓⅔⅛⅜⅝⅞])")

    private val SERVINGS_PATTERNS = listOf(
        Regex("\\bserves\\s*:?\\s*(\\d{1,3})", RegexOption.IGNORE_CASE),
        Regex("\\byields?\\s*:?\\s*(\\d{1,3})", RegexOption.IGNORE_CASE),
        Regex("\\bmakes\\s*:?\\s*(?:about\\s+)?(\\d{1,3})", RegexOption.IGNORE_CASE),
        Regex("\\b(\\d{1,3})\\s*(?:-|\\s)?\\s*servings?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bservings?\\s*:?\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
    )

    /**
     * Parse OCR'd (or pasted) recipe text. [fallbackName] is used when the page had no line that
     * could be a title — a crop of nothing but the ingredient list, say.
     */
    fun parse(text: String, fallbackName: String = "Recipe"): ParsedRecipe {
        val cleaned = text.lineSequence().map { clean(it) }.filter { it.isNotBlank() }.toList()
        // Two screenshots of one page usually overlap at the seam; drop the repeated line.
        val lines = cleaned.filterIndexed { i, line -> i == 0 || !line.equals(cleaned[i - 1], ignoreCase = true) }

        if (lines.isEmpty()) return ParsedRecipe(fallbackName, null, emptyList())

        val servings = findServings(lines)
        val ingredientHeader = lines.indexOfFirst { INGREDIENT_HEADER.matches(it) }
        val methodHeader = lines.withIndex()
            .indexOfFirst { (i, line) -> METHOD_HEADER.matches(line) && i > ingredientHeader }

        val titleBlock = lines.take(if (ingredientHeader >= 0) ingredientHeader else lines.size)
        val name = pickTitle(titleBlock) ?: fallbackName

        val ingredientRegion = when {
            ingredientHeader >= 0 && methodHeader > ingredientHeader ->
                lines.subList(ingredientHeader + 1, methodHeader)
            ingredientHeader >= 0 -> lines.drop(ingredientHeader + 1)
            methodHeader >= 0 -> lines.take(methodHeader)
            else -> lines
        }
        val ingredients = ingredientRegion
            .filter { keepAsIngredient(it, headerFound = ingredientHeader >= 0, title = name) }
            .distinct()

        val steps = if (methodHeader >= 0) {
            lines.drop(methodHeader + 1)
                .map { STEP_NUMBER.replace(it, "").trim() }
                .filter { it.isNotBlank() && !NOISE.matches(it) && it.length > 2 }
        } else {
            emptyList()
        }

        return ParsedRecipe(
            name = name,
            servings = servings,
            ingredients = ingredients,
            sourceUrl = null,
            steps = steps
        )
    }

    private fun clean(raw: String): String =
        raw.trim()
            .trimStart(*BULLETS.toCharArray())
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun findServings(lines: List<String>): Double? {
        for (line in lines) {
            for (pattern in SERVINGS_PATTERNS) {
                val value = pattern.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                if (value != null && value > 0) return value
            }
        }
        return null
    }

    /**
     * The first line of the title block that reads like a title: has letters, isn't page furniture,
     * isn't an amount, and is short enough to be a name rather than a paragraph of blurb.
     */
    private fun pickTitle(block: List<String>): String? = block.firstOrNull { line ->
        line.length in 2..80 &&
            line.any { it.isLetter() } &&
            !NOISE.matches(line) &&
            !INGREDIENT_HEADER.matches(line) &&
            !METHOD_HEADER.matches(line) &&
            !QUANTITY_LEAD.containsMatchIn(line)
    }

    /**
     * With a header above the list, everything under it is an ingredient unless it's page furniture
     * — that is the whole point of the header. Without one we only trust lines that open with an
     * amount, since we're reading an unlabelled region and a paragraph of method looks the same.
     */
    private fun keepAsIngredient(line: String, headerFound: Boolean, title: String): Boolean {
        if (line.length < 2 || line.none { it.isLetter() }) return false
        if (NOISE.matches(line)) return false
        if (INGREDIENT_HEADER.matches(line) || METHOD_HEADER.matches(line)) return false
        if (line.equals(title, ignoreCase = true)) return false
        return headerFound || QUANTITY_LEAD.containsMatchIn(line)
    }
}
