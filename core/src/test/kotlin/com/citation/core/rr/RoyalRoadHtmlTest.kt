package com.citation.core.rr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoyalRoadHtmlTest {

    private val fictionPage = """
        <html><body>
          <h1>My Great Serial</h1>
          <table id="chapters">
            <tr><td><a href="/fiction/12345/slug/chapter/100/prologue">Prologue</a></td>
                <td><a href="/fiction/12345/slug/chapter/100/prologue#comments">3 comments</a></td></tr>
            <tr><td><a href="/fiction/12345/slug/chapter/101/beginnings">Chapter 1: Beginnings</a></td></tr>
            <tr><td><a href="/fiction/12345/slug/chapter/102/onward">Chapter 2: Onward</a></td></tr>
          </table>
          <a href="/profile/999">Author Page</a>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesOrderedCatalogAndDedupesByChapterId() {
        val catalog = RoyalRoadHtml.parseFictionChapters(12345, "My Great Serial", fictionPage)
        assertEquals(12345, catalog.fictionId)
        assertEquals(3, catalog.chapters.size) // the duplicate #comments link to ch100 is deduped
        assertEquals(listOf(100L, 101L, 102L), catalog.chapters.map { it.chapterId })
        assertEquals(listOf(0, 1, 2), catalog.chapters.map { it.ordinal })
        assertEquals("Prologue", catalog.chapters[0].title)
        assertEquals("Chapter 1: Beginnings", catalog.chapters[1].title)
        // The author profile link is not a chapter.
        assertFalse(catalog.chapters.any { it.url.contains("/profile/") })
    }

    @Test
    fun extractsChapterBodyWithoutSiteChrome() {
        val chapterPage = """
            <html><body>
              <h1>Chapter 1: Beginnings</h1>
              <div class="chapter-inner chapter-content">
                <p>It began on a Tuesday.</p>
                <div class="author-note-inner">A nested block still counts.</div>
                <p>And ended on a Friday.</p>
              </div>
              <div class="comments-section">Reader comments go here — must not leak.</div>
            </body></html>
        """.trimIndent()

        val extracted = RoyalRoadHtml.extractChapter(chapterPage)
        assertEquals("Chapter 1: Beginnings", extracted.title)
        assertTrue(extracted.text.contains("It began on a Tuesday."))
        assertTrue(extracted.text.contains("A nested block still counts."))
        assertTrue(extracted.text.contains("And ended on a Friday."))
        // Comments outside the content div must not be captured.
        assertFalse(extracted.text.contains("Reader comments"))
    }

    @Test
    fun toChapterPlacesTextAtRefOrdinal() {
        val catalog = RoyalRoadHtml.parseFictionChapters(12345, "My Great Serial", fictionPage)
        val ref = catalog.chapters[1]
        val chapter = RoyalRoadHtml.toChapter(
            ref,
            "<h1>Chapter 1: Beginnings</h1><div class=\"chapter-content\"><p>Body.</p></div>"
        )
        assertEquals(1, chapter.ordinal)
        assertEquals("Body.", chapter.text)
        assertEquals("/fiction/12345/slug/chapter/101/beginnings", chapter.sourceRef)
    }
}
