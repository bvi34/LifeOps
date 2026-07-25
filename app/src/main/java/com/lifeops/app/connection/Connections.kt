package com.lifeops.app.connection

import android.util.Log
import com.lifeops.app.connection.local.LocalTaskConnection
import com.lifeops.app.connection.service.TaskService

/**
 * Composition root for the connection layer. Builds the registry, wires every connection's routes
 * onto it, and returns a ready dispatcher. New connections (internal resources under `local`, or
 * future named integrations) are registered here.
 */
object Connections {

    fun buildDispatcher(taskService: TaskService): ConnectionDispatcher {
        val registry = ConnectionRegistry()
        // --- local: internal app comms ---
        LocalTaskConnection.register(registry, taskService)
        // Future: LocalWeekConnection.register(registry, weekService), etc.
        // Future named connections (integrations) register their own connection namespace here.
        return ConnectionDispatcher(registry) { address, error ->
            Log.e("ConnectionDispatcher", "Handler failed for $address", error)
        }
    }
}
