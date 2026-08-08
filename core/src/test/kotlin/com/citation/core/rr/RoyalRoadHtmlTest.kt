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
    fun fictionTitleComesFromMetadataNotHeaderChrome() {
        // A realistic fiction page: the header's notifications widget renders an <h*> *before* the
        // fiction's own <h1>, so a first-heading grab would mis-title the serial. og:title/<title>
        // carry the real name.
        val page = """
            <html><head>
              <title>My Great Serial | Royal Road</title>
              <meta property="og:title" content="My Great Serial" />
            </head><body>
              <header>
                <div class="notifications"><h4>You have no pending notifications</h4></div>
              </header>
              <div class="fic-title"><h1>My Great Serial</h1></div>
            </body></html>
        """.trimIndent()
        assertEquals("My Great Serial", RoyalRoadHtml.extractFictionTitle(page))
    }

    @Test
    fun fictionTitleFallsBackToTitleTagStrippingSiteSuffix() {
        val page = "<html><head><title>Another Serial - Royal Road</title></head>" +
            "<body><h3>You have no pending notifications</h3></body></html>"
        assertEquals("Another Serial", RoyalRoadHtml.extractFictionTitle(page))
    }

    @Test
    fun stripsCssHiddenPiracyStinger() {
        // Royal Road hides its anti-piracy stinger with a per-request random class set to
        // display:none, so a browser never shows it but a naive tag-strip would surface it.
        val chapterPage = """
            <html><head>
              <style>.rr-x9f{display:none}</style>
            </head><body>
              <h1>Chapter 4</h1>
              <div class="chapter-inner chapter-content">
                <p>The smell of fresh bread hit him as he stepped in.</p>
                <p class="rr-x9f">Unauthorized duplication: this narrative has been taken without consent. Report sightings.</p>
                <p>&ldquo;Morning John,&rdquo; said Mia, waving at him.</p>
              </div>
            </body></html>
        """.trimIndent()

        val extracted = RoyalRoadHtml.extractChapter(chapterPage)
        assertTrue(extracted.text.contains("fresh bread"))
        assertTrue(extracted.text.contains("Morning John"))
        assertFalse(extracted.text.contains("Unauthorized duplication"))
        assertFalse(extracted.text.contains("Report sightings"))
        assertFalse(extracted.text.contains("without consent"))
    }

    @Test
    fun stripsInlineHiddenPiracyStinger() {
        val chapterPage = """
            <div class="chapter-content">
              <p>Real sentence one.</p>
              <p style="display:none">This tale has been unlawfully taken from Royal Road; report it if you see it on Amazon.</p>
              <p>Real sentence two.</p>
            </div>
        """.trimIndent()

        val extracted = RoyalRoadHtml.extractChapter(chapterPage)
        assertTrue(extracted.text.contains("Real sentence one."))
        assertTrue(extracted.text.contains("Real sentence two."))
        assertFalse(extracted.text.contains("Royal Road"))
        assertFalse(extracted.text.contains("Amazon"))
    }

    @Test
    fun dropsStingerParagraphByPhraseWhenHidingCssIsAbsent() {
        // If the hiding CSS lived in an external stylesheet (not in the fetched HTML), the stinger
        // has no marker to strip structurally — the phrase fallback catches it.
        val chapterPage = """
            <div class="chapter-content">
              <p>He crossed the street and opened the door to the bakery.</p>
              <p>If you spot this story on Amazon, know it has been stolen; report the violation.</p>
              <p>Mia was the titular Hilda's daughter.</p>
            </div>
        """.trimIndent()

        val extracted = RoyalRoadHtml.extractChapter(chapterPage)
        assertTrue(extracted.text.contains("opened the door to the bakery"))
        assertTrue(extracted.text.contains("Hilda's daughter"))
        assertFalse(extracted.text.contains("report the violation"))
        assertFalse(extracted.text.contains("Amazon"))
    }

    @Test
    fun keepsLegitimateProseThatMentionsTheftOrAuthors() {
        // Guard against over-eager filtering: a real, long paragraph about theft in the story, or
        // that names an author, must survive.
        val chapterPage = """
            <div class="chapter-content">
              <p>The thief had taken the crown without consent, and John knew the whole city
                 would soon hear of the report; the author of the heist, a wiry man named Cassian,
                 had already vanished into the crowd, leaving only questions and a single glove
                 behind on the cobblestones as the guards fanned out across the square.</p>
              <p>He sighed and kept walking.</p>
            </div>
        """.trimIndent()

        val extracted = RoyalRoadHtml.extractChapter(chapterPage)
        assertTrue(extracted.text.contains("Cassian"))
        assertTrue(extracted.text.contains("without consent"))
        assertTrue(extracted.text.contains("kept walking"))
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
