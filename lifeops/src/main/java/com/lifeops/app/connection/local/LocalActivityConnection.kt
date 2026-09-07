package com.lifeops.app.connection.local

import com.operations.connectkit.ConnectionError
import com.operations.connectkit.ConnectionRegistry
import com.operations.connectkit.ConnectionResult
import com.lifeops.app.connection.service.ActivityService

/**
 * The `local/activity` routes (saved outdoor activities).
 *
 *  - `create` — params: name (required), outdoorPreferred (bool, default true), durationMinutes,
 *    maxTempF, minTempF, avoidRain (bool), maxWindMph.
 *  - `delete` — params: id (required).
 */
object LocalActivityConnection {
    fun register(registry: ConnectionRegistry, activityService: ActivityService) {
        registry.register("local", "activity", "create") { request ->
            val p = request.params
            val template = activityService.create(
                name = p.requireString("name"),
                outdoorPreferred = p.getBoolean("outdoorPreferred", default = true),
                durationMinutes = p.getInt("durationMinutes"),
                maxTempF = p.getInt("maxTempF"),
                minTempF = p.getInt("minTempF"),
                avoidRain = p.getBoolean("avoidRain"),
                maxWindMph = p.getInt("maxWindMph")
            )
            ConnectionResult.ok("id" to template.id)
        }

        registry.register("local", "activity", "delete") { request ->
            val id = request.params.requireString("id")
            if (activityService.delete(id)) ConnectionResult.ok("id" to id)
            else ConnectionResult.fail(ConnectionError.NOT_FOUND, "No activity with id '$id'")
        }
    }
}
