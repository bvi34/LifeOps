package com.lifeops.app.connection.local

import com.operations.connectkit.ConnectionError
import com.operations.connectkit.ConnectionRegistry
import com.operations.connectkit.ConnectionResult
import com.lifeops.app.connection.service.MealPlanService

/**
 * The `local/menu` routes — the week's meal plan.
 *
 *  - `plan`   — params: date (required, yyyy-MM-dd), recipeId and/or mealName (one is required),
 *    servings (double, default 1), mealType (breakfast|lunch|snack|dinner).
 *    A recipe-backed plan also writes the day's *planned* diary entry.
 *  - `unplan` — params: id (required). Removes the meal and its still-unconfirmed diary entry;
 *    an entry already confirmed or adjusted is kept.
 */
object LocalMenuConnection {
    fun register(registry: ConnectionRegistry, service: MealPlanService) {
        registry.register("local", "menu", "plan") { request ->
            val p = request.params
            val item = service.plan(
                date = p.requireString("date"),
                recipeId = p.getString("recipeId"),
                mealName = p.getString("mealName"),
                servings = p.getDouble("servings") ?: 1.0,
                mealType = p.getString("mealType")
            )
            ConnectionResult.ok("id" to item.id)
        }

        registry.register("local", "menu", "unplan") { request ->
            val id = request.params.requireString("id")
            if (service.unplan(id)) ConnectionResult.ok("id" to id)
            else ConnectionResult.fail(ConnectionError.NOT_FOUND, "No planned meal with id '$id'")
        }
    }
}
