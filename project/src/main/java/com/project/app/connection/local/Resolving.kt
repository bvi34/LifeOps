package com.project.app.connection.local

import com.lifeops.app.connection.ConnectionError
import com.lifeops.app.connection.ConnectionResult
import com.project.app.data.model.Project
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.ProjectLookup

/**
 * The one thing every route here has to do first: work out which project the caller meant.
 *
 * Shared rather than repeated because getting it wrong writes into somebody else's work, and six
 * copies of a resolution rule is six chances for one of them to start guessing. The rule itself is
 * pure and lives in `logic/ProjectLookup`; this is the part that reads the database and turns the
 * three possible answers into the three the connection scheme already has words for.
 */
internal suspend fun ProjectRepository.resolveProject(reference: String): Resolved {
    val projects = allProjectsForLookup()
    return when (val match = ProjectLookup.resolve(reference, projects, { it.id }, { it.name })) {
        is ProjectLookup.Match.Found -> Resolved.Ok(match.value)

        is ProjectLookup.Match.None -> Resolved.Problem(
            ConnectionResult.fail(ConnectionError.NOT_FOUND, "No project called '$reference'")
        )

        // Named rather than picked. A caller told which two can ask again with an id; one told
        // nothing writes into whichever row happened to come first.
        is ProjectLookup.Match.Ambiguous -> Resolved.Problem(
            ConnectionResult.fail(
                ConnectionError.INVALID_PARAMS,
                "More than one project is called '$reference' (${match.names.joinToString(", ")}). " +
                    "Use its id."
            )
        )
    }
}

internal sealed interface Resolved {
    data class Ok(val project: Project) : Resolved
    data class Problem(val failure: ConnectionResult.Failure) : Resolved
}
