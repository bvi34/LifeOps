package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionError
import com.lifeops.app.connection.ConnectionParams
import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.FoodService
import com.lifeops.app.connection.service.RecipeService
import com.lifeops.app.data.model.IngredientUnit

/**
 * Nutrition routes: `local/food` (the food database + diary) and `local/recipe`.
 *
 *  - `food/createCustom` — params: name (required), brand, servingSize (double, required),
 *    servingUnit (required), servingSizeGrams, calories/carbsG/proteinG/fatG (double, required),
 *    fiberG, sodiumMg.
 *  - `food/log`      — params: foodItemId (required), quantity (double, required), unit (gram|serving).
 *  - `food/logAdHoc` — params: name (required), quantity (double, required), unit,
 *    calories/carbsG/proteinG/fatG (double, required).
 *  - `food/confirm`  — params: entryId (required).
 *  - `food/adjust`   — params: entryId (required), quantity (double, required), unit, foodItemId (swap).
 *  - `food/promote`  — params: entryId (required). Promote an ad-hoc entry to a custom food.
 *  - `recipe/create` — params: name (required), servings (double, default 1).
 *  - `recipe/delete` — params: id (required).
 *  - `recipe/addIngredient`    — params: recipeId (required), foodItemId (required), quantity
 *    (double, required), unit.
 *  - `recipe/removeIngredient` — params: id (required).
 */
object LocalFoodConnection {

    private fun unitOf(p: ConnectionParams): IngredientUnit =
        IngredientUnit.from((p.getString("unit") ?: "serving").uppercase())

    fun register(registry: ConnectionRegistry, foodService: FoodService, recipeService: RecipeService) {
        // --- food database + diary ---
        registry.register("local", "food", "createCustom") { request ->
            val p = request.params
            val item = foodService.createCustomFood(
                FoodService.CustomFoodInput(
                    name = p.requireString("name"),
                    brand = p.getString("brand"),
                    servingSize = p.requireDouble("servingSize"),
                    servingUnit = p.requireString("servingUnit"),
                    servingSizeGrams = p.getDouble("servingSizeGrams"),
                    calories = p.requireDouble("calories"),
                    carbsG = p.requireDouble("carbsG"),
                    proteinG = p.requireDouble("proteinG"),
                    fatG = p.requireDouble("fatG"),
                    fiberG = p.getDouble("fiberG"),
                    sodiumMg = p.getDouble("sodiumMg")
                )
            )
            ConnectionResult.ok("id" to item.id)
        }

        registry.register("local", "food", "log") { request ->
            val p = request.params
            val entry = foodService.logFood(p.requireString("foodItemId"), p.requireDouble("quantity"), unitOf(p))
            entry?.let { ConnectionResult.ok("id" to it.id) }
                ?: ConnectionResult.fail(ConnectionError.NOT_FOUND, "Unknown food item or unit mismatch")
        }

        registry.register("local", "food", "logAdHoc") { request ->
            val p = request.params
            val entry = foodService.logAdHoc(
                name = p.requireString("name"),
                quantity = p.requireDouble("quantity"),
                unit = unitOf(p),
                calories = p.requireDouble("calories"),
                carbsG = p.requireDouble("carbsG"),
                proteinG = p.requireDouble("proteinG"),
                fatG = p.requireDouble("fatG")
            )
            ConnectionResult.ok("id" to entry.id)
        }

        registry.register("local", "food", "confirm") { request ->
            val id = request.params.requireString("entryId")
            foodService.confirmEntry(id)?.let { ConnectionResult.ok("id" to it.id) }
                ?: ConnectionResult.fail(ConnectionError.NOT_FOUND, "No log entry with id '$id'")
        }

        registry.register("local", "food", "adjust") { request ->
            val p = request.params
            val id = p.requireString("entryId")
            foodService.adjustEntry(id, p.requireDouble("quantity"), unitOf(p), p.getString("foodItemId"))
                ?.let { ConnectionResult.ok("id" to it.id) }
                ?: ConnectionResult.fail(ConnectionError.NOT_FOUND, "No log entry with id '$id'")
        }

        registry.register("local", "food", "promote") { request ->
            val id = request.params.requireString("entryId")
            foodService.promoteToCustomFood(id)?.let { ConnectionResult.ok("foodItemId" to it.id) }
                ?: ConnectionResult.fail(ConnectionError.NOT_FOUND, "No log entry with id '$id'")
        }

        // --- recipes ---
        registry.register("local", "recipe", "create") { request ->
            val p = request.params
            val recipe = recipeService.create(p.requireString("name"), p.getDouble("servings") ?: 1.0)
            ConnectionResult.ok("id" to recipe.id)
        }

        registry.register("local", "recipe", "delete") { request ->
            val id = request.params.requireString("id")
            recipeService.delete(id)
            ConnectionResult.ok("id" to id)
        }

        registry.register("local", "recipe", "addIngredient") { request ->
            val p = request.params
            val ingredient = recipeService.addIngredient(
                recipeId = p.requireString("recipeId"),
                foodItemId = p.requireString("foodItemId"),
                quantity = p.requireDouble("quantity"),
                unit = unitOf(p)
            )
            ConnectionResult.ok("id" to ingredient.id)
        }

        registry.register("local", "recipe", "removeIngredient") { request ->
            val id = request.params.requireString("id")
            recipeService.removeIngredient(id)
            ConnectionResult.ok("id" to id)
        }
    }
}
