package com.repository.app.logic

import java.util.Locale

/** One drawer's worth of the shelf: the app a group of documents belongs to, and its documents. */
data class ShelfGroup(
    /** The owning app's key, or null for the household's own drawer. */
    val appKey: String?,
    val label: String,
    val documents: List<DocumentFacts>
) {
    val sizeBytes: Long get() = documents.sumOf { it.sizeBytes ?: 0L }
}

/**
 * How the shelf reads: what is on it, in what order, and what a search finds.
 *
 * The whole point of this app is that a household should not have to remember which app it filed
 * something in. So the list is one list — Repository's own documents and the ones other apps are
 * lending it, side by side — and everything here is about making that single list findable.
 */
object Shelf {

    /**
     * Newest first.
     *
     * By when it was *filed*, which is the only date this app has: it does not read documents, so it
     * cannot know the date printed on one. A household filing a folder of last year's paperwork in
     * one sitting gets it in the order they scanned it, which is the order they are thinking in.
     * Ties break on title so the order never wobbles between two documents filed the same second by
     * the same import.
     */
    fun order(documents: List<DocumentFacts>): List<DocumentFacts> =
        documents.sortedWith(
            compareByDescending<DocumentFacts> { it.addedAt }.thenBy { it.title.lowercase(Locale.US) }
        )

    /**
     * What a search for [query] finds, in [order].
     *
     * Matching is on the title, the note, the kind's label and **the owner's label** — that last one
     * is what lets "wrangler" find the truck's manual and "sam" find a lab result, without this
     * module knowing what a truck or a person is. Every term has to match something; the terms may
     * match different fields, so "wrangler warranty" finds the warranty on the truck.
     *
     * A blank query is not a filter. It returns everything rather than nothing, because an empty
     * search box is the state the screen opens in.
     */
    fun search(documents: List<DocumentFacts>, query: String): List<DocumentFacts> {
        val terms = query.lowercase(Locale.US).split(WHITESPACE).filter { it.isNotBlank() }
        if (terms.isEmpty()) return order(documents)
        return order(
            documents.filter { document ->
                val haystack = listOfNotNull(
                    document.title,
                    document.note,
                    document.kind.label,
                    document.owner.label
                ).joinToString(" ").lowercase(Locale.US)
                terms.all { haystack.contains(it) }
            }
        )
    }

    /**
     * The shelf as drawers, in a fixed order: the household's own first, then each app that has
     * filed something, by name.
     *
     * The household drawer leads because a document that belongs to no app is one nothing else will
     * ever show you — a will, a passport, the survey — and it would otherwise be the hardest thing
     * on the shelf to find. Everything after it is reachable from its own app as well as from here.
     *
     * [appName] resolves an app key to what to call it; a key nobody claims is shown as it came,
     * because a restored document from an app this build does not have is still a document.
     */
    fun drawers(documents: List<DocumentFacts>, appName: (String) -> String?): List<ShelfGroup> {
        val byApp = documents.groupBy { it.owner.appKey }
        val household = byApp[null].orEmpty()
        val filed = byApp.filterKeys { it != null }
            .map { (key, docs) -> ShelfGroup(key, appName(key!!) ?: key, order(docs)) }
            .sortedBy { it.label.lowercase(Locale.US) }
        return buildList {
            if (household.isNotEmpty()) add(ShelfGroup(null, HOUSEHOLD_LABEL, order(household)))
            addAll(filed)
        }
    }

    /**
     * The documents on one of an app's records — what the section lent to an owning app shows.
     *
     * The match is on both keys together. A record key is only unique inside the app that issued it,
     * and two apps both numbering their things from one is not a hypothetical.
     */
    fun on(documents: List<DocumentFacts>, appKey: String, recordKey: String): List<DocumentFacts> =
        order(documents.filter { it.owner.appKey == appKey && it.owner.recordKey == recordKey })

    /** "4 documents · 2.1 MB" — the one line at the top of the shelf. */
    fun headline(documents: List<DocumentFacts>): String {
        if (documents.isEmpty()) return "Nothing filed yet"
        val count = if (documents.size == 1) "1 document" else "${documents.size} documents"
        val size = Documents.formatSize(documents.sumOf { it.sizeBytes ?: 0L })
        return listOfNotNull(count, size).joinToString(" · ")
    }

    const val HOUSEHOLD_LABEL = "The household"

    private val WHITESPACE = Regex("\\s+")
}
