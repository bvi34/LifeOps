package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionError
import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.CounterService

/**
 * The `local/counter` routes.
 *
 *  - `/v1/LifeOps/local/counter/create`  — params: name (required), categoryId, isHabit,
 *    reminderHour. Returns the new counter id.
 *  - `/v1/LifeOps/local/counter/log`     — params: id (required), delta (int, default 1), note,
 *    occurredAt (epoch millis, default now).
 *  - `/v1/LifeOps/local/counter/archive` — params: id (required), archived (bool, default true).
 *  - `/v1/LifeOps/local/counter/update`  — params: id (required) + any of name/categoryId/
 *    isHabit/reminderHour to change.
 */
object LocalCounterConnection {

    private const val CONNECTION = "local"
    private const val RESOURCE = "counter"

    fun register(registry: ConnectionRegistry, counterService: CounterService) {
        registry.register(CONNECTION, RESOURCE, "create") { request ->
            val p = request.params
            val counter = counterService.create(
                name = p.requireString("name"),
                categoryId = p.getString("categoryId"),
                isHabit = p.getBoolean("isHabit"),
                reminderHour = p.getInt("reminderHour")
            )
            ConnectionResult.ok("id" to counter.id)
        }

        registry.register(CONNECTION, RESOURCE, "log") { request ->
            val p = request.params
            val id = p.requireString("id")
            val logged = counterService.log(
                counterId = id,
                delta = p.getInt("delta") ?: 1,
                note = p.getString("note"),
                occurredAt = p.getLong("occurredAt")
            )
            if (logged) ConnectionResult.ok("id" to id) else notFound(id)
        }

        registry.register(CONNECTION, RESOURCE, "archive") { request ->
            val p = request.params
            val id = p.requireString("id")
            val archived = if (p.has("archived")) p.getBoolean("archived") else true
            if (counterService.setArchived(id, archived)) ConnectionResult.ok("id" to id, "archived" to archived)
            else notFound(id)
        }

        registry.register(CONNECTION, RESOURCE, "update") { request ->
            val p = request.params
            val id = p.requireString("id")
            counterService.update(
                counterId = id,
                name = p.getString("name"),
                categoryId = p.getString("categoryId"),
                isHabit = if (p.has("isHabit")) p.getBoolean("isHabit") else null,
                reminderHour = p.getInt("reminderHour")
            )?.let { ConnectionResult.ok("id" to it.id) } ?: notFound(id)
        }
    }

    private fun notFound(id: String): ConnectionResult =
        ConnectionResult.fail(ConnectionError.NOT_FOUND, "No counter with id '$id'")
}
