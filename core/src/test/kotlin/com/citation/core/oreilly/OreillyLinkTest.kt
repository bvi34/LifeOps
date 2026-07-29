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

    @Test
    fun parsesNamedSlugFromCatalogLink() {
        val dest = OreillyLink.parse("https://learning.oreilly.com/library/view/the-pragmatic-programmer/9780135956977/")
        assertEquals("the-pragmatic-programmer", dest?.slug)
    }

    @Test
    fun bareDashSlugIsNotASlug() {
        val dest = OreillyLink.parse("https://learning.oreilly.com/library/view/-/9781492082279/")
        assertNull(dest?.slug)
    }

    @Test
    fun titleFromSlugTitleCasesWords() {
        assertEquals("The Pragmatic Programmer", OreillyLink.titleFromSlug("the-pragmatic-programmer"))
        assertNull(OreillyLink.titleFromSlug(null))
        assertNull(OreillyLink.titleFromSlug("-"))
    }

    @Test
    fun browseUrlRoutesThroughTheLibraryProxy() {
        assertEquals("https://learning.oreilly.com/search/", OreillyLink.browseUrl())
        assertEquals(
            "https://learning-oreilly-com.mcpl.idm.oclc.org/search/",
            OreillyLink.browseUrl(OreillyLibraryProxy.MID_CONTINENT)
        )
    }

    @Test
    fun buildsProxiedDeepLinkThroughLibrary() {
        val link = OreillyLink.deepLink("9781492082279", null, OreillyLibraryProxy.MID_CONTINENT)
        assertEquals(
            "https://learning-oreilly-com.mcpl.idm.oclc.org/library/view/-/9781492082279/",
            link
        )
    }

    @Test
    fun proxiedDeepLinkStillParsesBookIdAndLocation() {
        val link = OreillyLink.deepLink("9781098119003", "epubcfi(/6/22[chap]!/4/2)", OreillyLibraryProxy.MID_CONTINENT)
        val dest = OreillyLink.parse(link)!!
        assertEquals("9781098119003", dest.bookId)
        assertEquals("epubcfi(/6/22[chap]!/4/2)", dest.location)
    }

    @Test
    fun nullProxyLeavesTheDirectLink() {
        assertEquals(
            OreillyLink.deepLink("9781492082279"),
            OreillyLink.deepLink("9781492082279", null, null)
        )
    }
}
