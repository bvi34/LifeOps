package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.WellnessService

/**
 * The `local/wellness/*` routes.
 *
 *  - `checkin` — params: energy (int, required), sensory (int, required), note, at (epoch millis).
 *  - `sleep`   — params: energy (int, required), tired (int, required), sleepMinutes (int), note,
 *    at (epoch millis).
 */
object LocalWellnessConnection {
    fun register(registry: ConnectionRegistry, wellnessService: WellnessService) {
        registry.register("local", "wellness", "checkin") { request ->
            val p = request.params
            wellnessService.checkin(
                energy = p.requireInt("energy"),
                sensory = p.requireInt("sensory"),
                note = p.getString("note"),
                at = p.getLong("at")
            )
            ConnectionResult.ok()
        }

        registry.register("local", "wellness", "sleep") { request ->
            val p = request.params
            wellnessService.sleep(
                energy = p.requireInt("energy"),
                tired = p.requireInt("tired"),
                sleepMinutes = p.getInt("sleepMinutes"),
                note = p.getString("note"),
                at = p.getLong("at")
            )
            ConnectionResult.ok()
        }
    }
}
