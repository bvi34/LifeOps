package com.citation.core.library

import com.citation.core.model.SourceType
import com.citation.core.sync.AcquisitionState
import com.citation.core.sync.ReadingState

/**
 * The shelf-facing view of one book, and the pure rules for arranging a lot of them.
 *
 * The library screen was a flat, unsorted, unsearchable column of titles, which is workable for a
 * dozen books and useless for hundreds — and a catalog connection makes hundreds the normal case.
 * All the arranging lives here rather than in the screen: sorting, filtering, searching and series
 * grouping are decisions with edge cases (what is "The" in a title, what is a surname, what does
 * 42% mean), and they are worth testing without a device.
 */
data class LibraryEntry(
    val key: String,
    val title: String,
    val author: String? = null,
    val series: String? = null,
    val seriesIndex: Float? = null,
    val subjects: List<String> = emptyList(),
    val sourceType: SourceType = SourceType.EPUB,
    val readingState: ReadingState = ReadingState.TO_READ,
    val acquisitionState: AcquisitionState = AcquisitionState.ACQUIRED,
    val addedAt: Long = 0,
    val lastOpenedAt: Long? = null,
    /** 0-based chapter last read, and how many there are — the two numbers progress comes from. */
    val lastChapterOrdinal: Int = 0,
    val chapterCount: Int = 0,
    val isFavorite: Boolean = false,
    val collectionIds: Set<String> = emptySet()
) {

    /**
     * How far through, as a fraction. Chapter-granular, because that is the only progress measure
     * that means the same thing in every reader track — a PDF page count, a web serial's chapter
     * list and an EPUB spine all agree on "which chapter", and none agree on "which page".
     *
     * A finished book reads 1.0 even if its last chapter was never scrolled to the bottom; an
     * unopened one reads 0.0 rather than 1/n, because opening chapter one is not progress.
     */
    val progress: Float
        get() = when {
            readingState == ReadingState.DONE -> 1f
            chapterCount <= 0 || lastOpenedAt == null -> 0f
            else -> ((lastChapterOrdinal + 1).toFloat() / chapterCount).coerceIn(0f, 1f)
        }

    val isStarted: Boolean get() = lastOpenedAt != null && readingState != ReadingState.DONE

    /** "The Expanse #1", or null. */
    val seriesLabel: String?
        get() = series?.let { name ->
            val index = seriesIndex ?: return@let name
            val trimmed = if (index == index.toInt().toFloat()) index.toInt().toString() else index.toString()
            "$name #$trimmed"
        }

    /** Title with a leading article moved out of the way, so shelves alphabetise like shelves. */
    val sortableTitle: String get() = LibrarySorting.stripArticle(title).lowercase()

    /** "Wells, H. G." — surname first, which is how anyone scans an author list. */
    val sortableAuthor: String get() = LibrarySorting.surnameFirst(author).lowercase()

    /** Everything a search should look at, lowercased once. */
    internal val searchable: String
        get() = buildString {
            append(title).append(' ')
            author?.let { append(it).append(' ') }
            series?.let { append(it).append(' ') }
            subjects.forEach { append(it).append(' ') }
        }.lowercase()
}

/** How to order a shelf. */
enum class LibrarySort {
    /** Most recently opened first — "where was I", the default. */
    RECENT,

    /** Most recently added first. */
    ADDED,

    TITLE,
    AUTHOR,

    /** By series then position, with standalone books after. */
    SERIES,

    /** Furthest along first, finished books last — for clearing a half-read pile. */
    PROGRESS;

    val label: String
        get() = when (this) {
            RECENT -> "Recently read"
            ADDED -> "Recently added"
            TITLE -> "Title"
            AUTHOR -> "Author"
            SERIES -> "Series"
            PROGRESS -> "Progress"
        }
}

/**
 * What to show. Every field narrows; an empty filter shows everything.
 *
 * @property query free text matched against title, author, series and subjects.
 * @property collectionId only books on one shelf.
 * @property source only books from one place (only PDFs, only Royal Road).
 * @property reading only books in one reading state.
 * @property subject only books carrying one subject/tag.
 * @property favoritesOnly only starred books.
 * @property includeWanted whether books that are wanted-but-not-acquired appear. They are real
 *   library rows (LifeOps asked for them) but they cannot be opened, so most views hide them.
 */
data class LibraryFilter(
    val query: String = "",
    val collectionId: String? = null,
    val source: SourceType? = null,
    val reading: ReadingState? = null,
    val subject: String? = null,
    val favoritesOnly: Boolean = false,
    val includeWanted: Boolean = true
) {
    val isEmpty: Boolean
        get() = query.isBlank() && collectionId == null && source == null &&
            reading == null && subject == null && !favoritesOnly
}

/** Applying a [LibraryFilter] and a [LibrarySort] to a shelf. Pure, so it is all unit-tested. */
object LibraryQuery {

    fun apply(
        entries: List<LibraryEntry>,
        filter: LibraryFilter = LibraryFilter(),
        sort: LibrarySort = LibrarySort.RECENT
    ): List<LibraryEntry> = sort(entries.filter { matches(it, filter) }, sort)

    /**
     * A book matches when **every** whitespace token of the query appears somewhere in its
     * searchable surface — AND-of-tokens narrows as you type rather than widening, the same rule
     * note search already uses, so the two behave alike.
     */
    fun matches(entry: LibraryEntry, filter: LibraryFilter): Boolean {
        if (!filter.includeWanted && entry.acquisitionState != AcquisitionState.ACQUIRED) return false
        if (filter.favoritesOnly && !entry.isFavorite) return false
        filter.collectionId?.let { if (it !in entry.collectionIds) return false }
        filter.source?.let { if (entry.sourceType != it) return false }
        filter.reading?.let { if (entry.readingState != it) return false }
        filter.subject?.let { subject ->
            if (entry.subjects.none { it.equals(subject, ignoreCase = true) }) return false
        }
        if (filter.query.isNotBlank()) {
            val surface = entry.searchable
            val tokens = filter.query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (!tokens.all { surface.contains(it) }) return false
        }
        return true
    }

    fun sort(entries: List<LibraryEntry>, sort: LibrarySort): List<LibraryEntry> = when (sort) {
        // Never-opened books sort after opened ones rather than jumbling in at zero.
        LibrarySort.RECENT -> entries.sortedWith(
            compareByDescending<LibraryEntry> { it.lastOpenedAt ?: Long.MIN_VALUE }
                .thenByDescending { it.addedAt }
                .thenBy { it.sortableTitle }
        )
        LibrarySort.ADDED -> entries.sortedWith(
            compareByDescending<LibraryEntry> { it.addedAt }.thenBy { it.sortableTitle }
        )
        LibrarySort.TITLE -> entries.sortedWith(
            compareBy<LibraryEntry> { it.sortableTitle }.thenBy { it.sortableAuthor }
        )
        LibrarySort.AUTHOR -> entries.sortedWith(
            compareBy<LibraryEntry> { it.sortableAuthor }
                .thenBy { it.series ?: "" }
                .thenBy { it.seriesIndex ?: Float.MAX_VALUE }
                .thenBy { it.sortableTitle }
        )
        // Standalone books after the series, so the series read as blocks.
        LibrarySort.SERIES -> entries.sortedWith(
            compareBy<LibraryEntry> { it.series == null }
                .thenBy { it.series?.lowercase() ?: "" }
                .thenBy { it.seriesIndex ?: Float.MAX_VALUE }
                .thenBy { it.sortableTitle }
        )
        LibrarySort.PROGRESS -> entries.sortedWith(
            compareBy<LibraryEntry> { it.readingState == ReadingState.DONE }
                .thenByDescending { it.progress }
                .thenBy { it.sortableTitle }
        )
    }

    /** Subject facet counts, most-used first — the chip row above a shelf. */
    fun subjectCounts(entries: List<LibraryEntry>): List<Facet> =
        entries.flatMap { it.subjects }
            .groupingBy { it }
            .eachCount()
            .map { (name, count) -> Facet(name, count) }
            .sortedWith(compareByDescending<Facet> { it.count }.thenBy { it.name.lowercase() })

    /** Series present in the library, with how many volumes of each are held. */
    fun seriesCounts(entries: List<LibraryEntry>): List<Facet> =
        entries.mapNotNull { it.series }
            .groupingBy { it }
            .eachCount()
            .map { (name, count) -> Facet(name, count) }
            .sortedBy { it.name.lowercase() }
}

/** One facet value and how many books carry it. */
data class Facet(val name: String, val count: Int)

/** The naming conventions shelves are sorted by. */
object LibrarySorting {

    private val ARTICLES = listOf("the ", "a ", "an ")

    /** Drop a leading article, so *The Time Machine* files under T-i-m-e. */
    fun stripArticle(title: String): String {
        val trimmed = title.trimStart()
        val lower = trimmed.lowercase()
        ARTICLES.forEach { article ->
            if (lower.startsWith(article)) return trimmed.substring(article.length).trimStart()
        }
        return trimmed
    }

    /**
     * "H. G. Wells" to "Wells, H. G.".
     *
     * Deliberately naive — it moves the last whitespace-separated word to the front — because the
     * alternative is a name-parsing library and a long tail of wrong guesses. A name already
     * written surname-first (it contains a comma) is left alone, which covers most catalog data.
     */
    fun surnameFirst(author: String?): String {
        val name = author?.trim().orEmpty()
        if (name.isEmpty() || name.contains(',')) return name
        val parts = name.split(Regex("\\s+"))
        if (parts.size < 2) return name
        return parts.last() + ", " + parts.dropLast(1).joinToString(" ")
    }
}

/**
 * A shelf the user made: a name, an order, and whatever they put on it.
 *
 * Deliberately manual rather than rule-based. A "smart collection" needs a query language, an
 * evaluation moment and an explanation for why a book vanished; a shelf you drag books onto needs
 * none of that and is what people actually reach for. Series and subject grouping already give the
 * automatic view, from data the book itself carries.
 */
data class BookCollection(
    val id: String,
    val name: String,
    val position: Int = 0
)
