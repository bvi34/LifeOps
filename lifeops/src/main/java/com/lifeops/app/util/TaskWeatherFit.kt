package com.lifeops.app.util

import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.TaskWeatherRequirement

/**
 * A compact "how does the weather fit this task?" read for the task list badge — the same
 * BestTime engine the Weather screen uses, condensed into a single today-vs-best-day answer so a
 * task with a weather profile can flag "not today" inline. Pure and JVM-testable.
 */
data class TaskWeatherFit(
    /** True today's soonest daytime window clears the task's hard constraints. */
    val suitableToday: Boolean,
    /** 100 − discomfort for today's window, the friendly "82%" match. */
    val todayMatchPercent: Int,
    val todayRating: OutdoorRating,
    /** Label of the best eligible daytime window in the forecast (e.g. "Saturday"), or null if
     *  nothing in range works. */
    val bestWindowLabel: String?,
    /** True when the best eligible window is today's window (so "not today" shouldn't show). */
    val bestIsToday: Boolean,
    /** Top reason today is ruled out (e.g. "Rain likely (60%)"), null when suitable. */
    val notTodayReason: String?
)

object TaskWeatherFitCalculator {

    /**
     * Compute a [TaskWeatherFit] from a task's [requirement] and the forecast's daytime
     * [dayPeriods]. Returns null when the task has no meaningful weather profile or there's no
     * forecast to judge against — callers hide the badge in that case.
     */
    fun compute(
        requirement: TaskWeatherRequirement,
        dayPeriods: List<ForecastPeriod>,
        people: List<Person> = emptyList()
    ): TaskWeatherFit? {
        if (requirement.isEmpty || dayPeriods.isEmpty()) return null

        val windows = BestTime.recommend(requirement, dayPeriods, people)
        // "Today" = the soonest daytime window (ISO start times sort lexicographically).
        val todayWindow = windows.minByOrNull { it.startTime } ?: return null
        val best = windows.firstOrNull { !it.disqualified }
        val suitableToday = !todayWindow.disqualified

        return TaskWeatherFit(
            suitableToday = suitableToday,
            todayMatchPercent = todayWindow.matchPercent,
            todayRating = todayWindow.rating,
            bestWindowLabel = best?.label,
            bestIsToday = best != null && best.startTime == todayWindow.startTime,
            notTodayReason = if (suitableToday) null
                else todayWindow.disqualifiers.firstOrNull() ?: todayWindow.warnings.firstOrNull()
        )
    }
}
