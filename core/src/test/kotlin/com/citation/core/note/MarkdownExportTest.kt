package com.citation.core.note

import com.citation.core.anchor.TextAnchor
import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExportTest {

    private fun note(
        id: Int,
        body: String = "",
        snapshot: String? = null,
        title: String = "A Book",
        author: String? = "An Author",
        tags: List<String> = emptyList()
    ): Note = Note(
        key = EntityKey("ER", "Note", id.toLong()),
        type = NoteType.FREESTANDING_SYNTHESIS,
        body = body,
        source = SourceDescriptor(null, SourceType.EPUB, null, title, author),
        references = snapshot?.let {
            listOf(PassageReference(it, TextAnchor.Flowing(0, 0, it, "", "")))
        } ?: emptyList(),
        createdAt = id.toLong(),
        tags = tags
    )

    @Test
    fun emptyCorpusStillProducesAHeadingAndPlaceholder() {
        val md = MarkdownExport.render(emptyList(), heading = "My Notes")
        assertTrue(md.startsWith("# My Notes"))
        assertTrue(md.contains("_No notes._"))
    }

    @Test
    fun rendersSnapshotAsBlockquoteBodyAndTagsWithAttribution() {
        val md = MarkdownExport.render(
            listOf(note(1, body = "my take", snapshot = "the quote", title = "1984", author = "Orwell", tags = listOf("dystopia")))
        )
        assertTrue(md.contains("## 1984 — Orwell"))
        assertTrue(md.contains("> the quote"))
        assertTrue(md.contains("my take"))
        assertTrue(md.contains("#dystopia"))
    }

    @Test
    fun multiLineSnapshotGetsBlockquotedPerLine() {
        val md = MarkdownExport.render(listOf(note(1, snapshot = "line one\nline two")))
        assertTrue(md.contains("> line one"))
        assertTrue(md.contains("> line two"))
    }

    @Test
    fun groupsBySourcePreservingFirstSeenOrder() {
        val md = MarkdownExport.render(
            listOf(
                note(1, body = "a", title = "Zeta", author = null),
                note(2, body = "b", title = "Alpha", author = null),
                note(3, body = "c", title = "Zeta", author = null)
            )
        )
        // Zeta appears first, so it heads the document; both Zeta notes fall under one heading.
        assertTrue(md.indexOf("## Zeta") < md.indexOf("## Alpha"))
        assertEquals(1, Regex("## Zeta").findAll(md).count())
    }

    @Test
    fun subtitleRendersAsItalicWhenPresent() {
        val md = MarkdownExport.render(listOf(note(1, body = "x")), subtitle = "Exported 2026-07-29")
        assertTrue(md.contains("_Exported 2026-07-29_"))
    }

    @Test
    fun titleOnlyWhenAuthorMissing() {
        val md = MarkdownExport.render(listOf(note(1, body = "x", title = "Solo", author = null)))
        assertTrue(md.contains("## Solo\n"))
    }
}
