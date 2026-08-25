package com.citation.core.epub

import com.citation.core.doc.DocumentBlock
import com.citation.core.doc.HtmlDocument
import com.citation.core.doc.InlineSpan
import com.citation.core.identity.IdentityKey
import com.citation.core.identity.IdentitySet
import com.citation.core.model.Book
import com.citation.core.model.BookMetadata
import com.citation.core.model.Chapter
import com.citation.core.model.SourceType
import com.citation.core.model.TableOfContents
import com.citation.core.model.TocEntry
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Parses an EPUB into Citation's [Book] internal model.
 *
 * EPUB is the walking skeleton's chosen first producer because it is already *almost* the internal
 * model: a zip of XHTML chapters plus an OPF manifest that gives title/author, an ISBN, and the
 * reading order (the spine). Parsing is therefore mostly plumbing — read the zip, follow
 * `container.xml` to the OPF, resolve the spine to chapter files, and reduce each through
 * [HtmlDocument]. No third-party EPUB library; `java.util.zip` + small regexes keep it
 * dependency-free and JVM-testable, matching how LifeOps parses NWS JSON by hand.
 *
 * Beyond the text it recovers the things that make a book *look* like a book rather than a wall of
 * characters: the publisher's nested table of contents (EPUB 3 `nav`, falling back to EPUB 2
 * `toc.ncx`), the cover, every illustration, and the shelf metadata — series, subjects, publisher,
 * date, blurb.
 *
 * The parser is deliberately lenient throughout: a missing OPF, an unreadable entry, an odd spine,
 * a contents document that doesn't parse — each degrades to whatever could be recovered rather than
 * throwing. "Degrade, don't crash".
 */
object EpubParser {

    /**
     * Result of a parse.
     *
     * @property book the normalized model, chapters carrying their structure.
     * @property identity identity evidence recovered (ISBN, if any).
     * @property resources binary assets referenced by the content — images, keyed by zip path — for
     *   the storage layer to write alongside the book. Held separately from [book] because a value
     *   object describing a work has no business carrying megabytes of JPEG.
     * @property coverPath zip path of the cover image, when one is declared or can be inferred.
     */
    data class ParsedEpub(
        val book: Book,
        val identity: IdentitySet,
        val resources: Map<String, ByteArray> = emptyMap(),
        val coverPath: String? = null
    )

    /**
     * Parse [bytes] (a whole `.epub` file) into a [Book]. Returns `null` only when the archive is
     * unreadable as a zip or yields no content documents at all.
     */
    fun parse(bytes: ByteArray): ParsedEpub? {
        val entries = readZip(bytes) ?: return null

        val opfPath = findOpfPath(entries)
        val opfXml = opfPath?.let { entries[it]?.toString(Charsets.UTF_8) }
        val opfDir = opfPath?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""

        val meta = opfXml?.let { parseMetadata(it) }
        val manifest = opfXml?.let { manifestItems(it, opfDir) } ?: emptyMap()
        val spineIds = opfXml?.let { spineIdRefs(it) } ?: emptyList()

        // Resolve spine → chapters; fall back to *all* xhtml entries if the spine is unusable.
        val orderedHrefs = spineIds
            .mapNotNull { manifest[it]?.path }
            .filter { entries.containsKey(it) }
            .ifEmpty { entries.keys.filter { it.isContentDocument() }.sorted() }

        val chapters = orderedHrefs.mapIndexedNotNull { index, href ->
            val html = entries[href]?.toString(Charsets.UTF_8) ?: return@mapIndexedNotNull null
            val parsed = HtmlDocument.parse(html)
            if (parsed.text.isBlank()) return@mapIndexedNotNull null
            Chapter(
                ordinal = index,
                title = parsed.heading ?: Html.extractHeading(html) ?: "Chapter ${index + 1}",
                sourceRef = href,
                text = parsed.text,
                html = html,
                // Resolve every href the chapter carries against its own location, so the reader
                // never has to know where in the zip a chapter happened to live.
                blocks = parsed.blocks.map { resolveRefs(it, href) },
                anchors = parsed.anchors
            )
        }.reindex()

        if (chapters.isEmpty()) return null

        val coverPath = coverPath(opfXml, manifest, entries, chapters)
        val toc = tableOfContents(entries, manifest, opfXml, chapters)

        val metadata = BookMetadata(
            title = meta?.title ?: "Untitled",
            author = meta?.author,
            source = SourceType.EPUB,
            language = meta?.language,
            publisher = meta?.publisher,
            published = meta?.published,
            description = meta?.description,
            subjects = meta?.subjects.orEmpty(),
            series = meta?.series,
            seriesIndex = meta?.seriesIndex,
            coverRef = coverPath
        )
        val identity = meta?.isbn
            ?.let { IdentitySet(IdentityKey.Isbn(it)) }
            ?: IdentitySet(emptyList())

        return ParsedEpub(
            book = Book(key = null, metadata = metadata, chapters = chapters, toc = toc),
            identity = identity,
            resources = imageResources(entries, manifest, coverPath),
            coverPath = coverPath
        )
    }

    // --- OPF metadata --------------------------------------------------------------------------

    private data class OpfMeta(
        val title: String?,
        val author: String?,
        val language: String?,
        val isbn: String?,
        val publisher: String?,
        val published: String?,
        val description: String?,
        val subjects: List<String>,
        val series: String?,
        val seriesIndex: Float?
    )

    private fun parseMetadata(opf: String): OpfMeta {
        val title = tagText(opf, "dc:title") ?: tagText(opf, "title")
        val author = tagText(opf, "dc:creator") ?: tagText(opf, "creator")
        val language = tagText(opf, "dc:language") ?: tagText(opf, "language")
        val isbn = identifiers(opf).firstOrNull { looksLikeIsbn(it) }

        // Series is stated two incompatible ways in the wild: calibre's `<meta name=… content=…>`
        // (EPUB 2 style, overwhelmingly the most common) and EPUB 3's `belongs-to-collection`.
        val series = metaContent(opf, "calibre:series") ?: collectionName(opf)
        val seriesIndex = (metaContent(opf, "calibre:series_index") ?: collectionIndex(opf))
            ?.trim()?.toFloatOrNull()

        return OpfMeta(
            title = title,
            author = author,
            language = language,
            isbn = isbn,
            publisher = tagText(opf, "dc:publisher") ?: tagText(opf, "publisher"),
            published = tagText(opf, "dc:date") ?: tagText(opf, "date"),
            description = tagText(opf, "dc:description") ?: tagText(opf, "description"),
            subjects = allTagText(opf, "dc:subject").ifEmpty { allTagText(opf, "subject") },
            series = series,
            seriesIndex = seriesIndex
        )
    }

    private fun identifiers(opf: String): List<String> =
        Regex("(?is)<dc:identifier[^>]*>(.*?)</dc:identifier>")
            .findAll(opf)
            .map { it.groupValues[1].trim() }
            .map { it.substringAfterLast(':').removePrefix("urn:isbn:").trim() }
            .toList()

    private fun looksLikeIsbn(raw: String): Boolean {
        val digits = raw.filter { it.isLetterOrDigit() }
        return (digits.length == 10 || digits.length == 13) && digits.dropLast(1).all { it.isDigit() }
    }

    private fun tagText(xml: String, tag: String): String? =
        allTagText(xml, tag).firstOrNull()

    private fun allTagText(xml: String, tag: String): List<String> =
        Regex("(?is)<${Regex.escape(tag)}(\\s[^>]*)?>(.*?)</${Regex.escape(tag)}>")
            .findAll(xml)
            .map { Html.toText(it.groupValues[2]) }
            .filter { it.isNotBlank() }
            .toList()

    private fun metaContent(opf: String, name: String): String? =
        Regex("(?is)<meta\\b[^>]*\\bname\\s*=\\s*[\"']${Regex.escape(name)}[\"'][^>]*>")
            .find(opf)?.value?.let { attr(it, "content") }
            ?.takeIf { it.isNotBlank() }

    /** EPUB 3 `<meta property="belongs-to-collection">`, the standard-track series statement. */
    private fun collectionName(opf: String): String? =
        Regex("(?is)<meta\\b[^>]*property\\s*=\\s*[\"']belongs-to-collection[\"'][^>]*>(.*?)</meta>")
            .find(opf)?.groupValues?.get(1)?.let { Html.toText(it) }?.takeIf { it.isNotBlank() }

    private fun collectionIndex(opf: String): String? =
        Regex("(?is)<meta\\b[^>]*property\\s*=\\s*[\"']group-position[\"'][^>]*>(.*?)</meta>")
            .find(opf)?.groupValues?.get(1)?.let { Html.toText(it) }?.takeIf { it.isNotBlank() }

    private fun attr(tag: String, name: String): String? =
        Regex("(?i)\\b${Regex.escape(name)}\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1)

    // --- Manifest + spine ----------------------------------------------------------------------

    /** One `<item>` from the OPF manifest, with its href already resolved to a zip path. */
    private class Item(val id: String, val path: String, val mediaType: String, val properties: String)

    private fun manifestItems(opf: String, opfDir: String): Map<String, Item> =
        Regex("(?is)<item\\b[^>]*>")
            .findAll(opf)
            .mapNotNull { m ->
                val tag = m.value
                val id = attr(tag, "id") ?: return@mapNotNull null
                val href = attr(tag, "href") ?: return@mapNotNull null
                // An href may be percent-encoded; zip entry names are not.
                id to Item(
                    id = id,
                    path = normalizePath(opfDir + decodePath(href)),
                    mediaType = attr(tag, "media-type").orEmpty().lowercase(),
                    properties = attr(tag, "properties").orEmpty().lowercase()
                )
            }
            .toMap()

    private fun spineIdRefs(opf: String): List<String> =
        Regex("(?is)<itemref\\b[^>]*>")
            .findAll(opf)
            .mapNotNull { attr(it.value, "idref") }
            .toList()

    // --- Cover ---------------------------------------------------------------------------------

    /**
     * The cover, by descending confidence: the EPUB 3 `cover-image` property, the EPUB 2
     * `<meta name="cover">` pointer, the first image inside the first spine document (the
     * near-universal shape of a cover page), then any manifest image named like a cover.
     */
    private fun coverPath(
        opf: String?,
        manifest: Map<String, Item>,
        entries: Map<String, ByteArray>,
        chapters: List<Chapter>
    ): String? {
        manifest.values.firstOrNull { it.properties.contains("cover-image") }
            ?.let { if (entries.containsKey(it.path)) return it.path }

        opf?.let { metaContent(it, "cover") }
            ?.let { manifest[it] }
            ?.let { if (entries.containsKey(it.path)) return it.path }

        chapters.firstOrNull()?.blocks
            ?.filterIsInstance<DocumentBlock.Image>()
            ?.firstOrNull { entries.containsKey(it.src) }
            ?.let { return it.src }

        return manifest.values
            .firstOrNull {
                it.mediaType.startsWith("image/") &&
                    it.path.substringAfterLast('/').lowercase().contains("cover") &&
                    entries.containsKey(it.path)
            }
            ?.path
    }

    /** Every image the book actually holds, keyed by zip path, plus the cover. */
    private fun imageResources(
        entries: Map<String, ByteArray>,
        manifest: Map<String, Item>,
        coverPath: String?
    ): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        manifest.values
            .filter { it.mediaType.startsWith("image/") }
            .forEach { item -> entries[item.path]?.let { out[item.path] = it } }
        // A manifest that forgot to declare its images still has them in the zip.
        entries.forEach { (path, data) ->
            if (path !in out && path.looksLikeImage()) out[path] = data
        }
        coverPath?.let { path -> entries[path]?.let { out[path] = it } }
        return out
    }

    // --- Table of contents ---------------------------------------------------------------------

    /**
     * The publisher's contents: EPUB 3's `nav` document first (it is richer and the current
     * standard), then EPUB 2's `toc.ncx`. An empty result is fine — the reader falls back to the
     * chapter list, which is exactly what it always showed.
     */
    private fun tableOfContents(
        entries: Map<String, ByteArray>,
        manifest: Map<String, Item>,
        opf: String?,
        chapters: List<Chapter>
    ): TableOfContents {
        val ordinalOf = chapters.associate { it.sourceRef to it.ordinal }

        val navItem = manifest.values.firstOrNull { it.properties.contains("nav") }
        navItem?.let { item ->
            entries[item.path]?.toString(Charsets.UTF_8)?.let { xml ->
                val parsed = NavDocument.parse(xml) { href -> resolveTarget(href, item.path, ordinalOf) }
                if (parsed.isNotEmpty()) return TableOfContents(parsed)
            }
        }

        val ncxPath = opf?.let { xml ->
            Regex("(?is)<spine\\b[^>]*>").find(xml)?.value?.let { attr(it, "toc") }?.let { manifest[it]?.path }
        } ?: manifest.values.firstOrNull { it.mediaType.contains("ncx") }?.path
            ?: entries.keys.firstOrNull { it.endsWith(".ncx", ignoreCase = true) }

        ncxPath?.let { path ->
            entries[path]?.toString(Charsets.UTF_8)?.let { xml ->
                val parsed = NcxDocument.parse(xml) { href -> resolveTarget(href, path, ordinalOf) }
                if (parsed.isNotEmpty()) return TableOfContents(parsed)
            }
        }

        return TableOfContents.EMPTY
    }

    /** Turn a contents href, relative to the document that stated it, into chapter + fragment. */
    private fun resolveTarget(
        href: String,
        fromPath: String,
        ordinalOf: Map<String, Int>
    ): Pair<Int?, String?> {
        val fragment = href.substringAfter('#', "").takeIf { it.isNotBlank() }
        val file = href.substringBefore('#')
        if (file.isBlank()) return null to fragment
        val resolved = normalizePath(fromPath.substringBeforeLast('/', "").let {
            if (it.isEmpty()) decodePath(file) else "$it/${decodePath(file)}"
        })
        return ordinalOf[resolved] to fragment
    }

    // --- Reference resolution --------------------------------------------------------------------

    /**
     * Rewrite a block's source references from chapter-relative to zip-absolute. An image `src` of
     * `../images/plate.jpg` inside `OEBPS/text/ch3.xhtml` becomes `OEBPS/images/plate.jpg`, which is
     * the key the storage layer files it under; a link to `notes.xhtml#fn4` likewise becomes a path
     * the reader can look up, while a bare `#fn4` stays local to this chapter.
     */
    private fun resolveRefs(block: DocumentBlock, chapterPath: String): DocumentBlock = when (block) {
        is DocumentBlock.Image -> block.copy(src = resolveHref(block.src, chapterPath))
        is DocumentBlock.Text ->
            if (block.spans.none { it.href != null }) block
            else block.copy(spans = block.spans.map { it.resolvedAgainst(chapterPath) })
        else -> block
    }

    private fun InlineSpan.resolvedAgainst(chapterPath: String): InlineSpan {
        val target = href ?: return this
        if (target.startsWith("#") || target.isExternal()) return this
        return copy(href = resolveHref(target, chapterPath))
    }

    private fun String.isExternal(): Boolean =
        Regex("(?i)^[a-z][a-z0-9+.-]*:").containsMatchIn(this)

    private fun resolveHref(href: String, chapterPath: String): String {
        if (href.startsWith("#") || href.isExternal()) return href
        val fragment = href.substringAfter('#', "").takeIf { it.isNotBlank() }
        val file = decodePath(href.substringBefore('#'))
        val dir = chapterPath.substringBeforeLast('/', "")
        val resolved = normalizePath(if (dir.isEmpty()) file else "$dir/$file")
        return if (fragment == null) resolved else "$resolved#$fragment"
    }

    // --- Zip + path helpers --------------------------------------------------------------------

    private fun findOpfPath(entries: Map<String, ByteArray>): String? {
        val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
        val fromContainer = container?.let {
            Regex("(?i)full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(it)?.groupValues?.get(1)
        }
        if (fromContainer != null && entries.containsKey(fromContainer)) return fromContainer
        // Fallback: first *.opf anywhere in the archive.
        return entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray>? {
        return try {
            val out = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) out[entry.name] = zis.readBytes()
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            out.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun String.isContentDocument(): Boolean =
        endsWith(".xhtml", true) || endsWith(".html", true) || endsWith(".htm", true)

    private fun String.looksLikeImage(): Boolean =
        listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg").any { endsWith(it, true) }

    /** Percent-decode an href so it matches the zip's plain entry names. */
    private fun decodePath(href: String): String {
        if (!href.contains('%')) return href
        return try {
            java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            href
        }
    }

    /** Collapse `a/b/../c` and `./` segments so OPF-relative hrefs match zip entry names. */
    private fun normalizePath(path: String): String {
        val stack = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(seg)
            }
        }
        return stack.joinToString("/")
    }

    /** Renumber chapter ordinals to match final list position after filtering. */
    private fun List<Chapter>.reindex(): List<Chapter> =
        mapIndexed { i, c -> if (c.ordinal == i) c else c.copy(ordinal = i) }
}
