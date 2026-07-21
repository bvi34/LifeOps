package com.lifeops.app.data.repository

import android.content.Context
import com.lifeops.app.data.db.dao.WellnessCheckinDao
import com.lifeops.app.data.model.WellnessCheckin
import com.lifeops.app.data.model.WellnessKind
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ScreenTimeEstimator
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import com.lifeops.app.worker.WellnessCheckinWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Backs the wellness check-ins: the daytime energy/sensory pop-ups and the morning sleep report,
 * plus the daily/weekly aggregation the History report reads. Screen-time sleep estimation is
 * delegated to [ScreenTimeEstimator]; everything else is direct Room reads/writes (DESIGN §11:
 * no sync layer). Reminder cadence (on/off + slot hours) lives in [PreferencesRepository] so the
 * scheduled notifications and the on-open prompts share one source of truth.
 */
class WellnessRepository(
    private val context: Context,
    private val dao: WellnessCheckinDao,
    private val prefs: PreferencesRepository
) {
    /** The daytime check-in slots (device-local hour), from settings. */
    val slotHours: List<Int> get() = prefs.wellnessSlotHours

    /** Master switch: when off, no notifications and no on-open prompts. */
    val remindersEnabled: Boolean get() = prefs.wellnessRemindersEnabled

    /** Sleep report only prompts on the first app open at or after this local hour. */
    val sleepPromptFromHour: Int = 5

    /** Cancel any queued wellness notifications and re-queue the current settings (or none if off). */
    fun rescheduleReminders() {
        WellnessCheckinWorker.cancelAll(context)
        if (prefs.wellnessRemindersEnabled) {
            WellnessCheckinWorker.scheduleAll(context, prefs.wellnessSlotHours)
        }
    }

    fun observeAll(): Flow<List<WellnessCheckin>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }

    /** The report's working window: everything from [sinceWeekKey] onward. */
    fun observeSince(sinceWeekKey: Int): Flow<List<WellnessCheckin>> =
        dao.observeSince(sinceWeekKey).map { list -> list.map { it.toModel() } }

    /** Reports: full check-in history, filtered by recordedAt in the caller. */
    suspend fun getAllCheckins(): List<WellnessCheckin> = dao.getAll().map { it.toModel() }

    /** Persist a daytime check-in. */
    suspend fun logCheckin(energy: Int, sensory: Int, note: String?, at: Long = System.currentTimeMillis()) {
        insert(
            WellnessCheckin(
                id = UUID.randomUUID().toString(),
                kind = WellnessKind.CHECKIN,
                recordedAt = DateUtil.isoFromEpoch(at),
                weekKey = DateUtil.weekIndexFor(at),
                dayKey = DateUtil.localDateKey(at),
                energy = energy,
                sensory = sensory,
                note = note?.takeIf { it.isNotBlank() }
            )
        )
    }

    /** Persist a morning sleep report. [sleepMinutes] may be null when it couldn't be estimated. */
    suspend fun logSleep(
        energy: Int,
        tired: Int,
        sleepMinutes: Int?,
        note: String?,
        at: Long = System.currentTimeMillis()
    ) {
        insert(
            WellnessCheckin(
                id = UUID.randomUUID().toString(),
                kind = WellnessKind.SLEEP,
                recordedAt = DateUtil.isoFromEpoch(at),
                weekKey = DateUtil.weekIndexFor(at),
                dayKey = DateUtil.localDateKey(at),
                energy = energy,
                tired = tired,
                sleepMinutes = sleepMinutes,
                note = note?.takeIf { it.isNotBlank() }
            )
        )
    }

    private suspend fun insert(model: WellnessCheckin) = dao.insert(model.toEntity())

    // --- Pop-up gating ---

    /** True once the day's sleep report has been logged (so the morning prompt fires only once). */
    suspend fun hasSleepForToday(now: Long = System.currentTimeMillis()): Boolean =
        dao.countForDay(DateUtil.localDateKey(now), WellnessKind.SLEEP.value) > 0

    /** How many daytime check-ins have been logged today. */
    suspend fun checkinCountForToday(now: Long = System.currentTimeMillis()): Int =
        dao.countForDay(DateUtil.localDateKey(now), WellnessKind.CHECKIN.value)

    /** How many daytime slots have already come due today (slot hour <= current local hour). */
    fun passedSlotCount(now: Long = System.currentTimeMillis()): Int {
        val hour = DateUtil.localHour(now)
        return slotHours.count { it <= hour }
    }

    /** Screen-time sleep estimate, delegating to [ScreenTimeEstimator]. */
    fun estimateSleep(now: Long = System.currentTimeMillis()): ScreenTimeEstimator.SleepEstimate =
        ScreenTimeEstimator.estimate(context, now)

    fun hasUsageAccess(): Boolean = ScreenTimeEstimator.hasUsageAccess(context)
}
