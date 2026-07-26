package com.citation.core.rr

import com.citation.core.epub.Html
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Parses a fiction's per-fiction syndication feed (`royalroad.com/fiction/syndication/{id}`) and
 * acts as the **update detector**.
 *
 * The design rule this encodes: the *feed* is the cheap, blessed detector; a scrape is only the
 * expensive body puller. So this layer answers "did new chapters appear?" from the RSS alone — no
 * page fetches — and hands the caller just the chapter ids that are actually new. Feeds are polled
 * **individually** per fiction because Royal Road's comma-combined feed caps at ~10 items across all
 * ids and silently hides updates; one feed per fiction is the only way to see them all.
 */
object RoyalRoadFeed {

    /**
     * One feed item.
     * @property chapterId the chapter id parsed from the item link (`/chapter/{id}/…`), or `null`.
     * @property title item title.
     * @property link item link.
     * @property publishedAt epoch millis parsed from `pubDate`, or `null` if unparseable.
     */
    data class FeedEntry(
        val chapterId: Long?,
        val title: String,
        val link: String,
        val publishedAt: Long?
    )

    private val ITEM = Regex("(?is)<item\\b[^>]*>(.*?)</item>")
    private val CHAPTER_ID = Regex("/chapter/(\\d+)/")

    /** Parse the RSS XML into feed entries in document order (newest-first, as RR emits). */
    fun parse(xml: String): List<FeedEntry> =
        ITEM.findAll(xml).map { m ->
            val item = m.groupValues[1]
            val link = tag(item, "link")?.trim().orEmpty()
            val guid = tag(item, "guid")?.trim().orEmpty()
            val chapterId = (CHAPTER_ID.find(link) ?: CHAPTER_ID.find(guid))
                ?.groupValues?.get(1)?.toLongOrNull()
            FeedEntry(
                chapterId = chapterId,
                title = tag(item, "title")?.let { Html.toText(it).trim() }.orEmpty(),
                link = link,
                publishedAt = tag(item, "pubDate")?.let { parseDate(it.trim()) }
            )
        }.toList()

    /**
     * The **detector** step: given a parsed feed and the chapter ids already known to the catalog,
     * return the entries whose chapter is genuinely new, oldest-first (so the body puller fetches
     * them in reading order). Entries without a resolvable chapter id are skipped.
     */
    fun detectNewChapters(feed: List<FeedEntry>, knownChapterIds: Set<Long>): List<FeedEntry> =
        feed.filter { it.chapterId != null && it.chapterId !in knownChapterIds }
            .sortedBy { it.publishedAt ?: Long.MAX_VALUE }

    private fun tag(xml: String, name: String): String? =
        Regex("(?is)<${Regex.escape(name)}[^>]*>(.*?)</${Regex.escape(name)}>")
            .find(xml)?.groupValues?.get(1)
            ?.let { stripCdata(it) }

    private fun stripCdata(s: String): String =
        Regex("(?is)^\\s*<!\\[CDATA\\[(.*?)\\]\\]>\\s*$").find(s)?.groupValues?.get(1) ?: s

    private fun parseDate(raw: String): Long? = try {
        OffsetDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
