package com.citation.core.opds

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Parses OPDS **2.0**, the JSON successor to the Atom feeds — what Readium-based servers, Kavita,
 * Komga and newer library platforms emit.
 *
 * It is a different serialisation of the same idea, so it is normalised into the very same
 * [OpdsFeed]: navigation and publications are already separate lists in the JSON, facets are
 * groups of links, and `images` is just another way of saying cover art. Doing the normalisation
 * here means the browser, the download pipeline and the UI never learn there are two protocols.
 *
 * Uses Gson's tree model — the same dependency and the same style as the sync wire codec — and is
 * lenient: a publication missing a title is skipped, a malformed document yields `null`.
 */
object Opds2Parser {

    /** Whether [body] looks like OPDS 2.0 JSON, before trying to parse it. */
    fun looksLikeJson(body: String, contentType: String? = null): Boolean {
        if (contentType?.contains("opds+json", true) == true) return true
        val head = body.trimStart().take(1)
        return head == "{"
    }

    /** Parse [json] fetched from [url], or `null` when it is not an OPDS 2.0 document. */
    fun parse(json: String, url: String): OpdsFeed? {
        val root = try {
            JsonParser.parseString(json) as? JsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        // A document with none of these is some other JSON API, not a catalog.
        if (!root.has("publications") && !root.has("navigation") && !root.has("groups") && !root.has("links")) {
            return null
        }

        val entries = ArrayList<OpdsEntry>()
        entries += publications(root, url)
        entries += navigation(root, url)
        // Groups are presentational ("New this week", "Staff picks"); flatten them so the browser
        // shows one list rather than losing whatever is inside.
        root.array("groups").forEach { group ->
            (group as? JsonObject)?.let {
                entries += publications(it, url)
                entries += navigation(it, url)
            }
        }

        return OpdsFeed(
            title = root.obj("metadata")?.string("title"),
            id = root.obj("metadata")?.string("identifier"),
            links = links(root.array("links"), url) + facetLinks(root, url),
            entries = entries,
            url = url
        )
    }

    private fun publications(node: JsonObject, base: String): List<OpdsEntry> =
        node.array("publications").mapNotNull { element ->
            val pub = element as? JsonObject ?: return@mapNotNull null
            val meta = pub.obj("metadata") ?: return@mapNotNull null
            val title = meta.string("title") ?: return@mapNotNull null
            val series = meta.obj("belongsTo")?.let { belongs ->
                belongs.obj("series") ?: belongs.array("series").firstOrNull() as? JsonObject
            }
            OpdsEntry(
                id = meta.string("identifier"),
                title = title,
                authors = contributors(meta.get("author")),
                summary = meta.string("description"),
                published = meta.string("published") ?: meta.string("modified"),
                updated = meta.string("modified"),
                categories = meta.strings("subject"),
                language = meta.string("language") ?: meta.strings("language").firstOrNull(),
                publisher = contributors(meta.get("publisher")).firstOrNull(),
                identifiers = listOfNotNull(meta.string("identifier")),
                series = series?.string("name"),
                seriesIndex = series?.number("position"),
                links = links(pub.array("links"), base) + images(pub.array("images"), base)
            )
        }

    private fun navigation(node: JsonObject, base: String): List<OpdsEntry> =
        node.array("navigation").mapNotNull { element ->
            val nav = element as? JsonObject ?: return@mapNotNull null
            val title = nav.string("title") ?: return@mapNotNull null
            val href = nav.string("href") ?: return@mapNotNull null
            OpdsEntry(
                id = href,
                title = title,
                // A navigation item carries its target inline rather than in a links array; give it
                // the rel that makes it classify as navigation, so the two protocols look alike.
                links = listOf(
                    OpdsLink(
                        href = OpdsUrl.resolve(base, href),
                        rel = "subsection",
                        type = nav.string("type"),
                        title = title
                    )
                )
            )
        }

    private fun links(array: JsonArray, base: String): List<OpdsLink> =
        array.mapNotNull { element ->
            val link = element as? JsonObject ?: return@mapNotNull null
            val href = link.string("href") ?: return@mapNotNull null
            OpdsLink(
                href = OpdsUrl.resolve(base, href),
                // `rel` is a string or an array of them; the first is the significant one.
                rel = link.string("rel") ?: link.strings("rel").firstOrNull(),
                type = link.string("type"),
                title = link.string("title")
            )
        }

    /** Cover art. The largest is the cover; the smallest, when there are several, is the thumbnail. */
    private fun images(array: JsonArray, base: String): List<OpdsLink> {
        val images = array.mapNotNull { it as? JsonObject }
            .mapNotNull { image ->
                val href = image.string("href") ?: return@mapNotNull null
                Triple(OpdsUrl.resolve(base, href), image.string("type"), image.number("width") ?: 0f)
            }
        if (images.isEmpty()) return emptyList()
        val largest = images.maxByOrNull { it.third }!!
        val smallest = images.minByOrNull { it.third }!!
        val out = ArrayList<OpdsLink>()
        out.add(OpdsLink(href = largest.first, rel = "http://opds-spec.org/image", type = largest.second))
        if (smallest !== largest) {
            out.add(OpdsLink(href = smallest.first, rel = "http://opds-spec.org/image/thumbnail", type = smallest.second))
        }
        return out
    }

    /** Facets, flattened onto links carrying their group name — the shape [OpdsFeed] expects. */
    private fun facetLinks(root: JsonObject, base: String): List<OpdsLink> =
        root.array("facets").flatMap { element ->
            val facet = element as? JsonObject ?: return@flatMap emptyList()
            val group = facet.obj("metadata")?.string("title") ?: "Filter"
            links(facet.array("links"), base).map { it.copy(facetGroup = group) }
        }

    /** A contributor is a string, an object with a name, or an array of either. */
    private fun contributors(element: JsonElement?): List<String> = when {
        element == null || element.isJsonNull -> emptyList()
        element.isJsonPrimitive -> listOf(element.asString)
        element.isJsonObject -> listOfNotNull((element as JsonObject).string("name"))
        element.isJsonArray -> (element as JsonArray).flatMap { contributors(it) }
        else -> emptyList()
    }

    // --- Gson conveniences ------------------------------------------------------------------------

    private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject

    private fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: JsonArray()

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(name: String): Float? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asFloat }.getOrNull() }

    private fun JsonObject.strings(name: String): List<String> = when (val value = get(name)) {
        null -> emptyList()
        is JsonArray -> value.mapNotNull { item ->
            when {
                item.isJsonPrimitive -> item.asString
                item.isJsonObject -> (item as JsonObject).string("name")
                else -> null
            }
        }
        else -> if (value.isJsonPrimitive) listOf(value.asString) else emptyList()
    }
}
