package com.lifeops.app.ui.screens.wellness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.WellnessCheckin
import com.lifeops.app.data.model.WellnessKind
import com.lifeops.app.data.repository.WellnessRepository
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One day's rolled-up wellness: check-in averages plus that morning's sleep report. */
data class DailyWellness(
    val dayKey: String,
    val label: String,
    val checkinCount: Int,
    val avgEnergy: Double?,
    val avgSensory: Double?,
    val sleepMinutes: Int?,
    val sleepTired: Int?,
    val sleepEnergy: Int?
)

/** One week's rolled-up wellness across every check-in and sleep report in it. */
data class WeeklyWellness(
    val weekKey: Int,
    val label: String,
    val avgEnergy: Double?,
    val avgSensory: Double?,
    val avgTired: Double?,
    val avgSleepMinutes: Double?,
    val checkinCount: Int,
    val sleepCount: Int
)

data class WellnessReportState(
    val isLoading: Boolean = true,
    val today: DailyWellness? = null,
    val currentWeek: WeeklyWellness? = null,
    val weeklyTrend: List<WeeklyWellness> = emptyList(),  // oldest → newest
    val recentDays: List<DailyWellness> = emptyList(),     // newest → oldest, ~last 14 days
    val recent: List<WellnessCheckin> = emptyList()        // raw entries, newest first
)

class WellnessViewModel(
    private val repo: WellnessRepository
) : ViewModel() {

    private val currentWeekKey = DateUtil.weekIndexFor(System.currentTimeMillis())
    private val sinceWeekKey = currentWeekKey - 8

    private val _uiState = MutableStateFlow(WellnessReportState())
    val uiState: StateFlow<WellnessReportState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeSince(sinceWeekKey).collect { entries ->
                _uiState.value = aggregate(entries)
            }
        }
    }

    /** Log a check-in on demand (the report's "+" — not tied to a scheduled slot). */
    fun logCheckin(energy: Int, sensory: Int, why: String) {
        viewModelScope.launch { repo.logCheckin(energy, sensory, why) }
    }

    private fun aggregate(entries: List<WellnessCheckin>): WellnessReportState {
        val byDay = entries.groupBy { it.dayKey }
        val days = byDay.keys.sortedDescending().map { dayKey -> dailyFor(dayKey, byDay.getValue(dayKey)) }

        val today = days.firstOrNull { it.dayKey == DateUtil.todayKey() }

        val byWeek = entries.groupBy { it.weekKey }
        val weeks = byWeek.keys.sorted().map { weekKey -> weeklyFor(weekKey, byWeek.getValue(weekKey)) }
        val currentWeek = weeks.firstOrNull { it.weekKey == currentWeekKey }

        return WellnessReportState(
            isLoading = false,
            today = today,
            currentWeek = currentWeek,
            weeklyTrend = weeks,
            recentDays = days.take(14),
            recent = entries.take(60)
        )
    }

    private fun dailyFor(dayKey: String, dayEntries: List<WellnessCheckin>): DailyWellness {
        val checkins = dayEntries.filter { it.kind == WellnessKind.CHECKIN }
        // The last sleep report of the day is the authoritative one.
        val sleep = dayEntries.filter { it.kind == WellnessKind.SLEEP }.maxByOrNull { it.recordedAt }
        return DailyWellness(
            dayKey = dayKey,
            label = DateUtil.formatDate(dayKey),
            checkinCount = checkins.size,
            avgEnergy = checkins.mapNotNull { it.energy }.avgOrNull(),
            avgSensory = checkins.mapNotNull { it.sensory }.avgOrNull(),
            sleepMinutes = sleep?.sleepMinutes,
            sleepTired = sleep?.tired,
            sleepEnergy = sleep?.energy
        )
    }

    private fun weeklyFor(weekKey: Int, weekEntries: List<WellnessCheckin>): WeeklyWellness {
        val checkins = weekEntries.filter { it.kind == WellnessKind.CHECKIN }
        val sleeps = weekEntries.filter { it.kind == WellnessKind.SLEEP }
        // A sleep report's energy is a valid energy reading too, so the week's energy pools both.
        val allEnergy = checkins.mapNotNull { it.energy } + sleeps.mapNotNull { it.energy }
        return WeeklyWellness(
            weekKey = weekKey,
            label = DateUtil.formatDate(DateUtil.weekStartForIndex(weekKey).toString()),
            avgEnergy = allEnergy.avgOrNull(),
            avgSensory = checkins.mapNotNull { it.sensory }.avgOrNull(),
            avgTired = sleeps.mapNotNull { it.tired }.avgOrNull(),
            avgSleepMinutes = sleeps.mapNotNull { it.sleepMinutes }.avgOrNull(),
            checkinCount = checkins.size,
            sleepCount = sleeps.size
        )
    }

    private fun List<Int>.avgOrNull(): Double? = if (isEmpty()) null else average()
}

class WellnessViewModelFactory(
    private val repo: WellnessRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        WellnessViewModel(repo) as T
}
