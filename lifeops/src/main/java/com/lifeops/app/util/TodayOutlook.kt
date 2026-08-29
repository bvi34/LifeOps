package com.lifeops.app.util

import com.lifeops.app.data.model.ForecastPeriod

/**
 * The rest of the day in four numbers — what a glance-sized widget shows under the current
 * temperature. Pure and Android-free (like WeatherMath / OutdoorScore) so it's unit-testable.
 */
data class DayOutlook(
    /** The daytime period's temperature: NWS publishes it as the day's high. Null after sundown
     *  when the next daytime period is beyond the window we look at. */
    val highF: Int?,
    /** The night period's temperature — NWS's overnight low. */
    val lowF: Int?,
    /** The leading period's short forecast, e.g. "Partly Cloudy" — what it's about to do. */
    val headline: String,
    /** The worst precipitation chance across the window, so a dry afternoon before a wet
     *  evening still reads as "rain coming". */
    val precipitationProbabilityPct: Int?,
    /** The leading period's own name ("This Afternoon", "Tonight") — the window's honest label. */
    val periodName: String
)

/**
 * Reads the NWS daily product, which comes as alternating day/night halves ("This Afternoon",
 * "Tonight", "Wednesday", …) starting from *now* — so the first period is whichever half of the
 * day you're standing in.
 *
 * Only the leading two periods are considered, and that is the whole trick: in the morning they are
 * today and tonight, giving a real high and low; in the evening they are tonight and tomorrow,
 * giving tonight's low and tomorrow's high. Either way the pair is "the weather you still have to
 * live through", which is what the widget is for. A period's own daytime flag decides whether its
 * temperature is the high or the low, so neither is ever guessed from the clock.
 */
object TodayOutlook {

    /** How many leading periods make up "the rest of the day". */
    private const val WINDOW = 2

    fun from(daily: List<ForecastPeriod>): DayOutlook? {
        val window = daily.take(WINDOW)
        val lead = window.firstOrNull() ?: return null
        return DayOutlook(
            highF = window.firstOrNull { it.isDaytime }?.temperatureF,
            lowF = window.firstOrNull { !it.isDaytime }?.temperatureF,
            headline = lead.shortForecast,
            precipitationProbabilityPct =
                window.mapNotNull { it.precipitationProbabilityPct }.maxOrNull(),
            periodName = lead.name
        )
    }
}
