package com.citation.core.oreilly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OreillyLibraryProxyTest {

    private val mcpl = OreillyLibraryProxy.MID_CONTINENT

    @Test
    fun rewritesOreillyHostToTheLibraryProxy() {
        assertEquals(
            "https://learning-oreilly-com.mcpl.idm.oclc.org/library/view/-/9781492082279/",
            mcpl.rewrite("https://learning.oreilly.com/library/view/-/9781492082279/")
        )
    }

    @Test
    fun rewritePreservesPathQueryAndFragment() {
        assertEquals(
            "https://learning-oreilly-com.mcpl.idm.oclc.org/library/view/-/9781098119003/?a=1#!epubcfi(/6/22)",
            mcpl.rewrite("https://learning.oreilly.com/library/view/-/9781098119003/?a=1#!epubcfi(/6/22)")
        )
    }

    @Test
    fun rewriteIsIdempotent() {
        val once = mcpl.rewrite("https://learning.oreilly.com/library/view/-/9781492082279/")
        assertEquals(once, mcpl.rewrite(once))
    }

    @Test
    fun encodeDoublesDashesSoHostsRoundTrip() {
        // A dash in the origin host is escaped as `--`; a dot becomes `-`.
        assertEquals("www-foo--bar-com", OreillyLibraryProxy.encodeHost("www.foo-bar.com"))
        assertEquals("www.foo-bar.com", OreillyLibraryProxy.decodeHost("www-foo--bar-com"))
    }

    @Test
    fun encodeDecodeRoundTripsOreillyHost() {
        val encoded = OreillyLibraryProxy.encodeHost("learning.oreilly.com")
        assertEquals("learning-oreilly-com", encoded)
        assertEquals("learning.oreilly.com", OreillyLibraryProxy.decodeHost(encoded))
    }

    @Test
    fun directUrlUndoesRewrite() {
        val direct = "https://learning.oreilly.com/library/view/-/9781492082279/#!epubcfi(/6/14)"
        val proxied = mcpl.rewrite(direct)
        assertEquals(direct, mcpl.directUrl(proxied))
    }

    @Test
    fun isProxiedRecognisesProxyHost() {
        assertTrue(mcpl.isProxied("https://learning-oreilly-com.mcpl.idm.oclc.org/library/view/-/x/"))
        assertFalse(mcpl.isProxied("https://learning.oreilly.com/library/view/-/x/"))
    }

    @Test
    fun nonHttpUrlPassesThroughUnchanged() {
        assertEquals("mailto:x@y.com", mcpl.rewrite("mailto:x@y.com"))
    }

    @Test
    fun customLibraryProxyHostIsHonoured() {
        val other = OreillyLibraryProxy("ezproxy.example.edu")
        assertEquals(
            "https://learning-oreilly-com.ezproxy.example.edu/library/view/-/x/",
            other.rewrite("https://learning.oreilly.com/library/view/-/x/")
        )
    }
}
