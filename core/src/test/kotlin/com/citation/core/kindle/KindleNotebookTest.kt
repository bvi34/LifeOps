package com.citation.core.kindle

import com.citation.core.capture.ProvenanceRung
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KindleNotebookTest {

    // A trimmed but faithful shape of Amazon's "Export notebook" HTML.
    private val export = """
        <html><body>
          <div class="bookTitle serif">Designing Data-Intensive Applications</div>
          <div class="authors serif">Kleppmann, Martin</div>
          <div class="citation serif">Citation (APA): Kleppmann, M. (2017)...</div>
          <hr class="bodymatter"/>
          <div class="sectionHeading">Chapter 1: Reliable, Scalable, and Maintainable</div>
          <h3 class="noteHeading">Highlight (<span class="highlight_yellow">yellow</span>) - Reliability &gt; Page 6 &middot; Location 512</h3>
          <div class="noteText">Many things can go wrong in a data system.</div>
          <h3 class="noteHeading">Note - Page 6 &middot; Location 512</h3>
          <div class="noteText">This is the crux of the whole chapter.</div>
          <h3 class="noteHeading">Highlight (<span class="highlight_pink">pink</span>) - Reliability &gt; Page 8 &middot; Location 540</h3>
          <div class="noteText">Fault is not the same as failure.</div>
          <h3 class="noteHeading">Note - Page 20 &middot; Location 900</h3>
          <div class="noteText">A standalone thought with no highlight.</div>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesBookTitleAndAuthor() {
        val parsed = KindleNotebook.parse(export)!!
        assertEquals("Designing Data-Intensive Applications", parsed.book.title)
        assertEquals("Kleppmann, Martin", parsed.book.author)
        assertNull("the notebook export carries no machine identity", parsed.book.identity)
    }

    @Test
    fun highlightWithFollowingNoteFoldsIntoOneAnnotatedEntry() {
        val parsed = KindleNotebook.parse(export)!!
        val first = parsed.entries.first()
        assertEquals("Many things can go wrong in a data system.", first.quote)
        assertEquals("This is the crux of the whole chapter.", first.annotation)
        assertEquals("512", first.location)
        assertEquals("6", first.page)
        assertEquals("Reliability", first.chapter)
        assertFalse(first.isStandaloneNote)
        assertEquals("Location 512", first.locationToken)
    }

    @Test
    fun bareHighlightHasNoAnnotation() {
        val parsed = KindleNotebook.parse(export)!!
        val second = parsed.entries[1]
        assertEquals("Fault is not the same as failure.", second.quote)
        assertNull(second.annotation)
        assertEquals("540", second.location)
    }

    @Test
    fun noteAtADifferentLocationStandsAlone() {
        val parsed = KindleNotebook.parse(export)!!
        val third = parsed.entries[2]
        assertTrue(third.isStandaloneNote)
        assertEquals("", third.quote)
        assertEquals("A standalone thought with no highlight.", third.annotation)
        assertEquals("900", third.location)
    }

    @Test
    fun yieldsExactlyThreeEntries() {
        // highlight+note fold to one, bare highlight, standalone note = 3.
        assertEquals(3, KindleNotebook.parse(export)!!.entries.size)
    }

    @Test
    fun provenanceIsAProvisionalTitleClusterSharedByEveryEntry() {
        val parsed = KindleNotebook.parse(export)!!
        val prov = parsed.provenance(capturedAt = 1_690_000_000_000L)
        // No ASIN/ISBN in the export → clusters on the (normalised) title, promotable when the book
        // is added properly. Provisional, and not thin (a title is a decent identifier).
        assertEquals(ProvenanceRung.TITLE, prov.rung)
        assertEquals("title:designing data intensive applications", prov.clusterId)
        assertEquals("Designing Data-Intensive Applications", prov.displayTitle)
        assertFalse(prov.isThin)
    }

    @Test
    fun handlesBooksWithoutPageNumbers() {
        val noPages = """
            <div class="bookTitle">Some Serial</div>
            <div class="authors">Anon</div>
            <h3 class="noteHeading">Highlight (yellow) - Location 42</h3>
            <div class="noteText">A line without a page number.</div>
        """.trimIndent()
        val parsed = KindleNotebook.parse(noPages)!!
        val e = parsed.entries.single()
        assertEquals("42", e.location)
        assertNull(e.page)
        assertEquals("Location 42", e.locationToken)
    }

    @Test
    fun rejectsNonKindleHtml() {
        assertNull(KindleNotebook.parse("<html><body><p>not a notebook</p></body></html>"))
        assertNull(KindleNotebook.parse("<div class=\"bookTitle\">Titled but empty</div>"))
    }
}
