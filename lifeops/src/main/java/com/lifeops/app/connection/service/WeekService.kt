package com.lifeops.app.connection.service

import com.lifeops.app.data.model.Week
import com.lifeops.app.data.repository.TaskRepository
import com.lifeops.app.data.repository.WeekRepository

/**
 * Use-case layer for the weekly cycle. Owns the compound "close the week" orchestration that spans
 * the week and task repositories (mint the next week, snapshot + settle the closing week, seed its
 * recurring series) so the UI and connection paths close a week identically. Purely UI-side
 * concerns of a close (stopping a running timer, refreshing the home-screen widget) stay in the
 * caller.
 */
class WeekService(
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository
) {

    /** Resolve the open week, creating it if the app has none yet. */
    suspend fun getOrCreateCurrent(): Week = weekRepository.getOrCreateCurrentWeek()

    /** Outcome of a close call. [NoOpenWeek] means there was nothing open to close. */
    sealed interface CloseOutcome {
        data class Closed(val closedWeek: Week, val newWeek: Week) : CloseOutcome
        data object NoOpenWeek : CloseOutcome
    }

    /**
     * Close the current open week and open the next one. The close ritual's answers —
     * [selfRating]/[selfRatingNote], whether a [mentalReset] was achieved, and the week's overall
     * [exhaustion] (1–10) — are sealed into the closing week's snapshot. Idempotent-ish: relies on
     * [WeekRepository.createNextWeek]'s mutex to avoid double-opening when called concurrently.
     */
    suspend fun close(
        selfRating: Int? = null,
        selfRatingNote: String? = null,
        mentalReset: Boolean? = null,
        exhaustion: Int? = null
    ): CloseOutcome {
        val week = weekRepository.getCurrentWeek() ?: return CloseOutcome.NoOpenWeek
        val newWeek = weekRepository.createNextWeek(week)
        taskRepository.closeWeek(week.id, newWeek.id, selfRating, selfRatingNote, mentalReset, exhaustion)
        taskRepository.seedRecurringTasks(week.id, newWeek.id)
        return CloseOutcome.Closed(closedWeek = week, newWeek = newWeek)
    }
}
