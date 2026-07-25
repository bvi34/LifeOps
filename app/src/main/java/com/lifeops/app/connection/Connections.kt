package com.lifeops.app.connection

import android.util.Log
import com.lifeops.app.connection.local.LocalCounterConnection
import com.lifeops.app.connection.local.LocalProjectConnection
import com.lifeops.app.connection.local.LocalTaskConnection
import com.lifeops.app.connection.local.LocalWeekConnection
import com.lifeops.app.connection.service.CounterService
import com.lifeops.app.connection.service.ProjectService
import com.lifeops.app.connection.service.TaskService
import com.lifeops.app.connection.service.WeekService

/**
 * Composition root for the connection layer. Builds the registry, wires every connection's routes
 * onto it, and returns a ready dispatcher. New connections (internal resources under `local`, or
 * future named integrations) are registered here.
 */
object Connections {

    /** Services the connection layer dispatches into, gathered so [buildDispatcher] stays stable
     * as more are added. */
    data class Services(
        val task: TaskService,
        val week: WeekService,
        val project: ProjectService,
        val counter: CounterService
    )

    fun buildDispatcher(services: Services): ConnectionDispatcher {
        val registry = ConnectionRegistry()
        // --- local: internal app comms ---
        LocalTaskConnection.register(registry, services.task)
        LocalWeekConnection.register(registry, services.week)
        LocalProjectConnection.register(registry, services.project)
        LocalCounterConnection.register(registry, services.counter)
        // Future named connections (integrations) register their own connection namespace here.
        return ConnectionDispatcher(registry) { address, error ->
            Log.e("ConnectionDispatcher", "Handler failed for $address", error)
        }
    }
}
