package com.lifeops.app.data.repository

import android.content.Context
import com.lifeops.app.data.db.dao.WellnessCheckinDao
import com.lifeops.app.data.model.Initiative
import com.lifeops.app.data.model.SensoryTrend
import com.lifeops.app.data.model.WellnessCheckin
import com.lifeops.app.data.model.WellnessKind
import com.lifeops.app.data.model.WellnessTrend
import com.lifeops.app.service.SleepTrackingService
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ScreenTimeEstimator
import com.lifeops.app.util.SleepInferenceService
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import com.lifeops.app.worker.WellnessCheckinWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Backs the wellness check-ins: the daytime better/same/worse pop-ups and the morning sleep report,
 * plus the daily/weekly aggregation the History report reads. The morning sleep duration is
 * reconstructed from tracked phone-activity events via [PhoneActivityRepository] (the accurate path),
 * falling back to the coarser [ScreenTimeEstimator] when too little was captured; everything else is
 * direct Room reads/writes (DESIGN §11: no sync layer). Reminder cadence (on/off + slot hours) lives
 * in [PreferencesRepository] so the scheduled notifications and the on-open prompts share one source
 * of truth.
 */
class WellnessRepository(
    private val context: Context,
    private val dao: WellnessCheckinDao,
    private val phoneActivity: PhoneActivityRepository,
    private val prefs: PreferencesRepository
) {
    /** The daytime check-in slots (device-local hour), from settings. */
    val slotHours: List<Int> get() = prefs.wellnessSlotHours

    /** Master switch: when off, no notifications and no on-open prompts. */
    val remindersEnabled: Boolean get() = prefs.wellnessRemindersEnabled

    /** Sleep report only prompts on the first app open at or after this local hour. */
    val sleepPromptFromHour: Int = 5

    /** Whether the background sleep tracker (screen/charging capture) is running. */
    val sleepTrackingEnabled: Boolean get() = prefs.sleepTrackingEnabled

    /** Turn the background sleep tracker on/off: flips the pref and starts/stops the service. */
    fun setSleepTrackingEnabled(enabled: Boolean) {
        prefs.sleepTrackingEnabled = enabled
        if (enabled) SleepTrackingService.start(context) else SleepTrackingService.stop(context)
    }

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

    /**
     * Persist a daytime check-in. The answers are relative — [trend] and [sensoryTrend] against the
     * previous reading, plus [initiative] (desire to do things) — because re-scoring the same 1–10
     * scales three times a day mostly produced repeated numbers. [energy]/[sensory] are only
     * non-null when the user opened the optional exact ratings; otherwise each is stepped from the
     * last reading by its trend (see [deriveEnergy]/[deriveSensory]) and flagged as derived, so the
     * reports' 1–10 series stay continuous. [sensoryTrend] is null only on the connection route,
     * which may omit it — the check-in dialog always asks.
     */
    suspend fun logCheckin(
        trend: WellnessTrend,
        initiative: Initiative,
        sensoryTrend: SensoryTrend? = null,
        energy: Int? = null,
        sensory: Int? = null,
        note: String? = null,
        at: Long = System.currentTimeMillis()
    ) {
        val recordedAt = DateUtil.isoFromEpoch(at)
        val derivedSensory = if (sensory == null && sensoryTrend != null)
            deriveSensory(sensoryTrend, recordedAt) else null
        insert(
            WellnessCheckin(
                id = UUID.randomUUID().toString(),
                kind = WellnessKind.CHECKIN,
                recordedAt = recordedAt,
                weekKey = DateUtil.weekIndexFor(at),
                dayKey = DateUtil.localDateKey(at),
                energy = energy ?: deriveEnergy(trend, recordedAt),
                sensory = sensory ?: derivedSensory,
                trend = trend,
                sensoryTrend = sensoryTrend,
                initiative = initiative,
                energyDerived = energy == null,
                sensoryDerived = derivedSensory != null,
                note = note?.takeIf { it.isNotBlank() }
            )
        )
    }

    /**
     * Step the last energy reading (check-in or morning report, whichever came last) by [trend], so a
     * relative answer still lands on the 1–10 scale the rollups and correlations read. Derived values
     * are themselves anchors, so a run of "worse" walks the number down.
     */
    private suspend fun deriveEnergy(trend: WellnessTrend, atIso: String): Int =
        trend.energyFrom(dao.latestWithEnergyBefore(atIso)?.energy)

    /**
     * The same walk for sensory load, anchored on the last reading that carried one. Sleep reports
     * never do, so the anchor is always the previous check-in — and before the first one, the middle
     * of the scale.
     */
    private suspend fun deriveSensory(sensoryTrend: SensoryTrend, atIso: String): Int =
        sensoryTrend.sensoryFrom(dao.latestWithSensoryBefore(atIso)?.sensory)

    /** The reading a "better/same/worse" answer is measured against — null before anything is logged. */
    suspend fun latestReading(now: Long = System.currentTimeMillis()): WellnessCheckin? =
        dao.latestBefore(DateUtil.isoFromEpoch(now))?.toModel()

    /**
     * Persist a morning sleep report. [sleepMinutes] may be null when it couldn't be estimated. When
     * the overnight reconstruction produced [reconstruction], its bedtime/wake/interruptions/longest
     * are stored alongside so the full picture is retained (and not just the total).
     */
    suspend fun logSleep(
        energy: Int,
        tired: Int,
        sleepMinutes: Int?,
        note: String?,
        reconstruction: SleepInferenceService.SleepReconstruction? = null,
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
                note = note?.takeIf { it.isNotBlank() },
                sleepBedtime = reconstruction?.let { DateUtil.isoFromEpoch(it.bedtimeMillis) },
                sleepWakeTime = reconstruction?.let { DateUtil.isoFromEpoch(it.wakeMillis) },
                sleepInterruptions = reconstruction?.interruptions,
                longestSleepMinutes = reconstruction?.longestSleepMinutes
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

    /**
     * Reconstruct last night's sleep from the captured phone-activity events (the accurate path).
     * Null when there aren't enough events to be confident — the caller then falls back to the
     * screen-time estimate.
     */
    suspend fun reconstructLastNight(
        now: Long = System.currentTimeMillis()
    ): SleepInferenceService.SleepReconstruction? = phoneActivity.reconstructLastNight(now)
}
