package com.advisor.app.logic

/**
 * One thing, one record.
 *
 * Two apps legitimately hold the same object. A book read in Citation is also a row in LifeOps'
 * Collection (that's how reading minutes pay the reward at week-close), and a highlight captured in
 * Citation is synced onto that book's LifeOps notes. Each app's knowledge source is honest about
 * its own data, so with both granted the corpus would carry the same book — and the same note —
 * twice: wasted context, and a model that can reasonably read a pair as two things.
 *
 * The fold keeps whichever side holds more of what gets asked about:
 *
 *  - **Books → LifeOps.** It has the reading status, the minutes logged, the reading category and
 *    the finish date. Citation's one exclusive fact, the favourite flag, is carried across.
 *  - **Notes → Citation.** It has the note's frozen title and author; LifeOps' row is a mirror of
 *    the same text.
 *
 * Books pair by **row key** — LifeOps stores a synced book under Citation's own book key, so the two
 * document ids share a suffix and a rename in LifeOps (where the title is the user's to change)
 * can't split them — or, failing that, by title, which catches a book added to LifeOps by hand and
 * later read in Citation. Notes pair by key alone: a synced note's LifeOps row id is literally
 * `citation:<noteKey>`, so the match is exact and no unsynced note is ever touched.
 *
 * Nothing else in the corpus is affected, and neither is a record whose counterpart isn't there —
 * with only one app granted, everything it holds survives.
 */
object CorpusMerge {

    private const val FAVOURITE = "Favourite."
    private const val SYNCED_NOTE_PREFIX = "citation:"

    fun merge(docs: List<KnowledgeDocument>): List<KnowledgeDocument> = mergeNotes(mergeBooks(docs))

    // --- books: the LifeOps record wins ---

    private fun mergeBooks(docs: List<KnowledgeDocument>): List<KnowledgeDocument> {
        val lifeOpsBooks = docs.filter { it.source == SourceApp.LIFEOPS && it.kind == "book" }
        val citationBooks = docs.filter { it.source == SourceApp.CITATION && it.kind == "book" }
        if (lifeOpsBooks.isEmpty() || citationBooks.isEmpty()) return docs

        val heldKeys = lifeOpsBooks.map { rowKey(it) }.toSet()
        val heldTitles = lifeOpsBooks.map { titleKey(it) }.toSet()
        val dropped = citationBooks.filter { rowKey(it) in heldKeys || titleKey(it) in heldTitles }
        if (dropped.isEmpty()) return docs

        val droppedIds = dropped.map { it.id }.toSet()
        val favourites = dropped.filter { it.body.contains(FAVOURITE, ignoreCase = true) }
        val favouriteKeys = favourites.map { rowKey(it) }.toSet()
        val favouriteTitles = favourites.map { titleKey(it) }.toSet()

        return docs.mapNotNull { doc ->
            val isLifeOpsBook = doc.source == SourceApp.LIFEOPS && doc.kind == "book"
            val isFavourite = isLifeOpsBook &&
                (rowKey(doc) in favouriteKeys || titleKey(doc) in favouriteTitles)
            when {
                doc.id in droppedIds -> null
                isFavourite && !doc.body.contains(FAVOURITE, ignoreCase = true) ->
                    doc.copy(body = doc.body.trimEnd().trimEnd('.') + ". $FAVOURITE")
                else -> doc
            }
        }
    }

    // --- notes: the Citation record wins ---

    private fun mergeNotes(docs: List<KnowledgeDocument>): List<KnowledgeDocument> {
        val citationKeys = docs
            .filter { it.source == SourceApp.CITATION && it.kind == "note" }
            .map { rowKey(it) }
            .toSet()
        if (citationKeys.isEmpty()) return docs

        return docs.filterNot { doc ->
            if (doc.source != SourceApp.LIFEOPS || doc.kind != "note") return@filterNot false
            val key = rowKey(doc)
            key.startsWith(SYNCED_NOTE_PREFIX) && key.removePrefix(SYNCED_NOTE_PREFIX) in citationKeys
        }
    }

    /** The row id behind a document id of the form `<source>:<kind>:<rowId>`. */
    private fun rowKey(doc: KnowledgeDocument): String = doc.id.substringAfter(":").substringAfter(":")

    private fun titleKey(doc: KnowledgeDocument): String = doc.title.trim().lowercase()
}
