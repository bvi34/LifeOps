package com.project.app.connection

import android.util.Log
import com.operations.connectkit.ConnectionDispatcher
import com.operations.connectkit.ConnectionRegistry
import com.project.app.connection.local.LocalCardConnection
import com.project.app.connection.local.LocalDocConnection
import com.project.app.connection.local.LocalLoreConnection
import com.project.app.connection.local.LocalOutlineConnection
import com.project.app.connection.local.LocalProjectConnection
import com.project.app.connection.local.LocalTimelineConnection
import com.project.app.data.repository.ProjectRepository

/**
 * Composition root for Project's connection layer — the suite's second one.
 *
 * The addressing scheme reserved its `application` segment from the start "so a future multi-app
 * surface can address peers without ambiguity". This is that surface: Project serves
 * `/v1/Project/local/…` from its own dispatcher, built on the same machinery LifeOps uses rather
 * than a second convention invented over here. See `docs/CONNECTIONS.md`.
 *
 * **Why Project and not some other app first.** Every structural edit here is already a pure
 * function that returns the rows that changed, applied by one repository — so a route is a genuinely
 * thin adapter over a use case that exists, rather than a second path into the data with its own
 * quietly different rules. That is the property that makes routes safe, and it is why there is no
 * service layer between the handlers and the repository: LifeOps needs one because its task
 * lifecycle carries policy no repository owns; here the repository *is* that layer, and a second one
 * would be a second place for the rules to live.
 */
object ProjectConnections {

    /** The `application` segment Project answers for. */
    const val APPLICATION = "Project"

    fun buildDispatcher(repository: ProjectRepository): ConnectionDispatcher {
        val registry = ConnectionRegistry()
        LocalProjectConnection.register(registry, repository)
        LocalOutlineConnection.register(registry, repository)
        LocalDocConnection.register(registry, repository)
        LocalLoreConnection.register(registry, repository)
        LocalTimelineConnection.register(registry, repository)
        LocalCardConnection.register(registry, repository)
        return ConnectionDispatcher(registry, APPLICATION) { address, error ->
            Log.e("ProjectConnections", "Handler failed for $address", error)
        }
    }
}
