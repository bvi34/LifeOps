package com.lifeops.app.connection.local

import com.operations.connectkit.ConnectionRegistry
import com.operations.connectkit.ConnectionResult
import com.lifeops.app.connection.service.WellnessService
import com.lifeops.app.data.model.Initiative
import com.lifeops.app.data.model.SensoryTrend
import com.lifeops.app.data.model.WellnessTrend

/**
 * The `local/wellness` routes.
 *
 *  - `checkin` — params: trend ("BETTER"/"SAME"/"WORSE", required), initiative
 *    ("YES"/"NEUTRAL"/"NO", required), sensoryTrend ("BETTER"/"NEUTRAL"/"WORSE", optional — the
 *    dialog always asks, this route may skip it), energy (int, optional exact rating), sensory
 *    (int, optional), note, at (epoch millis). Omitting energy/sensory is the normal case: each is
 *    derived from its trend.
 *  - `sleep`   — params: energy (int, required), tired (int, required), sleepMinutes (int), note,
 *    at (epoch millis).
 */
object LocalWellnessConnection {
    fun register(registry: ConnectionRegistry, wellnessService: WellnessService) {
        registry.register("local", "wellness", "checkin") { request ->
            val p = request.params
            val trend = WellnessTrend.from(p.requireString("trend"))
                ?: throw IllegalArgumentException(
                    "trend must be one of ${WellnessTrend.entries.joinToString("/") { it.value }}"
                )
            val initiative = Initiative.from(p.requireString("initiative"))
                ?: throw IllegalArgumentException(
                    "initiative must be one of ${Initiative.entries.joinToString("/") { it.value }}"
                )
            val sensoryTrendRaw = p.getString("sensoryTrend")
            val sensoryTrend = sensoryTrendRaw?.let {
                SensoryTrend.from(it) ?: throw IllegalArgumentException(
                    "sensoryTrend must be one of ${SensoryTrend.entries.joinToString("/") { e -> e.value }}"
                )
            }
            wellnessService.checkin(
                trend = trend,
                initiative = initiative,
                sensoryTrend = sensoryTrend,
                energy = p.getInt("energy"),
                sensory = p.getInt("sensory"),
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
