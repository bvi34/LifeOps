package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.WeekDao
import com.lifeops.app.data.db.dao.WeekSnapshotDao
import com.lifeops.app.data.db.entities.WeekSnapshotEntity
import com.lifeops.app.data.model.Week
import com.lifeops.app.data.model.WeekSnapshot
import com.lifeops.app.util.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WeekRepository(
    private val weekDao: WeekDao,
    private val weekSnapshotDao: WeekSnapshotDao
) {
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Int>>() {}.type

    fun observeCurrentWeek(): Flow<Week?> =
        weekDao.observeCurrentWeek().map { it?.toModel() }

    fun observeAllWeeks(): Flow<List<Week>> =
        weekDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeSnapshots(): Flow<List<WeekSnapshot>> =
        weekSnapshotDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeSnapshotsSince(since: String): Flow<List<WeekSnapshot>> =
        weekSnapshotDao.observeSince(since).map { list -> list.map { it.toModel() } }

    suspend fun getCurrentWeek(): Week? = weekDao.getCurrentWeek()?.toModel()

    suspend fun getOrCreateCurrentWeek(): Week {
        val current = weekDao.getCurrentWeek()
        if (current != null) return current.toModel()
        val start = DateUtil.currentWeekStart()
        val end = DateUtil.currentWeekEnd()
        val week = Week(
            id = java.util.UUID.randomUUID().toString(),
            startDate = start.toString(),
            endDate = end.toString()
        )
        weekDao.upsert(week.toEntity())
        return week
    }

    suspend fun upsertWeek(week: Week) = weekDao.upsert(week.toEntity())

    private fun WeekSnapshotEntity.toModel(): WeekSnapshot = WeekSnapshot(
        id = id,
        weekId = weekId,
        completedCount = completedCount,
        incompleteCount = incompleteCount,
        expiredCount = expiredCount,
        skippedCount = skippedCount,
        carriedForwardCount = carriedForwardCount,
        totalResourcesEarned = totalResourcesEarned,
        aspectBreakdown = gson.fromJson(aspectBreakdown, mapType) ?: emptyMap(),
        categoryBreakdown = gson.fromJson(categoryBreakdown, mapType) ?: emptyMap(),
        categorySlipBreakdown = gson.fromJson(categorySlipBreakdown, mapType) ?: emptyMap(),
        hardDeadlineCompletedCount = hardDeadlineCompletedCount,
        hardDeadlineExpiredCount = hardDeadlineExpiredCount,
        createdAt = createdAt
    )
}
