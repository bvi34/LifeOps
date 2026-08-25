package com.citation.core.epub

import com.citation.core.model.TocEntry

/**
 * Nesting-aware scanning of the two contents formats EPUB has shipped.
 *
 * Both are nested by nature — a chapter contains its sections — and a flat regex sweep cannot see
 * that, which is exactly why the reader used to show a flat spine. These helpers walk elements at
 * one level at a time, tracking depth, so `Part II › Chapter 7 › "Consistent Hashing"` survives.
 */
internal object XmlNest {

    /** One element found at the current level: its opening tag, and everything inside it. */
    class Element(val open: String, val inner: String)

    /**
     * The elements named [tag] that sit at the **top level** of [xml] — nested ones are left inside
     * their parent's [Element.inner] for the caller to recurse into.
     */
    fun elements(xml: String, tag: String): List<Element> {
        val out = ArrayList<Element>()
        val pattern = Regex("(?is)<(/?)${Regex.escape(tag)}(\\s[^>]*)?(/?)>")
        var depth = 0
        var openTag = ""
        var contentStart = 0
        for (m in pattern.findAll(xml)) {
            val closing = m.groupValues[1] == "/"
            // `[^>]*` swallows a trailing slash, so read self-closing off the raw match.
            val selfClosing = m.value.endsWith("/>")
            when {
                selfClosing && !closing -> if (depth == 0) out.add(Element(m.value, ""))
                !closing -> {
                    if (depth == 0) {
                        openTag = m.value
                        contentStart = m.range.last + 1
                    }
                    depth++
                }
                else -> {
                    depth--
                    if (depth == 0) out.add(Element(openTag, xml.substring(contentStart, m.range.first)))
                    if (depth < 0) depth = 0
                }
            }
        }
        return out
    }

    fun attr(tag: String, name: String): String? =
        Regex("(?i)\\b${Regex.escape(name)}\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1)
}

/**
 * EPUB 3's navigation document: an XHTML file holding `<nav epub:type="toc">` with nested
 * `<ol>/<li>/<a>`. Preferred over the legacy NCX because it is the current standard and publishers
 * keep it richer (it is also what carries landmarks and page lists).
 */
internal object NavDocument {

    /** Parse [xml]; [resolve] turns an href into `(chapterOrdinal, fragment)`. */
    fun parse(xml: String, resolve: (String) -> Pair<Int?, String?>): List<TocEntry> {
        val navs = XmlNest.elements(xml, "nav")
        val toc = navs.firstOrNull { XmlNest.attr(it.open, "epub:type")?.contains("toc", true) == true }
            ?: navs.firstOrNull { XmlNest.attr(it.open, "type")?.contains("toc", true) == true }
            ?: navs.firstOrNull()
            ?: return emptyList()
        return list(toc.inner, resolve)
    }

    private fun list(xml: String, resolve: (String) -> Pair<Int?, String?>): List<TocEntry> {
        val ol = XmlNest.elements(xml, "ol").firstOrNull() ?: return emptyList()
        return XmlNest.elements(ol.inner, "li").mapNotNull { item -> entry(item.inner, resolve) }
    }

    private fun entry(li: String, resolve: (String) -> Pair<Int?, String?>): TocEntry? {
        val link = XmlNest.elements(li, "a").firstOrNull()
        val label = (link?.inner ?: XmlNest.elements(li, "span").firstOrNull()?.inner)
            ?.let { Html.toText(it) }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val href = link?.let { XmlNest.attr(it.open, "href") }
        val (ordinal, fragment) = href?.let(resolve) ?: (null to null)
        return TocEntry(
            title = label,
            chapterOrdinal = ordinal,
            fragment = fragment,
            children = list(li, resolve)
        )
    }
}

/**
 * EPUB 2's `toc.ncx`: a `<navMap>` of nested `<navPoint>`s. Still the only contents document in a
 * large share of real libraries — anything produced by calibre, AO3, or any pre-2011 publisher —
 * so it is a first-class path, not a fallback that half works.
 */
internal object NcxDocument {

    fun parse(xml: String, resolve: (String) -> Pair<Int?, String?>): List<TocEntry> {
        val map = XmlNest.elements(xml, "navMap").firstOrNull() ?: return emptyList()
        return points(map.inner, resolve)
    }

    private fun points(xml: String, resolve: (String) -> Pair<Int?, String?>): List<TocEntry> =
        XmlNest.elements(xml, "navPoint").mapNotNull { point ->
            val label = XmlNest.elements(point.inner, "navLabel").firstOrNull()
                ?.let { XmlNest.elements(it.inner, "text").firstOrNull()?.inner }
                ?.let { Html.toText(it) }
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val src = XmlNest.elements(point.inner, "content").firstOrNull()
                ?.let { XmlNest.attr(it.open, "src") }
            val (ordinal, fragment) = src?.let(resolve) ?: (null to null)
            TocEntry(
                title = label,
                chapterOrdinal = ordinal,
                fragment = fragment,
                children = points(point.inner, resolve)
            )
        }
}
