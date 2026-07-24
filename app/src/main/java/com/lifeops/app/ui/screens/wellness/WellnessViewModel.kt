package com.lifeops.app.ui.screens.wellness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.WellnessCheckin
import com.lifeops.app.data.model.WellnessKind
import com.lifeops.app.data.repository.AspectRepository
import com.lifeops.app.data.repository.TaskRepository
import com.lifeops.app.data.repository.TimeEntryRepository
import com.lifeops.app.data.repository.WellnessRepository
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ScreenTimeEstimator
import com.lifeops.app.util.SleepInferenceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

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

/** A daily trend series for a sparkline; [values] are oldest→newest, null = no data that day. */
data class Sparkline(val values: List<Float?>, val latest: Float?)

/** Average energy for a band of daily logged hours. */
data class HoursEnergyBucket(val label: String, val avgEnergy: Double, val dayCount: Int)

/** How energy looks on days with meaningful time in one aspect, vs. the overall daily average. */
data class AspectEnergyRow(
    val aspectName: String,
    val color: String,
    val dayCount: Int,
    val avgEnergy: Double,
    val delta: Double
)

/** Correlations between logged time (total + per aspect) and daily energy. */
data class WellnessInsights(
    val dayCount: Int,
    val overallAvgEnergy: Double?,
    val hoursEnergyCorr: Double?,
    val hoursEnergyBuckets: List<HoursEnergyBucket>,
    val aspectEnergy: List<AspectEnergyRow>
)

data class WellnessReportState(
    val isLoading: Boolean = true,
    val today: DailyWellness? = null,
    val currentWeek: WeeklyWellness? = null,
    val weeklyTrend: List<WeeklyWellness> = emptyList(),   // oldest → newest
    val recentDays: List<DailyWellness> = emptyList(),      // newest → oldest, ~last 14 days
    val recent: List<WellnessCheckin> = emptyList(),        // raw entries, newest first
    val energySpark: Sparkline = Sparkline(emptyList(), null),
    val sensorySpark: Sparkline = Sparkline(emptyList(), null),
    val sleepSpark: Sparkline = Sparkline(emptyList(), null),
    val insights: WellnessInsights? = null
)

class WellnessViewModel(
    private val repo: WellnessRepository,
    private val taskRepository: TaskRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val aspectRepository: AspectRepository
) : ViewModel() {

    private val currentWeekKey = DateUtil.weekIndexFor(System.currentTimeMillis())
    private val sinceWeekKey = currentWeekKey - 8
    private val sparkDays = 21

    private val _uiState = MutableStateFlow(WellnessReportState())
    val uiState: StateFlow<WellnessReportState> = _uiState.asStateFlow()

    // Screen-time sleep estimate for the manual "log sleep" path; null until computed.
    private val _sleepEstimate = MutableStateFlow<ScreenTimeEstimator.SleepEstimate?>(null)
    val sleepEstimate: StateFlow<ScreenTimeEstimator.SleepEstimate?> = _sleepEstimate.asStateFlow()

    // The accurate event-based reconstruction for the manual path, when enough was captured.
    private val _sleepReconstruction = MutableStateFlow<SleepInferenceService.SleepReconstruction?>(null)
    val sleepReconstruction: StateFlow<SleepInferenceService.SleepReconstruction?> = _sleepReconstruction.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repo.observeSince(sinceWeekKey),
                aspectRepository.observeAllAspects()
            ) { entries, aspects -> entries to aspects }
                .collect { (entries, aspects) ->
                    // Pure daily/weekly rollups first, so the screen paints without waiting on IO.
                    val base = aggregate(entries)
                    _uiState.value = base
                    // Correlations need the time-entry ↔ task ↔ aspect join off the main thread.
                    val insights = withContext(Dispatchers.IO) { computeInsights(entries, aspects) }
                    _uiState.value = base.copy(insights = insights)
                }
        }
    }

    /** Log a check-in on demand (the report's "+" — not tied to a scheduled slot). */
    fun logCheckin(energy: Int, sensory: Int, why: String) {
        viewModelScope.launch { repo.logCheckin(energy, sensory, why) }
    }

    /** Log a sleep report on demand. Keeps the reconstruction only when the user didn't override the total. */
    fun logSleep(energy: Int, tired: Int, sleepMinutes: Int?, why: String) {
        val reconstruction = _sleepReconstruction.value?.takeIf { it.totalSleepMinutes == sleepMinutes }
        viewModelScope.launch { repo.logSleep(energy, tired, sleepMinutes, why, reconstruction) }
    }

    /** Prepare the manual sleep dialog: reconstruct from tracked events, with the screen-time estimate as fallback. */
    fun prepareSleepEstimate() {
        _sleepEstimate.value = null
        _sleepReconstruction.value = null
        viewModelScope.launch(Dispatchers.IO) {
            _sleepReconstruction.value = repo.reconstructLastNight()
            _sleepEstimate.value = repo.estimateSleep()
        }
    }

    // --- Pure rollups ---

    private fun aggregate(entries: List<WellnessCheckin>): WellnessReportState {
        val byDay = entries.groupBy { it.dayKey }
        val daily = byDay.mapValues { (dayKey, dayEntries) -> dailyFor(dayKey, dayEntries) }
        val days = daily.values.sortedByDescending { it.dayKey }

        val today = daily[DateUtil.todayKey()]

        val byWeek = entries.groupBy { it.weekKey }
        val weeks = byWeek.keys.sorted().map { weekKey -> weeklyFor(weekKey, byWeek.getValue(weekKey)) }
        val currentWeek = weeks.firstOrNull { it.weekKey == currentWeekKey }

        // Sparklines over the last [sparkDays] calendar days, oldest → newest.
        val today0 = LocalDate.now()
        val window = (sparkDays - 1 downTo 0).map { today0.minusDays(it.toLong()).toString() }
        val energyVals = window.map { daily[it]?.avgEnergy?.toFloat() }
        val sensoryVals = window.map { daily[it]?.avgSensory?.toFloat() }
        val sleepVals = window.map { daily[it]?.sleepMinutes?.let { m -> m / 60f } }

        return WellnessReportState(
            isLoading = false,
            today = today,
            currentWeek = currentWeek,
            weeklyTrend = weeks,
            recentDays = days.take(14),
            recent = entries.take(60),
            energySpark = Sparkline(energyVals, energyVals.lastOrNull { it != null }),
            sensorySpark = Sparkline(sensoryVals, sensoryVals.lastOrNull { it != null }),
            sleepSpark = Sparkline(sleepVals, sleepVals.lastOrNull { it != null })
        )
    }

    private fun dailyFor(dayKey: String, dayEntries: List<WellnessCheckin>): DailyWellness {
        val checkins = dayEntries.filter { it.kind == WellnessKind.CHECKIN }
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

    // --- Correlation (the focus): logged time ↔ energy ---

    private suspend fun computeInsights(
        entries: List<WellnessCheckin>,
        aspects: List<Aspect>
    ): WellnessInsights {
        // Daily energy: pool check-in and sleep energy readings.
        val energyByDay: Map<String, Double> = entries
            .groupBy { it.dayKey }
            .mapValues { (_, es) -> es.mapNotNull { it.energy } }
            .filterValues { it.isNotEmpty() }
            .mapValues { (_, v) -> v.average() }

        if (energyByDay.isEmpty()) {
            return WellnessInsights(0, null, null, emptyList(), emptyList())
        }

        val cutoffIso = DateUtil.weekStartForIndex(sinceWeekKey)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toString()
        val timeEntries = timeEntryRepository.getAllSince(cutoffIso)
        val tasks = taskRepository.getAllSince(cutoffIso)
        val taskAspect = tasks.associate { it.id to it.aspectId }
        val aspectById = aspects.associateBy { it.id }

        val minutesByDay = mutableMapOf<String, Int>()
        val minutesByDayAspect = mutableMapOf<String, MutableMap<String, Int>>()
        for (te in timeEntries) {
            val day = isoToDayKey(te.recordedAt) ?: continue
            minutesByDay[day] = (minutesByDay[day] ?: 0) + te.durationMinutes
            val aspectId = taskAspect[te.taskId] ?: continue
            val perAspect = minutesByDayAspect.getOrPut(day) { mutableMapOf() }
            perAspect[aspectId] = (perAspect[aspectId] ?: 0) + te.durationMinutes
        }

        val overallAvg = energyByDay.values.average()

        // Hours → energy across days that have an energy reading (0h counts).
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        energyByDay.forEach { (day, energy) ->
            xs.add((minutesByDay[day] ?: 0) / 60.0)
            ys.add(energy)
        }
        val corr = pearson(xs, ys)
        val buckets = bucketize(xs, ys)

        // Aspect → energy: energy on days with any time in that aspect.
        val aspectRows = minutesByDayAspect.values
            .flatMap { it.keys }
            .distinct()
            .mapNotNull { aspectId ->
                val daysForAspect = minutesByDayAspect
                    .filter { (day, m) -> (m[aspectId] ?: 0) > 0 && energyByDay.containsKey(day) }
                    .keys
                if (daysForAspect.size < 2) return@mapNotNull null
                val avg = daysForAspect.map { energyByDay.getValue(it) }.average()
                val aspect = aspectById[aspectId]
                AspectEnergyRow(
                    aspectName = aspect?.name ?: "—",
                    color = aspect?.color ?: "#6200EE",
                    dayCount = daysForAspect.size,
                    avgEnergy = avg,
                    delta = avg - overallAvg
                )
            }
            .sortedByDescending { it.delta }

        return WellnessInsights(
            dayCount = energyByDay.size,
            overallAvgEnergy = overallAvg,
            hoursEnergyCorr = corr,
            hoursEnergyBuckets = buckets,
            aspectEnergy = aspectRows
        )
    }

    private fun bucketize(xs: List<Double>, ys: List<Double>): List<HoursEnergyBucket> {
        data class Band(val label: String, val test: (Double) -> Boolean)
        val bands = listOf(
            Band("None") { it <= 0.0 },
            Band("<2h") { it > 0.0 && it < 2.0 },
            Band("2–4h") { it >= 2.0 && it < 4.0 },
            Band("4h+") { it >= 4.0 }
        )
        return bands.mapNotNull { band ->
            val energies = xs.indices.filter { band.test(xs[it]) }.map { ys[it] }
            if (energies.isEmpty()) null
            else HoursEnergyBucket(band.label, energies.average(), energies.size)
        }
    }

    private fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        val n = xs.size
        if (n < 3) return null
        val mx = xs.average(); val my = ys.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        }
        if (sxx == 0.0 || syy == 0.0) return null
        return sxy / sqrt(sxx * syy)
    }

    private fun isoToDayKey(iso: String): String? = try {
        DateUtil.localDateKey(Instant.parse(iso).toEpochMilli())
    } catch (_: Exception) { null }

    private fun List<Int>.avgOrNull(): Double? = if (isEmpty()) null else average()
}

class WellnessViewModelFactory(
    private val repo: WellnessRepository,
    private val taskRepository: TaskRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val aspectRepository: AspectRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        WellnessViewModel(repo, taskRepository, timeEntryRepository, aspectRepository) as T
}
