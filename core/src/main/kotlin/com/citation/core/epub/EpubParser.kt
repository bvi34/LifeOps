package com.citation.core.epub

import com.citation.core.identity.IdentityKey
import com.citation.core.identity.IdentitySet
import com.citation.core.model.Book
import com.citation.core.model.BookMetadata
import com.citation.core.model.Chapter
import com.citation.core.model.SourceType
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Parses an EPUB into Citation's [Book] internal model.
 *
 * EPUB is the walking skeleton's chosen first producer because it is already *almost* the internal
 * model: a zip of XHTML chapters plus an OPF manifest that gives title/author, an ISBN, and the
 * reading order (the spine). Parsing is therefore mostly plumbing — read the zip, follow
 * `container.xml` to the OPF, resolve the spine to chapter files, and reduce each to flowing text
 * via [Html]. No third-party EPUB library; `java.util.zip` + small regexes keep it dependency-free
 * and JVM-testable, matching how LifeOps parses NWS JSON by hand.
 *
 * The parser is deliberately lenient: a missing OPF, an unreadable entry, or an odd spine degrades
 * to whatever chapters it could recover rather than throwing — "degrade, don't crash".
 */
object EpubParser {

    /** Result of a parse: the [Book] plus the identity evidence recovered (ISBN, if any). */
    data class ParsedEpub(val book: Book, val identity: IdentitySet)

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
        val spineHrefs = opfXml?.let { spineHrefs(it) } ?: emptyList()

        // Resolve spine → chapters; fall back to *all* xhtml entries if the spine is unusable.
        val orderedHrefs = spineHrefs
            .map { normalizePath(opfDir + it) }
            .filter { entries.containsKey(it) }
            .ifEmpty { entries.keys.filter { it.isContentDocument() }.sorted() }

        val chapters = orderedHrefs.mapIndexedNotNull { index, href ->
            val html = entries[href]?.toString(Charsets.UTF_8) ?: return@mapIndexedNotNull null
            val text = Html.toText(html)
            if (text.isBlank()) return@mapIndexedNotNull null
            Chapter(
                ordinal = index,
                title = Html.extractHeading(html) ?: "Chapter ${index + 1}",
                sourceRef = href,
                text = text,
                html = html
            )
        }.reindex()

        if (chapters.isEmpty()) return null

        val metadata = BookMetadata(
            title = meta?.title ?: "Untitled",
            author = meta?.author,
            source = SourceType.EPUB,
            language = meta?.language
        )
        val identity = meta?.isbn
            ?.let { IdentitySet(IdentityKey.Isbn(it)) }
            ?: IdentitySet(emptyList())

        return ParsedEpub(Book(key = null, metadata = metadata, chapters = chapters), identity)
    }

    // --- OPF metadata --------------------------------------------------------------------------

    private data class OpfMeta(
        val title: String?,
        val author: String?,
        val language: String?,
        val isbn: String?
    )

    private fun parseMetadata(opf: String): OpfMeta {
        val title = tagText(opf, "dc:title") ?: tagText(opf, "title")
        val author = tagText(opf, "dc:creator") ?: tagText(opf, "creator")
        val language = tagText(opf, "dc:language") ?: tagText(opf, "language")
        val isbn = identifiers(opf).firstOrNull { looksLikeIsbn(it) }
        return OpfMeta(title, author, language, isbn)
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
        Regex("(?is)<${Regex.escape(tag)}[^>]*>(.*?)</${Regex.escape(tag)}>")
            .find(xml)?.groupValues?.get(1)
            ?.let { Html.toText(it) }
            ?.takeIf { it.isNotBlank() }

    // --- Spine ---------------------------------------------------------------------------------

    private fun spineHrefs(opf: String): List<String> {
        val idToHref = Regex("(?is)<item\\b[^>]*>")
            .findAll(opf)
            .mapNotNull { m ->
                val tag = m.value
                val id = attr(tag, "id") ?: return@mapNotNull null
                val href = attr(tag, "href") ?: return@mapNotNull null
                id to href
            }.toMap()

        return Regex("(?is)<itemref\\b[^>]*>")
            .findAll(opf)
            .mapNotNull { attr(it.value, "idref") }
            .mapNotNull { idToHref[it] }
            .toList()
    }

    private fun attr(tag: String, name: String): String? =
        Regex("(?i)\\b${Regex.escape(name)}\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1)

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
