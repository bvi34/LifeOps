package com.lifeops.app.connection.service

import com.lifeops.app.data.repository.WellnessRepository

/** Use-case layer for wellness check-ins (daytime energy/sensory) and morning sleep reports. */
class WellnessService(private val wellnessRepository: WellnessRepository) {

    /** Log a daytime check-in. [at] (epoch millis) defaults to now. */
    suspend fun checkin(energy: Int, sensory: Int, note: String? = null, at: Long? = null) =
        wellnessRepository.logCheckin(energy, sensory, note, at ?: System.currentTimeMillis())

    /**
     * Log a morning sleep report. [sleepMinutes] may be null when it couldn't be estimated.
     * Overnight reconstruction (bedtime/wake/interruptions) is a UI-side concern and is not
     * accepted here; the connection route records the self-reported figures.
     */
    suspend fun sleep(
        energy: Int,
        tired: Int,
        sleepMinutes: Int? = null,
        note: String? = null,
        at: Long? = null
    ) = wellnessRepository.logSleep(
        energy = energy,
        tired = tired,
        sleepMinutes = sleepMinutes,
        note = note,
        at = at ?: System.currentTimeMillis()
    )
}
