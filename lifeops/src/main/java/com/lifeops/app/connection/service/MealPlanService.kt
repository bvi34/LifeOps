package com.lifeops.app.connection.service

import com.lifeops.app.data.model.WeeklyMenuItem
import com.lifeops.app.data.repository.FoodLogRepository
import com.lifeops.app.data.repository.RecipeRepository
import com.lifeops.app.data.repository.WeeklyMenuRepository
import com.lifeops.app.util.DateUtil
import java.time.LocalDate

/**
 * Planning a meal onto a day — the step that makes the recipe book matter to the week rather than
 * being a reference shelf beside it.
 *
 * One plan writes two rows: the [WeeklyMenuItem] itself, and the **planned (unconfirmed) diary
 * entry** it stands for, carrying the recipe's macros for the servings planned. The Daily Plan
 * screen has always drawn planned-vs-confirmed totals and offered Confirm/Adjust; nothing ever
 * wrote the planned side, so those affordances could not fire. This is that missing writer, and it
 * is the only one — the routes and the screen both come through here.
 */
class MealPlanService(
    private val menuRepository: WeeklyMenuRepository,
    private val recipeRepository: RecipeRepository,
    private val foodLogRepository: FoodLogRepository
) {

    /**
     * Commits [servings] of a meal to [date]. With a [recipeId] the name and macros come from the
     * recipe; without one it's a freeform meal ("leftovers") that still holds a slot in the day but
     * carries no macros to confirm. Returns the menu item.
     */
    suspend fun plan(
        date: String,
        recipeId: String? = null,
        mealName: String? = null,
        servings: Double = 1.0,
        mealType: String? = null
    ): WeeklyMenuItem {
        require(DateUtil.isValidDate(date)) { "Meal date must be yyyy-MM-dd" }
        val portions = servings.coerceAtLeast(0.0)
        val recipe = recipeId?.let { recipeRepository.getById(it) }
        require(recipe != null || !mealName.isNullOrBlank()) {
            "A planned meal needs either a known recipe or a name"
        }
        val name = mealName?.trim()?.takeIf { it.isNotBlank() } ?: recipe!!.name

        val item = menuRepository.add(
            weekStartDate = DateUtil.weekStartFor(LocalDate.parse(date)).toString(),
            mealName = name,
            recipeId = recipe?.id,
            plannedServings = portions,
            assignedDate = date,
            mealType = mealType
        )

        // Only a recipe can say what a meal is made of; a freeform slot has nothing to plan macros
        // from, so it stays a menu line until it's logged by hand.
        val perServing = recipe?.let { recipeRepository.getNutrition(it.id)?.perServing }
        if (perServing != null) {
            foodLogRepository.logPlannedMeal(
                name = name,
                servings = portions,
                totals = perServing * portions,
                loggedAt = DateUtil.isoFromEpoch(DateUtil.epochMillisForDate(date, hourFor(mealType))),
                weeklyMenuItemId = item.id
            )
        }
        return item
    }

    /**
     * Takes a planned meal back off the day. Its diary entry goes with it *unless* it has already
     * been confirmed or adjusted — at that point the entry records what was actually eaten and
     * outlives the plan that suggested it.
     */
    suspend fun unplan(menuItemId: String): Boolean {
        menuRepository.getById(menuItemId) ?: return false
        foodLogRepository.deletePlannedEntryFor(menuItemId)
        menuRepository.delete(menuItemId)
        return true
    }

    /** Where in the day a meal lands, so the diary reads in the order it was eaten. */
    private fun hourFor(mealType: String?): Int = when (mealType?.trim()?.lowercase()) {
        "breakfast" -> 8
        "lunch" -> 12
        "snack" -> 15
        "dinner" -> 18
        else -> 12
    }
}
