package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.CostService

/**
 * Cost-tracking routes: `local/costResource` (budgets) and `local/cost` (per-task entries).
 *
 *  - `costResource/create`  — params: name (required), resetCycle (default "monthly"), capacity (int).
 *  - `costResource/archive` — params: id (required), active is set to !archived (default archived true).
 *  - `cost/log`             — params: taskId (required), resourceId (required), amount (int, required), note.
 *  - `cost/delete`          — params: id (required).
 */
object LocalCostConnection {
    fun register(registry: ConnectionRegistry, costService: CostService) {
        registry.register("local", "costResource", "create") { request ->
            val p = request.params
            costService.createResource(
                name = p.requireString("name"),
                resetCycle = p.getString("resetCycle") ?: "monthly",
                capacity = p.getInt("capacity")
            )
            ConnectionResult.ok()
        }

        registry.register("local", "costResource", "archive") { request ->
            val p = request.params
            val id = p.requireString("id")
            val archived = if (p.has("archived")) p.getBoolean("archived") else true
            costService.setResourceActive(id, !archived)
            ConnectionResult.ok("id" to id, "active" to !archived)
        }

        registry.register("local", "cost", "log") { request ->
            val p = request.params
            costService.logCost(
                taskId = p.requireString("taskId"),
                resourceId = p.requireString("resourceId"),
                amount = p.requireInt("amount"),
                note = p.getString("note")
            )
            ConnectionResult.ok()
        }

        registry.register("local", "cost", "delete") { request ->
            val id = request.params.requireString("id")
            costService.deleteEntry(id)
            ConnectionResult.ok("id" to id)
        }
    }
}
