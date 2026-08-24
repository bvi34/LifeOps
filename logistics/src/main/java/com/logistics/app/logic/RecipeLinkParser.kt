package com.logistics.app.logic

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.logistics.app.data.model.ParsedRecipe

/**
 * Extracts a recipe from a fetched web page's HTML, the same way "recipe grabber" apps do: it reads
 * the page's structured data. Preference order:
 *
 *  1. **schema.org/Recipe JSON-LD** (`<script type="application/ld+json">`) — what almost every
 *     modern recipe site emits, including inside an `@graph`.
 *  2. **Microdata fallback** (`itemprop="recipeIngredient"` / `recipeYield` / `recipeInstructions`
 *     / `name`) for the stragglers.
 *
 * Both paths pull the **method** as well as the ingredients: a recipe you can't cook from is just a
 * shopping list, so the steps ride into LifeOps' recipe book alongside the source link.
 *
 * Framework-free (HTML in, [ParsedRecipe] out) so it's unit-tested on the JVM; the Android side only
 * does the network fetch (see [com.logistics.app.net.RecipeFetcher]).
 */
object RecipeLinkParser {

    /** Step separators inside a single instructions blob: block-level tags or a bare newline. */
    private val STEP_SPLIT = Regex("(?i)</(?:p|li|div)>|<br\\s*/?>|\\r?\\n")
    private val WHITESPACE = Regex("\\s+")

    private val LD_JSON = Regex(
        """<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun parse(html: String, sourceUrl: String? = null): ParsedRecipe? {
        parseJsonLd(html, sourceUrl)?.let { return it }
        return parseMicrodata(html, sourceUrl)
    }

    // --- JSON-LD ---

    private fun parseJsonLd(html: String, sourceUrl: String?): ParsedRecipe? {
        for (match in LD_JSON.findAll(html)) {
            val json = match.groupValues[1].trim()
            val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: continue
            val recipeNode = findRecipeNode(root) ?: continue
            val recipe = recipeFromNode(recipeNode, sourceUrl)
            if (recipe != null && recipe.ingredients.isNotEmpty()) return recipe
        }
        return null
    }

    /** Depth-first search for a JSON object whose @type is (or includes) "Recipe". Handles a top
     *  object, an array of objects, and the common `{"@graph":[…]}` wrapper. */
    private fun findRecipeNode(element: JsonElement): JsonObject? {
        when {
            element.isJsonArray -> {
                for (e in element.asJsonArray) findRecipeNode(e)?.let { return it }
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                if (isRecipeType(obj.get("@type"))) return obj
                obj.get("@graph")?.let { graph -> findRecipeNode(graph)?.let { return it } }
            }
        }
        return null
    }

    private fun isRecipeType(type: JsonElement?): Boolean {
        if (type == null) return false
        if (type.isJsonPrimitive) return type.asString.equals("Recipe", ignoreCase = true)
        if (type.isJsonArray) return type.asJsonArray.any { it.isJsonPrimitive && it.asString.equals("Recipe", ignoreCase = true) }
        return false
    }

    private fun recipeFromNode(obj: JsonObject, sourceUrl: String?): ParsedRecipe? {
        val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.let { decodeEntities(it).trim() }
            ?: return null
        val ingredients = stringList(obj.get("recipeIngredient") ?: obj.get("ingredients"))
            .map { decodeEntities(it).trim() }
            .filter { it.isNotBlank() }
        val servings = parseYield(obj.get("recipeYield"))
        val steps = parseInstructions(obj.get("recipeInstructions"))
        return ParsedRecipe(
            name = name,
            servings = servings,
            ingredients = ingredients,
            sourceUrl = sourceUrl,
            steps = steps
        )
    }

    /**
     * `recipeInstructions` is the least standardised field in schema.org/Recipe. In the wild it is
     * one of: a single string (often with HTML in it), an array of strings, an array of `HowToStep`
     * objects, or an array of `HowToSection`s each wrapping its own steps. All four collapse to the
     * same thing here — a flat list of plain-text steps.
     */
    private fun parseInstructions(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        val out = ArrayList<String>()
        collectInstructions(element, out, depth = 0)
        return out
    }

    private fun collectInstructions(element: JsonElement, out: MutableList<String>, depth: Int) {
        // Sections nest steps one level down; the depth cap only stops a pathological document
        // from recursing — it is far deeper than any real page needs.
        if (depth > 4) return
        when {
            element.isJsonArray -> for (e in element.asJsonArray) collectInstructions(e, out, depth + 1)
            element.isJsonObject -> {
                val obj = element.asJsonObject
                // A HowToSection carries its steps in itemListElement; a HowToStep carries text.
                val nested = obj.get("itemListElement")
                if (nested != null) {
                    collectInstructions(nested, out, depth + 1)
                    return
                }
                val text = obj.get("text") ?: obj.get("name") ?: return
                if (text.isJsonPrimitive) addStep(text.asString, out)
            }
            element.isJsonPrimitive -> {
                // One string holding the whole method: sites separate steps with markup or plain
                // newlines, so split on both and keep whatever survives.
                val parts = element.asString.split(STEP_SPLIT)
                if (parts.size > 1) parts.forEach { addStep(it, out) } else addStep(element.asString, out)
            }
        }
    }

    private fun addStep(raw: String, out: MutableList<String>) {
        val text = decodeEntities(stripTags(raw)).replace(WHITESPACE, " ").trim()
        if (text.isNotBlank()) out += text
    }

    private fun stringList(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        return when {
            element.isJsonArray -> element.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            element.isJsonPrimitive -> listOf(element.asString)
            else -> emptyList()
        }
    }

    /** recipeYield can be a number, "4", "4 servings", "Serves 6", or an array. Pull the first int. */
    private fun parseYield(element: JsonElement?): Double? {
        if (element == null) return null
        val raw = when {
            element.isJsonArray -> element.asJsonArray.firstOrNull()?.let { if (it.isJsonPrimitive) it.asString else null }
            element.isJsonPrimitive -> element.asString
            else -> null
        } ?: return null
        return Regex("""\d+""").find(raw)?.value?.toDoubleOrNull()
    }

    // --- Microdata fallback ---

    private fun parseMicrodata(html: String, sourceUrl: String?): ParsedRecipe? {
        val ingredients = Regex(
            """itemprop\s*=\s*["']recipeIngredient["'][^>]*>(.*?)<""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html)
            .map { decodeEntities(stripTags(it.groupValues[1])).trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (ingredients.isEmpty()) return null

        val name = Regex(
            """itemprop\s*=\s*["']name["'][^>]*>(.*?)<""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.let { decodeEntities(stripTags(it.groupValues[1])).trim() }
            ?: titleOf(html)
            ?: "Imported recipe"

        val servings = Regex(
            """itemprop\s*=\s*["']recipeYield["'][^>]*>(.*?)<""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.let { Regex("""\d+""").find(stripTags(it.groupValues[1]))?.value?.toDoubleOrNull() }

        val steps = Regex(
            """itemprop\s*=\s*["']recipeInstructions["'][^>]*>(.*?)</""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html)
            .map { decodeEntities(stripTags(it.groupValues[1])).replace(WHITESPACE, " ").trim() }
            .filter { it.isNotBlank() }
            .toList()

        return ParsedRecipe(name, servings, ingredients, sourceUrl, steps)
    }

    private fun titleOf(html: String): String? =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.let { decodeEntities(it.groupValues[1]).trim() }

    private fun stripTags(s: String): String = s.replace(Regex("""<[^>]+>"""), " ")

    private fun decodeEntities(s: String): String {
        var out = s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        // Numeric entities like &#233; and &#x00e9;
        out = Regex("""&#(\d+);""").replace(out) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }
        out = Regex("""&#x([0-9a-fA-F]+);""").replace(out) { m -> m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value }
        return out
    }
}
