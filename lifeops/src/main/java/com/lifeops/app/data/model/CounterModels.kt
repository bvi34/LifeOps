package com.lifeops.app.data.model



/**
 * Counters: standalone tally streams, and the timestamped ticks that make them up.
 */

data class Counter(
    val id: String,
    val name: String,
    val categoryId: String? = null,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: String,
    val isHabit: Boolean = false,
    val reminderHour: Int? = null
)

/**
 * The weather conditions captured alongside a [CounterEvent] at the moment it was logged. Present
 * only on live ticks that had fresh cached conditions to draw on; every field is nullable so a
 * partially-known reading (e.g. no humidity from the NWS forecast product) still records what it
 * can. [locationName] names the tracked place the reading came from and [observedAt] is that
 * snapshot's observation time, so the UI can convey how current the numbers were.
 */
data class CounterEventWeather(
    val temperatureF: Int? = null,
    val feelsLikeF: Int? = null,
    val humidityPct: Int? = null,
    val windMph: Int? = null,
    val conditions: String? = null,
    val locationName: String? = null,
    val observedAt: String? = null
) {
    /** True when nothing meaningful was captured — the mapper treats this as "no weather". */
    val isEmpty: Boolean
        get() = temperatureF == null && feelsLikeF == null && humidityPct == null &&
            windMph == null && conditions.isNullOrBlank() && locationName.isNullOrBlank()
}

data class CounterEvent(
    val id: String,
    val counterId: String,
    val weekKey: Int,
    val occurredAt: String,
    val delta: Int = 1,
    val note: String? = null,
    // Weather at the moment of the tick, or null when none was captured (backdated entry, no
    // tracked location, or an empty/stale cache). See [CounterEventWeather].
    val weather: CounterEventWeather? = null
)
