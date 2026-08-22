package com.lifeops.app.connection.service

import com.lifeops.app.data.model.Initiative
import com.lifeops.app.data.model.WellnessTrend
import com.lifeops.app.data.repository.WellnessRepository
import com.lifeops.app.util.SleepInferenceService

/** Use-case layer for wellness check-ins (relative trend + initiative) and morning sleep reports. */
class WellnessService(private val wellnessRepository: WellnessRepository) {

    /**
     * Log a daytime check-in: [trend] against the last reading and [initiative] (desire to do
     * things). [energy]/[sensory] are the optional exact 1–10 ratings — left null, the energy is
     * derived from the trend. [at] (epoch millis) defaults to now.
     */
    suspend fun checkin(
        trend: WellnessTrend,
        initiative: Initiative,
        energy: Int? = null,
        sensory: Int? = null,
        note: String? = null,
        at: Long? = null
    ) = wellnessRepository.logCheckin(
        trend = trend,
        initiative = initiative,
        energy = energy,
        sensory = sensory,
        note = note,
        at = at ?: System.currentTimeMillis()
    )

    /**
     * Log a morning sleep report. [sleepMinutes] may be null when it couldn't be estimated.
     * [reconstruction] (bedtime/wake/interruptions from tracked phone activity) is supplied by the
     * wellness screen when it has one; connection-function callers, which can't build it, pass null.
     */
    suspend fun sleep(
        energy: Int,
        tired: Int,
        sleepMinutes: Int? = null,
        note: String? = null,
        reconstruction: SleepInferenceService.SleepReconstruction? = null,
        at: Long? = null
    ) = wellnessRepository.logSleep(
        energy = energy,
        tired = tired,
        sleepMinutes = sleepMinutes,
        note = note,
        reconstruction = reconstruction,
        at = at ?: System.currentTimeMillis()
    )
}
