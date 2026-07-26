package com.citation.core.oreilly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OreillyLinkTest {

    @Test
    fun buildsDeepLinkWithAndWithoutLocation() {
        assertEquals(
            "https://learning.oreilly.com/library/view/-/9781492082279/",
            OreillyLink.deepLink("9781492082279")
        )
        assertEquals(
            "https://learning.oreilly.com/library/view/-/9781492082279/#!epubcfi(/6/14)",
            OreillyLink.deepLink("9781492082279", "epubcfi(/6/14)")
        )
    }

    @Test
    fun parsesBookIdAndLocation() {
        val dest = OreillyLink.parse("https://learning.oreilly.com/library/view/-/9781492082279/#!epubcfi(/6/14)")
        assertEquals("9781492082279", dest?.bookId)
        assertEquals("epubcfi(/6/14)", dest?.location)
    }

    @Test
    fun parsesWithNamedSlugAndNoLocation() {
        val dest = OreillyLink.parse("https://learning.oreilly.com/library/view/designing-data-intensive/9781492082279/")
        assertEquals("9781492082279", dest?.bookId)
        assertNull(dest?.location)
    }

    @Test
    fun roundTripsThroughBuildAndParse() {
        val url = OreillyLink.deepLink("9781098119003", "epubcfi(/6/22[chap]!/4/2)")
        val dest = OreillyLink.parse(url)!!
        assertEquals("9781098119003", dest.bookId)
        assertEquals("epubcfi(/6/22[chap]!/4/2)", dest.location)
    }

    @Test
    fun nonOreillyUrlReturnsNull() {
        assertNull(OreillyLink.parse("https://example.com/some/page"))
    }
}
