package com.lifeops.app.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The task a running timer is attached to. Pomodoro auto-stops at 25 minutes. */
data class ActiveTimer(
    val taskId: String,
    val startMillis: Long,
    val isPomodoro: Boolean = false
)

/**
 * App-scoped clock for task time tracking. Lifted out of any one screen's ViewModel so a timer
 * started from the task detail screen keeps running — and stays visible on the This Week rows —
 * as the user navigates between them. Both surfaces observe this single instance, and the timer
 * survives navigation (it is only ever stopped explicitly, never on a screen's teardown).
 *
 * [activeTimer] changes only on start/stop; [elapsedSeconds] ticks every second so a caller can
 * recompose just the running clock without churning surrounding state.
 */
class TimerController(
    private val scope: CoroutineScope,
    private val timeEntryRepository: TimeEntryRepository
) {
    private val _activeTimer = MutableStateFlow<ActiveTimer?>(null)
    val activeTimer: StateFlow<ActiveTimer?> = _activeTimer.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    private var timerJob: Job? = null

    fun start(taskId: String, isPomodoro: Boolean = false) {
        stop(saveEntry = true)
        val startMillis = System.currentTimeMillis()
        _activeTimer.value = ActiveTimer(taskId, startMillis, isPomodoro = isPomodoro)
        _elapsedSeconds.value = 0
        timerJob = scope.launch {
            while (true) {
                delay(1000)
                val elapsed = ((System.currentTimeMillis() - startMillis) / 1000).toInt()
                _elapsedSeconds.value = elapsed
                // Auto-stop Pomodoro at 25 minutes.
                if (isPomodoro && elapsed >= 1500) {
                    stop(saveEntry = true)
                    break
                }
            }
        }
    }

    fun stop(saveEntry: Boolean = true) {
        val timer = _activeTimer.value ?: return
        timerJob?.cancel()
        timerJob = null
        // Always log actual elapsed time. For a completed Pomodoro elapsed ≈ 1500s → 25min.
        val minutes = _elapsedSeconds.value / 60
        if (saveEntry && minutes > 0) {
            scope.launch { timeEntryRepository.logTime(timer.taskId, minutes) }
        }
        _activeTimer.value = null
        _elapsedSeconds.value = 0
    }
}
