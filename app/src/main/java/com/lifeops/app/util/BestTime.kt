package com.lifeops.app.util

import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.TaskWeatherRequirement

/**
 * The Phase 3 "best time" recommendation engine — pure and JVM-testable. Given a task's weather
 * [TaskWeatherRequirement], a list of forecast [ForecastPeriod]s (typically the daytime periods of
 * the daily forecast), the household [Person]s a task involves, and optional calendar busy-labels,
 * it scores each window with [OutdoorScore], applies the task's hard limits and each person's
 * comfort ceilings as disqualifiers, and ranks the eligible windows best-first.
 *
 * "Best" = lowest OutdoorScore discomfort among windows that violate no hard constraint. The
 * [RecommendedWindow.matchPercent] (100 − score) is the friendly "92%" the cards show.
 */
data class RecommendedWindow(
    val startTime: String,
    val endTime: String,
    /** Human label from the forecast period, e.g. "Saturday" or "This Afternoon". */
    val label: String,
    val score: Int,
    val matchPercent: Int,
    val rating: OutdoorRating,
    val reasons: List<String>,
    val warnings: List<String>,
    val disqualified: Boolean,
    /** Why this window was ruled out (empty when eligible). */
    val disqualifiers: List<String>
)

object BestTime {

    /** At/above this rain probability an "avoid rain" task treats the window as wet. */
    const val RAIN_DISQUALIFY_PCT = 40

    /**
     * Rank [periods] for a task. Eligible windows (no hard-constraint violation) come first,
     * ordered by ascending discomfort; disqualified windows follow so a caller can still show
     * "nothing works, here's the least-bad" if it wants.
     */
    fun recommend(
        requirement: TaskWeatherRequirement,
        periods: List<ForecastPeriod>,
        people: List<Person> = emptyList(),
        busyLabels: Set<String> = emptySet()
    ): List<RecommendedWindow> =
        periods.map { assess(requirement, it, people, busyLabels) }
            .sortedWith(compareBy({ it.disqualified }, { it.score }, { it.startTime }))

    /** The single best eligible window, or null if every window violates a hard constraint. */
    fun best(
        requirement: TaskWeatherRequirement,
        periods: List<ForecastPeriod>,
        people: List<Person> = emptyList(),
        busyLabels: Set<String> = emptySet()
    ): RecommendedWindow? =
        recommend(requirement, periods, people, busyLabels).firstOrNull { !it.disqualified }

    private fun assess(
        req: TaskWeatherRequirement,
        period: ForecastPeriod,
        people: List<Person>,
        busyLabels: Set<String>
    ): RecommendedWindow {
        val feelsLike = WeatherMath.feelsLikeRounded(period.temperatureF, period.humidityPct, period.wind.speedMph)
        val storm = OutdoorScore.stormRiskFromText(period.shortForecast)
        // Forecast periods carry no UV, so pass null — UV only sharpens the score when available.
        val assessment = OutdoorScore.score(
            feelsLikeF = feelsLike,
            humidityPct = period.humidityPct,
            uvIndex = null,
            windMph = period.wind.speedMph,
            rainProbabilityPct = period.precipitationProbabilityPct,
            stormRisk = storm
        )

        val disq = mutableListOf<String>()

        // Task hard limits (measured against the actual air temperature, not feels-like).
        req.maxTempF?.let { if (period.temperatureF > it) disq += "Too warm (${period.temperatureF}° > ${it}°)" }
        req.minTempF?.let { if (period.temperatureF < it) disq += "Too cold (${period.temperatureF}° < ${it}°)" }
        req.maxWindMph?.let { if (period.wind.speedMph > it) disq += "Too windy (${period.wind.speedMph} > ${it} mph)" }
        if (req.avoidRain) {
            val p = period.precipitationProbabilityPct ?: 0
            if (p >= RAIN_DISQUALIFY_PCT) disq += "Rain likely (${p}%)"
            if (storm) disq += "Storm risk"
        }

        // Household comfort ceilings (feels-like based, matching the People profiles).
        for (person in people) {
            person.heatToleranceMaxF?.let { if (feelsLike > it) disq += "Too hot for ${person.name}" }
            person.coldToleranceMinF?.let { if (feelsLike < it) disq += "Too cold for ${person.name}" }
            person.windMaxMph?.let { if (period.wind.speedMph > it) disq += "Too windy for ${person.name}" }
            person.maxPrecipitationPct?.let {
                if ((period.precipitationProbabilityPct ?: 0) > it) disq += "Too wet for ${person.name}"
            }
        }

        if (period.name in busyLabels) disq += "Calendar conflict"

        return RecommendedWindow(
            startTime = period.startTime,
            endTime = period.endTime,
            label = period.name,
            score = assessment.score,
            matchPercent = (100 - assessment.score).coerceIn(0, 100),
            rating = assessment.rating,
            reasons = assessment.positives,
            warnings = assessment.warnings,
            disqualified = disq.isNotEmpty(),
            disqualifiers = disq
        )
    }
}
