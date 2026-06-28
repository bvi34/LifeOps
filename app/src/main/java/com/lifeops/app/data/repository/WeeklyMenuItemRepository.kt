package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FoodLogDao
import com.lifeops.app.data.db.dao.WeeklyMenuItemDao
import com.lifeops.app.data.model.FoodLogEntry
import com.lifeops.app.data.model.FoodLogSource
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.WeeklyMenuItem
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private const val ASSIGNED_MEAL_HOUR = 12

/** Meals committed to a week (floating, or pinned once assigned to a day). Assigning a day
 *  spawns or moves an unconfirmed [FoodLogEntry] so the Daily Plan tab has something to
 *  confirm — unassigning removes that placeholder but never touches an entry the user already
 *  confirmed or adjusted, since that's real diary history by then. */
class WeeklyMenuItemRepository(
    private val weeklyMenuItemDao: WeeklyMenuItemDao,
    private val foodLogDao: FoodLogDao,
    private val recipeRepository: RecipeRepository
) {
    fun observeForWeek(weekStartDate: String): Flow<List<WeeklyMenuItem>> =
        weeklyMenuItemDao.observeByWeek(weekStartDate).map { items -> items.map { it.toModel() } }

    suspend fun createMenuItem(
        weekStartDate: String,
        mealName: String,
        recipeId: String? = null,
        plannedServings: Double = 1.0,
        mealType: String? = null
    ): WeeklyMenuItem {
        val item = WeeklyMenuItem(
            id = UUID.randomUUID().toString(),
            weekStartDate = weekStartDate,
            recipeId = recipeId,
            mealName = mealName,
            plannedServings = plannedServings,
            assignedDate = null,
            mealType = mealType,
            createdAt = DateUtil.now()
        )
        weeklyMenuItemDao.upsert(item.toEntity())
        return item
    }

    /** Assigns [menuItem] to [date], creating its placeholder log entry on first assignment
     *  and simply moving it on subsequent re-assignments. */
    suspend fun assignToDate(menuItemId: String, date: String): WeeklyMenuItem? {
        val entity = weeklyMenuItemDao.getById(menuItemId) ?: return null
        val updated = entity.copy(assignedDate = date)
        weeklyMenuItemDao.upsert(updated)

        val loggedAt = DateUtil.isoFromEpoch(DateUtil.epochMillisForDate(date, ASSIGNED_MEAL_HOUR))
        val existing = foodLogDao.getByWeeklyMenuItemId(menuItemId)
        if (existing != null) {
            if (!existing.confirmed) {
                foodLogDao.update(existing.copy(loggedAt = loggedAt))
            }
        } else {
            foodLogDao.insert(buildPlaceholderEntry(updated.toModel(), loggedAt).toEntity())
        }
        return updated.toModel()
    }

    /** Clears the day assignment and removes the placeholder entry — unless it was already
     *  confirmed or adjusted, in which case it's real history and stays untouched. */
    suspend fun unassign(menuItemId: String): WeeklyMenuItem? {
        val entity = weeklyMenuItemDao.getById(menuItemId) ?: return null
        val updated = entity.copy(assignedDate = null)
        weeklyMenuItemDao.upsert(updated)

        foodLogDao.getByWeeklyMenuItemId(menuItemId)?.let { existing ->
            if (!existing.confirmed) foodLogDao.delete(existing.id)
        }
        return updated.toModel()
    }

    suspend fun delete(menuItemId: String) {
        foodLogDao.getByWeeklyMenuItemId(menuItemId)?.let { existing ->
            if (!existing.confirmed) foodLogDao.delete(existing.id)
        }
        weeklyMenuItemDao.delete(menuItemId)
    }

    private suspend fun buildPlaceholderEntry(item: WeeklyMenuItem, loggedAt: String): FoodLogEntry {
        val nutrition = item.recipeId?.let { recipeRepository.getNutrition(it) }
            ?.let { it.perServing * item.plannedServings }
        return FoodLogEntry(
            id = UUID.randomUUID().toString(),
            foodItemId = null,
            name = item.mealName,
            quantity = item.plannedServings,
            unit = IngredientUnit.SERVING,
            calories = nutrition?.calories ?: 0.0,
            carbsG = nutrition?.carbsG ?: 0.0,
            proteinG = nutrition?.proteinG ?: 0.0,
            fatG = nutrition?.fatG ?: 0.0,
            loggedAt = loggedAt,
            source = FoodLogSource.PLANNED,
            confirmed = false,
            confirmedAt = null,
            weeklyMenuItemId = item.id
        )
    }
}
