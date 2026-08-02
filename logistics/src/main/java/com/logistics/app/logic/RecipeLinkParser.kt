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
 *  2. **Microdata fallback** (`itemprop="recipeIngredient"` / `recipeYield` / `name`) for the
 *     stragglers.
 *
 * Framework-free (HTML in, [ParsedRecipe] out) so it's unit-tested on the JVM; the Android side only
 * does the network fetch (see [com.logistics.app.net.RecipeFetcher]).
 */
object RecipeLinkParser {

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
        return ParsedRecipe(name = name, servings = servings, ingredients = ingredients, sourceUrl = sourceUrl)
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

        return ParsedRecipe(name, servings, ingredients, sourceUrl)
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
