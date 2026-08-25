package com.citation.core.xml

/**
 * A **nesting-aware** scanner for the small, well-formed XML documents Citation reads: EPUB
 * contents documents and OPDS Atom feeds.
 *
 * It is not a parser and does not pretend to be one. It walks elements one level at a time,
 * tracking depth, which is the single thing a flat regex sweep cannot do — and the thing that
 * matters, because both formats are nested by nature (a part contains chapters; a feed entry
 * contains links). Everything else is deliberately left out: no DOM, no namespace resolution, no
 * validation, no dependency. That keeps `:core` Android-free and JVM-testable, the same bargain
 * the EPUB and Royal Road parsers already make.
 *
 * Namespaces are handled by convention rather than resolution: callers ask for the prefixed name
 * they expect (`dc:creator`), and [localName] lets a caller match either spelling when a feed is
 * inconsistent about prefixes — which real ones are.
 */
internal object Xml {

    /** One element: its opening tag verbatim, and everything between it and its close. */
    class Element(val open: String, val inner: String) {
        /** The element's text content, tags stripped and entities decoded. */
        val text: String get() = Text.of(inner)

        fun attr(name: String): String? = Xml.attr(open, name)
    }

    /**
     * The elements named [tag] that sit at the **top level** of [xml]; nested ones stay inside
     * their parent's [Element.inner] for the caller to recurse into.
     *
     * [tag] may be a prefixed name (`dc:title`) or a bare local name, in which case elements
     * carrying any prefix match too — feeds disagree about whether Dublin Core is `dc:` or `dcterms:`
     * and about whether the default namespace is declared at all.
     */
    fun elements(xml: String, tag: String, anyPrefix: Boolean = false): List<Element> {
        val name = if (anyPrefix) "(?:[A-Za-z0-9_.-]+:)?${Regex.escape(tag)}" else Regex.escape(tag)
        val pattern = Regex("(?is)<(/?)$name(\\s[^>]*)?(/?)>")
        val out = ArrayList<Element>()
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

    /** The first element named [tag] at the top level of [xml], or `null`. */
    fun element(xml: String, tag: String, anyPrefix: Boolean = false): Element? =
        elements(xml, tag, anyPrefix).firstOrNull()

    /** Text of the first [tag] element, or `null` when absent or empty. */
    fun text(xml: String, tag: String, anyPrefix: Boolean = false): String? =
        element(xml, tag, anyPrefix)?.text?.takeIf { it.isNotBlank() }

    /** Text of every [tag] element, in document order, blanks dropped. */
    fun texts(xml: String, tag: String, anyPrefix: Boolean = false): List<String> =
        elements(xml, tag, anyPrefix).map { it.text }.filter { it.isNotBlank() }

    /** One attribute off a raw opening tag, or `null` when absent. Values are entity-decoded. */
    fun attr(tag: String, name: String): String? =
        Regex("(?i)[\\s:\"']${Regex.escape(name)}\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
            .find(tag)
            ?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } } }
            ?.let { Text.unescape(it) }
            ?.takeIf { it.isNotEmpty() }

    /** The part of a possibly-prefixed name after the colon: `dc:creator` to `creator`. */
    fun localName(name: String): String = name.substringAfterLast(':')
}
