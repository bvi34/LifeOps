package com.lifeops.app.connection.service

import com.lifeops.app.data.repository.WellnessRepository
import com.lifeops.app.util.SleepInferenceService

/** Use-case layer for wellness check-ins (daytime energy/sensory) and morning sleep reports. */
class WellnessService(private val wellnessRepository: WellnessRepository) {

    /** Log a daytime check-in. [at] (epoch millis) defaults to now. */
    suspend fun checkin(energy: Int, sensory: Int, note: String? = null, at: Long? = null) =
        wellnessRepository.logCheckin(energy, sensory, note, at ?: System.currentTimeMillis())

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
