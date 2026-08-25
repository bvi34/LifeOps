package com.citation.core.model

/**
 * A book's **real** table of contents — the nested one its publisher wrote, not the flat list of
 * spine files the reader used to show.
 *
 * The distinction matters for anything longer than a novel: a reference book's spine is a hundred
 * undifferentiated XHTML files, while its `nav`/`ncx` says Part II › Chapter 7 › "Consistent
 * Hashing". Entries can also point *inside* a file via [fragment], which is how a single-file book
 * (very common for older EPUBs and every AO3 download) still gets a usable contents list.
 */
data class TableOfContents(val entries: List<TocEntry> = emptyList()) {

    val isEmpty: Boolean get() = entries.isEmpty()

    /** Every entry, depth-first, paired with its nesting depth (0 for top level). */
    fun flatten(): List<Pair<TocEntry, Int>> {
        val out = ArrayList<Pair<TocEntry, Int>>()
        fun walk(list: List<TocEntry>, depth: Int) {
            list.forEach {
                out.add(it to depth)
                walk(it.children, depth + 1)
            }
        }
        walk(entries, 0)
        return out
    }

    /** The deepest entry that covers [ordinal], for showing "where am I" while reading. */
    fun entryFor(ordinal: Int): TocEntry? =
        flatten().lastOrNull { (entry, _) -> entry.chapterOrdinal != null && entry.chapterOrdinal <= ordinal }?.first

    companion object {
        val EMPTY = TableOfContents()
    }
}

/**
 * One line of the contents.
 *
 * @property title the label as written by the publisher.
 * @property chapterOrdinal which chapter it lands in, or `null` when the target isn't in the spine
 *   (a stray link) — such an entry is still shown, just not tappable.
 * @property fragment the `id` within that chapter, when the entry points mid-file. Resolved against
 *   [Chapter.anchors] to an offset at read time.
 * @property children nested entries beneath this one.
 */
data class TocEntry(
    val title: String,
    val chapterOrdinal: Int?,
    val fragment: String? = null,
    val children: List<TocEntry> = emptyList()
)
