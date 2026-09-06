package com.project.app.logic

/**
 * Somewhere in Project worth opening at.
 *
 * Until this existed the only thing anybody outside could do was *start* Project, which meant that
 * Advisor could quote a scene and never take you to it, and that the app's own "reopen where I left
 * off" was a special case written into the nav graph. Both are the same question — open Project at
 * X — so both go through here.
 *
 * The ids are the app's own row ids. A destination naming a row that has since been deleted is not
 * an error and not a crash: it is resolved against the database first (see
 * `ProjectRepository.resolve`) and falls back to the shelf, because landing somebody on a workspace
 * for something that no longer exists is worse than landing them on the shelf.
 */
sealed interface ProjectDestination {

    /** The shelf itself — used to say "open Project, and do not reopen what I had last time". */
    data object Shelf : ProjectDestination

    /**
     * One project, optionally on a named section.
     *
     * [section] is a [SearchSection] rather than the UI's own `ProjectSection`, which carries an
     * icon and therefore cannot live down here. The UI already converts between the two for search
     * results, so a destination costs no new plumbing.
     */
    data class Workspace(val projectId: String, val section: SearchSection? = null) : ProjectDestination

    /** One document, open in the editor, with its project underneath it on the back stack. */
    data class Document(val projectId: String, val docId: String) : ProjectDestination
}

/**
 * Destinations as text, for putting in an intent.
 *
 * The addresses are deliberately the same shape as the app's own nav routes — `project/<id>`,
 * `project/<id>/doc/<id>` — so there is one vocabulary for "where in Project" rather than a private
 * one for callers and another for the nav graph.
 *
 * Everything here is pure and total: nothing throws, and anything unrecognised parses to `null`,
 * which every caller reads as "just open the app normally". A deep link is a hint from somewhere
 * else, and a hint that has gone stale must not be able to stop the app opening.
 */
object ProjectLinks {

    private const val SEGMENT_PROJECT = "project"
    private const val SEGMENT_DOC = "doc"
    private const val SEGMENT_SHELF = "shelf"

    /**
     * A destination as an address, or `null` if it cannot be written down unambiguously.
     *
     * The one way that happens is an id containing a `/`, which would split into extra segments and
     * parse back as something else. The app's ids are UUIDs so it should not arise — but silently
     * producing an address that means something different from what was asked for is exactly the
     * bug this returns `null` to avoid, and a caller that gets `null` opens the app plainly.
     */
    fun format(destination: ProjectDestination): String? = when (destination) {
        is ProjectDestination.Shelf -> SEGMENT_SHELF

        is ProjectDestination.Workspace -> {
            val id = destination.projectId.takeIf { it.isUsableId() }
            when {
                id == null -> null
                destination.section == null -> "$SEGMENT_PROJECT/$id"
                else -> "$SEGMENT_PROJECT/$id/${destination.section.key}"
            }
        }

        is ProjectDestination.Document -> {
            val projectId = destination.projectId.takeIf { it.isUsableId() }
            val docId = destination.docId.takeIf { it.isUsableId() }
            if (projectId == null || docId == null) null
            else "$SEGMENT_PROJECT/$projectId/$SEGMENT_DOC/$docId"
        }
    }

    /** An address back into a destination, or `null` for anything this app does not recognise. */
    fun parse(address: String?): ProjectDestination? {
        val segments = address?.trim()?.split("/")?.filter { it.isNotBlank() } ?: return null

        return when {
            segments.size == 1 && segments[0] == SEGMENT_SHELF -> ProjectDestination.Shelf

            segments.size == 2 && segments[0] == SEGMENT_PROJECT ->
                ProjectDestination.Workspace(segments[1])

            segments.size == 3 && segments[0] == SEGMENT_PROJECT ->
                // A third segment is a section name. An unknown one is *not* read as "the outline":
                // a caller that misspells a section is a caller with a bug, and quietly landing
                // somewhere plausible is how that bug survives to ship.
                SearchSection.entries.firstOrNull { it.key == segments[2] }
                    ?.let { ProjectDestination.Workspace(segments[1], it) }

            segments.size == 4 && segments[0] == SEGMENT_PROJECT && segments[2] == SEGMENT_DOC ->
                ProjectDestination.Document(segments[1], segments[3])

            else -> null
        }
    }

    private fun String.isUsableId(): Boolean = isNotBlank() && !contains('/')
}
