package com.citation.core.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTest {

    @Test
    fun stripsTagsAndDecodesEntities() {
        val text = Html.toText("<p>Tom &amp; Jerry &mdash; <b>friends</b>&nbsp;forever</p>")
        assertEquals("Tom & Jerry — friends forever", text)
    }

    @Test
    fun blockElementsBecomeParagraphBreaks() {
        val text = Html.toText("<p>One</p><p>Two</p>")
        assertEquals("One\n\nTwo", text)
    }

    @Test
    fun dropsScriptAndStyle() {
        val text = Html.toText("<style>p{color:red}</style><p>Body</p><script>evil()</script>")
        assertEquals("Body", text)
        assertFalse(text.contains("evil"))
        assertFalse(text.contains("color"))
    }

    @Test
    fun numericEntitiesDecode() {
        assertEquals("A—B", Html.toText("A&#8212;B"))
        assertEquals("A—B", Html.toText("A&#x2014;B"))
    }

    @Test
    fun deterministicWhitespaceCollapse() {
        val a = Html.toText("<p>hello    world\n\n\n\nnext</p>")
        val b = Html.toText("<p>hello world\n\nnext</p>")
        // Same canonical text regardless of incoming whitespace — anchors depend on this.
        assertTrue(a.contains("hello world"))
        assertEquals(a, b.let { Html.toText("<p>hello world\n\nnext</p>") })
    }

    @Test
    fun extractsHeading() {
        assertEquals("Chapter One", Html.extractHeading("<h1>Chapter One</h1><p>x</p>"))
    }
}
