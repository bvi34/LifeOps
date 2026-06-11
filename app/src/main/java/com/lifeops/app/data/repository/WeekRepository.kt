package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.WeekDao
import com.lifeops.app.data.db.dao.WeekSnapshotDao
import com.lifeops.app.data.db.entities.WeekSnapshotEntity
import com.lifeops.app.data.model.Week
import com.lifeops.app.data.model.WeekSnapshot
import com.lifeops.app.util.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WeekRepository(
    private val weekDao: WeekDao,
    private val weekSnapshotDao: WeekSnapshotDao
) {
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Int>>() {}.type
    private val weekCreationMutex = Mutex()

    fun observeCurrentWeek(): Flow<Week?> =
        weekDao.observeCurrentWeek().map { it?.toModel() }

    fun observeAllWeeks(): Flow<List<Week>> =
        weekDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeSnapshots(): Flow<List<WeekSnapshot>> =
        weekSnapshotDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeSnapshotsSince(since: String): Flow<List<WeekSnapshot>> =
        weekSnapshotDao.observeSince(since).map { list -> list.map { it.toModel() } }

    suspend fun getCurrentWeek(): Week? = weekDao.getCurrentWeek()?.toModel()

    suspend fun getOrCreateCurrentWeek(): Week = weekCreationMutex.withLock {
        val current = weekDao.getCurrentWeek()
        if (current != null) return@withLock current.toModel()
        val start = DateUtil.currentWeekStart()
        val end = DateUtil.currentWeekEnd()
        val week = Week(
            id = java.util.UUID.randomUUID().toString(),
            startDate = start.toString(),
            endDate = end.toString()
        )
        weekDao.upsert(week.toEntity())
        week
    }

    suspend fun upsertWeek(week: Week) = weekDao.upsert(week.toEntity())

    suspend fun getMostRecentClosedWeek(): Week? = weekDao.getMostRecentClosedWeek()?.toModel()

    suspend fun createNextWeek(currentWeek: Week): Week = weekCreationMutex.withLock {
        // If a new open week already exists (e.g. repair ran already), return it
        val existing = weekDao.getCurrentWeek()
        if (existing != null && existing.id != currentWeek.id) return@withLock existing.toModel()
        // Parse current week end date and add 1 day to get next week start
        val nextStart = LocalDate.parse(currentWeek.endDate).plusDays(1)
        val nextEnd = nextStart.plusDays(6)
        val week = Week(
            id = java.util.UUID.randomUUID().toString(),
            startDate = nextStart.toString(),
            endDate = nextEnd.toString()
        )
        weekDao.upsert(week.toEntity())
        week
    }

    suspend fun getAllWeeksSync(): List<Week> = weekDao.getAllSync().map { it.toModel() }

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
        categoryTotalBreakdown = gson.fromJson(categoryTotalBreakdown, mapType) ?: emptyMap(),
        hardDeadlineCompletedCount = hardDeadlineCompletedCount,
        hardDeadlineExpiredCount = hardDeadlineExpiredCount,
        createdAt = createdAt
    )
}
