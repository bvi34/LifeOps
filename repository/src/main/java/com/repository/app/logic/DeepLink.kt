package com.repository.app.logic

/**
 * Somewhere on the shelf worth opening at.
 *
 * Until this existed the only thing anything outside could do was *start* Repository. That is a
 * strange gap for the one app in the suite whose whole purpose is being pointed at: Maintenance can
 * show you the furnace's manual on the furnace and cannot say "and here is the rest of the shelf
 * this came off"; Advisor can ground an answer in the mortgage statement and cannot take you to it;
 * the section lent to an owning app can list a record's documents and cannot hand the list on. All
 * of those are the same question — open Repository showing X — so all of them go through here.
 *
 * A destination is a **filter on the one list**, never a screen. The shelf has no navigation and is
 * not about to grow any: a drawer you have to open to see into is a folder by another name, and the
 * argument for this app is that a household should not have had to file things correctly to find
 * them. So the deepest link here still lands somebody on the shelf — just the shelf already narrowed
 * to the thing they were sent for, with everything else one press away.
 */
sealed interface RepositoryDestination {

    /** The shelf as it opens: everything, newest first. */
    data object Shelf : RepositoryDestination

    /**
     * One drawer — an app's documents, or the household's own.
     *
     * [appKey] is null for the household's drawer, which is a real place and not a missing value:
     * the will, the passport and the survey belong to no app in the suite and are the reason this
     * app exists as more than a library.
     */
    data class Drawer(val appKey: String?) : RepositoryDestination

    /**
     * The documents filed against one of an app's records — the furnace, the truck, one project.
     *
     * Both keys, because a record key is only unique inside the app that issued it and two apps
     * numbering their things from one is not a hypothetical (see [Shelf.on]).
     */
    data class Record(val appKey: String, val recordKey: String) : RepositoryDestination

    /**
     * One document, with the shelf narrowed to it.
     *
     * [sourceKey] names the app *lending* it, and is null for a document this app holds. It has to
     * be part of the address rather than looked up: an id is only unique within the app that minted
     * it, which is exactly why the shelf keys its rows on the pair. Addressing a lent document by id
     * alone would be a link that lands on somebody else's document the first time two apps mint the
     * same one.
     */
    data class Document(val documentId: String, val sourceKey: String? = null) : RepositoryDestination
}

/**
 * Destinations as text, for putting in an intent.
 *
 * Everything here is pure and total: nothing throws, and anything unrecognised parses to `null`,
 * which every caller reads as "just open the shelf". A deep link is a hint from somewhere else, and
 * a hint that has gone stale must never be able to stop the app opening — least of all this app,
 * where the thing somebody is failing to reach may be the only copy of a document in the house.
 */
object RepositoryLinks {

    private const val SEGMENT_SHELF = "shelf"
    private const val SEGMENT_DRAWER = "drawer"
    private const val SEGMENT_DOCUMENT = "document"
    private const val SEGMENT_LENT = "lent"

    /**
     * The household's drawer, written down.
     *
     * A sentinel rather than an empty segment, because an address with a hole in it is one nobody
     * can read in a log. No app in the suite has this key, and the check below refuses to *write*
     * one that did rather than letting it silently mean the household — an address that means two
     * things is worse than one that cannot be written.
     */
    private const val SEGMENT_HOUSEHOLD = "household"

    /**
     * A destination as an address, or `null` if it cannot be written down unambiguously.
     *
     * The ways that happens are a key containing a `/`, which would split into extra segments and
     * parse back as something else, and an app whose key collides with the household sentinel. The
     * suite's keys are short lower-case words so neither should arise — but producing an address
     * that means something *different* from what was asked for is exactly the bug this returns
     * `null` to avoid, and a caller handed `null` opens the shelf plainly.
     */
    fun format(destination: RepositoryDestination): String? = when (destination) {
        is RepositoryDestination.Shelf -> SEGMENT_SHELF

        is RepositoryDestination.Drawer -> when (val app = destination.appKey) {
            null -> "$SEGMENT_DRAWER/$SEGMENT_HOUSEHOLD"
            else -> app.takeIf { it.isUsableKey() }?.let { "$SEGMENT_DRAWER/$it" }
        }

        is RepositoryDestination.Record -> {
            val app = destination.appKey.takeIf { it.isUsableKey() }
            val record = destination.recordKey.takeIf { it.isUsableKey() }
            if (app == null || record == null) null else "$SEGMENT_DRAWER/$app/$record"
        }

        is RepositoryDestination.Document -> {
            val id = destination.documentId.takeIf { it.isUsableKey() }
            val source = destination.sourceKey
            when {
                id == null -> null
                source == null -> "$SEGMENT_DOCUMENT/$id"
                !source.isUsableKey() -> null
                else -> "$SEGMENT_LENT/$source/$id"
            }
        }
    }

    /** An address back into a destination, or `null` for anything this app does not recognise. */
    fun parse(address: String?): RepositoryDestination? {
        val segments = address?.trim()?.split("/")?.filter { it.isNotBlank() } ?: return null

        return when {
            segments.size == 1 && segments[0] == SEGMENT_SHELF -> RepositoryDestination.Shelf

            segments.size == 2 && segments[0] == SEGMENT_DRAWER ->
                RepositoryDestination.Drawer(segments[1].takeIf { it != SEGMENT_HOUSEHOLD })

            segments.size == 3 && segments[0] == SEGMENT_DRAWER ->
                // The household's drawer holds no records — nothing owns those documents, which is
                // what makes them the household's. An address saying otherwise is a caller's bug,
                // and landing it somewhere plausible is how that bug survives to ship.
                if (segments[1] == SEGMENT_HOUSEHOLD) null
                else RepositoryDestination.Record(segments[1], segments[2])

            segments.size == 2 && segments[0] == SEGMENT_DOCUMENT ->
                RepositoryDestination.Document(segments[1])

            segments.size == 3 && segments[0] == SEGMENT_LENT ->
                RepositoryDestination.Document(documentId = segments[2], sourceKey = segments[1])

            else -> null
        }
    }

    private fun String.isUsableKey(): Boolean =
        isNotBlank() && !contains('/') && this != SEGMENT_HOUSEHOLD
}

/**
 * The line the shelf shows when it has been narrowed to something the drawer chips cannot say, or
 * null when they can.
 *
 * A chip per app is a filter somebody can see the whole of; a chip per *record* would be the folders
 * this app refuses to have. So a link to one asset's documents, or to one document, says so in a
 * sentence — and the sentence exists mainly so that leaving it is obvious. A household that followed
 * a link and then cannot work out how to see the rest of its paperwork has been handed a folder
 * after all.
 *
 * [documents] is the whole shelf, not the narrowed list: the line has to read the same whether or
 * not somebody has since typed in the search box.
 */
fun RepositoryDestination.describe(documents: List<DocumentFacts>): String? = when (this) {
    // The chips already say both of these, and saying it twice is a line of screen a phone does not
    // have.
    is RepositoryDestination.Shelf, is RepositoryDestination.Drawer -> null

    is RepositoryDestination.Record -> {
        val on = Shelf.on(documents, appKey, recordKey)
        // The label the owning app last supplied. A record whose documents have all gone takes its
        // name with them — there is nothing left here that knows what `a3f2` was called, and
        // guessing is the one thing this module never does.
        val label = on.firstOrNull()?.owner?.label?.takeIf { it.isNotBlank() }
        listOfNotNull(label, Shelf.headline(on)).joinToString(" · ")
    }

    is RepositoryDestination.Document ->
        documents.firstOrNull { it.id == documentId && it.sourceKey == sourceKey }
            ?.let { "Showing “${it.title}”" }
            ?: "That document isn't on the shelf any more"
}
