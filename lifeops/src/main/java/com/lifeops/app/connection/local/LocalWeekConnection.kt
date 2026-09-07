package com.lifeops.app.connection.local

import com.operations.connectkit.ConnectionError
import com.operations.connectkit.ConnectionRegistry
import com.operations.connectkit.ConnectionResult
import com.lifeops.app.connection.service.WeekService

/**
 * The `local/week` routes.
 *
 *  - `/v1/LifeOps/local/week/current` — resolve (creating if needed) the open week; returns
 *    `id`, `startDate`, `endDate`.
 *  - `/v1/LifeOps/local/week/close`   — close the open week and open the next; params: selfRating
 *    (optional int), selfRatingNote (optional string), mentalReset (optional bool — "was a mental
 *    reset achieved?"), exhaustion (optional int 1–10). Returns the closed and new week ids.
 */
object LocalWeekConnection {

    private const val CONNECTION = "local"
    private const val RESOURCE = "week"

    fun register(registry: ConnectionRegistry, weekService: WeekService) {
        registry.register(CONNECTION, RESOURCE, "current") {
            val week = weekService.getOrCreateCurrent()
            ConnectionResult.ok(
                "id" to week.id,
                "startDate" to week.startDate,
                "endDate" to week.endDate
            )
        }

        registry.register(CONNECTION, RESOURCE, "close") { request ->
            val p = request.params
            when (val outcome = weekService.close(
                selfRating = p.getInt("selfRating"),
                selfRatingNote = p.getString("selfRatingNote"),
                mentalReset = p.getBooleanOrNull("mentalReset"),
                exhaustion = p.getInt("exhaustion")
            )) {
                is WeekService.CloseOutcome.Closed ->
                    ConnectionResult.ok(
                        "closedWeekId" to outcome.closedWeek.id,
                        "newWeekId" to outcome.newWeek.id
                    )
                WeekService.CloseOutcome.NoOpenWeek ->
                    ConnectionResult.fail(ConnectionError.NOT_FOUND, "No open week to close")
            }
        }
    }
}
