package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.WeeklyMenuItemDao
import com.lifeops.app.data.model.WeeklyMenuItem
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The week's menu: meals committed to a week, optionally pinned to a day. Persistence only — the
 * policy that turns a planned meal into a *planned diary entry* (and takes it back out again) lives
 * in `MealPlanService`, so both the Daily Plan screen and the `local/menu` routes go the same way.
 */
class WeeklyMenuRepository(private val menuDao: WeeklyMenuItemDao) {

    fun observeWeek(weekStartDate: String): Flow<List<WeeklyMenuItem>> =
        menuDao.observeByWeek(weekStartDate).map { items -> items.map { it.toModel() } }

    suspend fun getById(id: String): WeeklyMenuItem? = menuDao.getById(id)?.toModel()

    suspend fun getForDate(date: String): List<WeeklyMenuItem> =
        menuDao.getByAssignedDate(date).map { it.toModel() }

    suspend fun add(
        weekStartDate: String,
        mealName: String,
        recipeId: String? = null,
        plannedServings: Double = 1.0,
        assignedDate: String? = null,
        mealType: String? = null
    ): WeeklyMenuItem {
        val item = WeeklyMenuItem(
            id = UUID.randomUUID().toString(),
            weekStartDate = weekStartDate,
            recipeId = recipeId,
            mealName = mealName,
            plannedServings = plannedServings,
            assignedDate = assignedDate,
            mealType = mealType,
            createdAt = DateUtil.now()
        )
        menuDao.upsert(item.toEntity())
        return item
    }

    suspend fun delete(id: String) = menuDao.delete(id)
}
