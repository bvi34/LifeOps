package com.citation.core.ao3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ao3HtmlTest {

    private val workPage = """
        <html><head>
          <title>My Great Fic - CoolAuthor - Some Fandom [Archive of Our Own]</title>
        </head><body>
          <h2 class="title heading">My Great Fic</h2>
          <h3 class="byline heading">
            <a rel="author" href="/users/CoolAuthor/pseuds/CoolAuthor">CoolAuthor</a>
          </h3>
          <blockquote class="userstuff">This is the work summary — must not be a chapter.</blockquote>
          <ul id="chapter_index" class="actions">
            <li><form action="/works/12345/navigate">
              <select id="selected_id" name="selected_id">
                <option value="100" selected="selected">1. Prologue</option>
                <option value="101">2. Beginnings</option>
                <option value="102">3. Onward</option>
              </select>
            </form></li>
          </ul>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesOrderedCatalogFromChapterDropdown() {
        val title = Ao3Html.extractWorkTitle(workPage)!!
        val author = Ao3Html.extractWorkAuthor(workPage)
        val catalog = Ao3Html.parseWorkChapters(12345, title, author, workPage)
        assertEquals(12345, catalog.workId)
        assertEquals("My Great Fic", catalog.title)
        assertEquals("CoolAuthor", catalog.author)
        assertEquals(3, catalog.chapters.size)
        assertEquals(listOf(100L, 101L, 102L), catalog.chapters.map { it.chapterId })
        assertEquals(listOf(0, 1, 2), catalog.chapters.map { it.ordinal })
        // The leading "N. " number prefix is stripped from the visible title.
        assertEquals("Prologue", catalog.chapters[0].title)
        assertEquals("Beginnings", catalog.chapters[1].title)
        // Chapter URLs are reconstructed as /works/{workId}/chapters/{chapterId}.
        assertEquals("/works/12345/chapters/101", catalog.chapters[1].url)
    }

    @Test
    fun workTitleComesFromHeaderNotTheStuffedTitleTag() {
        // The <title>/og:title carry "Title - Author - Fandom … [Archive of Our Own]"; the work
        // header <h2 class="title heading"> is the clean source.
        assertEquals("My Great Fic", Ao3Html.extractWorkTitle(workPage))
    }

    @Test
    fun workTitleFallsBackToTitleTagLeadingSegment() {
        val page = "<html><head><title>Another Fic - Someone - A Fandom [Archive of Our Own]</title>" +
            "</head><body><p>no header heading</p></body></html>"
        assertEquals("Another Fic", Ao3Html.extractWorkTitle(page))
    }

    @Test
    fun singleChapterWorkDegradesToOneChapterAtTheWorkUrl() {
        val page = """
            <html><body>
              <h2 class="title heading">Solo Work</h2>
              <div class="userstuff module" role="article" id="chapter-1">
                <p>Just one chapter here.</p>
              </div>
            </body></html>
        """.trimIndent()
        val catalog = Ao3Html.parseWorkChapters(777, "Solo Work", "A", page)
        assertEquals(1, catalog.chapters.size)
        // No dropdown ⇒ the single chapter is keyed by the work id and read at the work URL.
        assertEquals(777L, catalog.chapters[0].chapterId)
        assertEquals("/works/777", catalog.chapters[0].url)
    }

    @Test
    fun extractsChapterBodyWithoutSummaryNotesOrComments() {
        val chapterPage = """
            <html><body>
              <h2 class="title heading">My Great Fic</h2>
              <div id="chapters">
                <div class="chapter" id="chapter-2">
                  <div class="chapter preface group">
                    <h3 class="title">Chapter 2: Beginnings</h3>
                    <blockquote class="userstuff">Chapter notes — must not leak.</blockquote>
                  </div>
                  <div class="userstuff module" role="article" id="chapter-2-content">
                    <h3 class="landmark heading" id="work">Chapter Text</h3>
                    <p>It began on a Tuesday.</p>
                    <div class="foo">A nested block still counts.</div>
                    <p>And ended on a Friday.</p>
                  </div>
                </div>
              </div>
              <blockquote class="userstuff">Work summary — must not leak.</blockquote>
              <ul id="comments">Reader comments must not leak.</ul>
            </body></html>
        """.trimIndent()

        val extracted = Ao3Html.extractChapter(chapterPage)
        assertEquals("Chapter 2: Beginnings", extracted.title)
        assertTrue(extracted.text.contains("It began on a Tuesday."))
        assertTrue(extracted.text.contains("A nested block still counts."))
        assertTrue(extracted.text.contains("And ended on a Friday."))
        // Only the role="article" userstuff div is the chapter — notes/summary/comments stay out.
        assertFalse(extracted.text.contains("Chapter notes"))
        assertFalse(extracted.text.contains("Work summary"))
        assertFalse(extracted.text.contains("Reader comments"))
    }

    @Test
    fun toChapterPlacesTextAtRefOrdinal() {
        val ref = Ao3ChapterRef(chapterId = 101, ordinal = 1, title = "Beginnings", url = "/works/12345/chapters/101")
        val chapter = Ao3Html.toChapter(
            ref,
            "<h3 class=\"title\">Chapter 2: Beginnings</h3>" +
                "<div class=\"userstuff\" role=\"article\"><p>Body.</p></div>"
        )
        assertEquals(1, chapter.ordinal)
        assertEquals("Body.", chapter.text)
        assertEquals("/works/12345/chapters/101", chapter.sourceRef)
    }
}
