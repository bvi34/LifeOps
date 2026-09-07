package com.project.app.connection.local

import com.operations.connectkit.NameLookup
import com.operations.connectkit.Resolution
import com.operations.connectkit.orProblem
import com.project.app.data.model.Project
import com.project.app.data.repository.ProjectRepository

/**
 * The one thing every route here has to do first: work out which project the caller meant.
 *
 * The rule itself — id first, then an exact name, and an ambiguous name resolving to *neither* —
 * is `NameLookup` in `:connectkit`, shared with every other app that serves routes, because getting
 * it wrong writes into somebody else's work and a per-app copy is a per-app chance to start
 * guessing. What is left here is the part that reads this app's database and says what the thing is
 * called in a sentence.
 */
internal suspend fun ProjectRepository.resolveProject(reference: String): Resolution<Project> =
    NameLookup.resolve(reference, allProjectsForLookup(), { it.id }, { it.name })
        .orProblem(noun = "project", reference = reference)
