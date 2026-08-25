package com.citation.core.epub

import com.citation.core.model.TocEntry
import com.citation.core.xml.Xml

/**
 * EPUB 3's navigation document: an XHTML file holding `<nav epub:type="toc">` with nested
 * `<ol>/<li>/<a>`. Preferred over the legacy NCX because it is the current standard and publishers
 * keep it richer (it is also what carries landmarks and page lists).
 */
internal object NavDocument {

    /** Parse [xml]; [resolve] turns an href into `(chapterOrdinal, fragment)`. */
    fun parse(xml: String, resolve: (String) -> Pair<Int?, String?>): List<TocEntry> {
        val navs = Xml.elements(xml, "nav")
        val toc = navs.firstOrNull { Xml.attr(it.open, "epub:type")?.contains("toc", true) == true }
            ?: navs.firstOrNull { Xml.attr(it.open, "type")?.contains("toc", true) == true }
            ?: navs.firstOrNull()
            ?: return emptyList()
        return list(toc.inner, resolve)
    }

    private fun list(xml: String, resolve: (String) -> Pair<Int?, String?>): List<TocEntry> {
        val ol = Xml.elements(xml, "ol").firstOrNull() ?: return emptyList()
        return Xml.elements(ol.inner, "li").mapNotNull { item -> entry(item.inner, resolve) }
    }

    private fun entry(li: String, resolve: (String) -> Pair<Int?, String?>): TocEntry? {
        val link = Xml.elements(li, "a").firstOrNull()
        val label = (link?.inner ?: Xml.elements(li, "span").firstOrNull()?.inner)
            ?.let { Html.toText(it) }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val href = link?.let { Xml.attr(it.open, "href") }
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
        val map = Xml.elements(xml, "navMap").firstOrNull() ?: return emptyList()
        return points(map.inner, resolve)
    }

    private fun points(xml: String, resolve: (String) -> Pair<Int?, String?>): List<TocEntry> =
        Xml.elements(xml, "navPoint").mapNotNull { point ->
            val label = Xml.elements(point.inner, "navLabel").firstOrNull()
                ?.let { Xml.elements(it.inner, "text").firstOrNull()?.inner }
                ?.let { Html.toText(it) }
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val src = Xml.elements(point.inner, "content").firstOrNull()
                ?.let { Xml.attr(it.open, "src") }
            val (ordinal, fragment) = src?.let(resolve) ?: (null to null)
            TocEntry(
                title = label,
                chapterOrdinal = ordinal,
                fragment = fragment,
                children = points(point.inner, resolve)
            )
        }
}
