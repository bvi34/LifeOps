package com.citation.core.kindle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KindleLinkTest {

    @Test
    fun buildsReaderUrlFromAsin() {
        assertEquals("https://read.amazon.com/?asin=B0H9Y3ZFJM", KindleLink.readerUrl("B0H9Y3ZFJM"))
        assertEquals("https://read.amazon.com/?asin=B0H9Y3ZFJM", KindleLink.readerUrl("  B0H9Y3ZFJM  "))
    }

    @Test
    fun pullsAsinFromAReaderUrl() {
        assertEquals(
            "B0H9Y3ZFJM",
            KindleLink.asinOf("https://read.amazon.com/?asin=B0H9Y3ZFJM&ref_=kwl_kr_iv_rec_1")
        )
        assertEquals("B0H9Y3ZFJM", KindleLink.asinOf("https://read.amazon.com/kindle-library?asin=B0H9Y3ZFJM"))
    }

    @Test
    fun nonKindleUrlHasNoAsin() {
        assertNull(KindleLink.asinOf("https://example.com/?asin=NOTKINDLE"))
        assertNull(KindleLink.asinOf("https://read.amazon.com/kindle-library"))
    }

    @Test
    fun parsesLocationOfTotalWithPercent() {
        val pos = KindleLink.parseFooter("Location 156 of 3866 ● 4%")!!
        assertEquals("Location", pos.unit)
        assertEquals("156", pos.value)
        assertEquals("3866", pos.total)
        assertEquals("4", pos.percent)
        assertEquals("Location 156", pos.token)
        assertEquals("Location 156 of 3866", pos.label)
    }

    @Test
    fun stripsThousandsCommas() {
        val pos = KindleLink.parseFooter("Location 1,203 of 12,540 ● 10%")!!
        assertEquals("1203", pos.value)
        assertEquals("12540", pos.total)
        assertEquals("Location 1203 of 12540", pos.label)
    }

    @Test
    fun recognisesPageScaleAndBareLocation() {
        val page = KindleLink.parseFooter("Page 42 of 300")!!
        assertEquals("Page", page.unit)
        assertEquals("42", page.value)
        assertEquals("Page 42 of 300", page.label)

        val bare = KindleLink.parseFooter("Location 512")!!
        assertNull(bare.total)
        assertEquals("Location 512", bare.label)
    }

    @Test
    fun emptyOrPositionlessFooterIsNull() {
        assertNull(KindleLink.parseFooter(""))
        assertNull(KindleLink.parseFooter("Loading…"))
    }
}
