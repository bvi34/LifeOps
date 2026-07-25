package com.citation.core.rr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoyalRoadFeedTest {

    // Newest-first, as RR emits; ch103 and ch104 are newer than the known ch100..102.
    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <title>My Great Serial</title>
          <item>
            <title><![CDATA[Chapter 4: Storm]]></title>
            <link>https://www.royalroad.com/fiction/12345/slug/chapter/104/storm</link>
            <guid>https://www.royalroad.com/fiction/12345/slug/chapter/104/storm</guid>
            <pubDate>Wed, 22 Jul 2026 10:00:00 GMT</pubDate>
          </item>
          <item>
            <title>Chapter 3: Calm</title>
            <link>https://www.royalroad.com/fiction/12345/slug/chapter/103/calm</link>
            <pubDate>Tue, 21 Jul 2026 10:00:00 GMT</pubDate>
          </item>
          <item>
            <title>Chapter 2: Onward</title>
            <link>https://www.royalroad.com/fiction/12345/slug/chapter/102/onward</link>
            <pubDate>Mon, 20 Jul 2026 10:00:00 GMT</pubDate>
          </item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun parsesItemsWithChapterIdsTitlesAndDates() {
        val entries = RoyalRoadFeed.parse(rss)
        assertEquals(3, entries.size)
        assertEquals(104L, entries[0].chapterId)
        assertEquals("Chapter 4: Storm", entries[0].title) // CDATA stripped
        assertTrue(entries[0].publishedAt != null && entries[0].publishedAt!! > 0)
        assertEquals(103L, entries[1].chapterId)
    }

    @Test
    fun detectsOnlyGenuinelyNewChaptersOldestFirst() {
        val feed = RoyalRoadFeed.parse(rss)
        val known = setOf(100L, 101L, 102L)
        val new = RoyalRoadFeed.detectNewChapters(feed, known)
        // 103 and 104 are new; returned oldest-first so the body puller fetches in reading order.
        assertEquals(listOf(103L, 104L), new.map { it.chapterId })
    }

    @Test
    fun noNewChaptersWhenFeedIsAllKnown() {
        val feed = RoyalRoadFeed.parse(rss)
        val known = setOf(102L, 103L, 104L)
        assertTrue(RoyalRoadFeed.detectNewChapters(feed, known).isEmpty())
    }
}
