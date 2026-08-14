package com.lifeops.app.ui.screens.counters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.db.dao.CounterDailyTotal
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Category
import com.lifeops.app.data.model.Counter
import com.lifeops.app.data.repository.AspectRepository
import com.lifeops.app.data.repository.CounterRepository
import com.lifeops.app.data.repository.WeekRepository
import com.lifeops.app.data.repository.WellnessRepository
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

data class CounterDashboardHabit(
    val counter: Counter,
    val todayTotal: Int,
    val weekTotal: Int,
    val cumulativeTotal: Int,
    val streakDays: Int,
    val last7Days: List<Int>
)

private data class CounterSources(
    val counters: List<Counter>,
    val aspects: List<Aspect>,
    val categories: List<Category>,
    val weekly: Map<String, Int>,
    val cumulative: Map<String, Int>
)

data class CountersUiState(
    val counters: List<Counter> = emptyList(),
    val aspects: List<Aspect> = emptyList(),
    val categoriesByAspect: Map<String, List<Category>> = emptyMap(),
    val weeklyTotals: Map<String, Int> = emptyMap(),   // counterId -> count this week
    val cumulativeTotals: Map<String, Int> = emptyMap(), // counterId -> all-time count
    val todayTotals: Map<String, Int> = emptyMap(),
    val last7DaysByCounter: Map<String, List<Int>> = emptyMap(), // counterId -> oldest..today, for every counter's list dots
    val dashboardHabits: List<CounterDashboardHabit> = emptyList(),
    val activeHabitCount: Int = 0,
    val touchedTodayCount: Int = 0,
    val totalToday: Int = 0,
    val bestStreakDays: Int = 0,
    val last7DayTotals: List<Int> = List(7) { 0 }
)

class CountersViewModel(
    private val counterRepository: CounterRepository,
    private val aspectRepository: AspectRepository,
    private val weekRepository: WeekRepository,
    private val wellnessRepository: WellnessRepository
) : ViewModel() {

    private val counterService = com.lifeops.app.connection.service.CounterService(counterRepository)

    // Stable within the current week; the VM is recreated on navigation, which re-reads it.
    private val weekKey = DateUtil.weekIndexFor(System.currentTimeMillis())
    private val today = LocalDate.now()
    private val dashboardStart = today.minusDays(29).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString()

    private val _uiState = MutableStateFlow(CountersUiState())
    val uiState: StateFlow<CountersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                combine(
                    counterRepository.observeAll(),
                    aspectRepository.observeAspects(),
                    aspectRepository.observeCategories(),
                    counterRepository.observeWeeklyTotalsByCounter(weekKey),
                    counterRepository.observeCumulativeTotalsByCounter()
                ) { counters, aspects, categories, weekly, cumulative ->
                    CounterSources(counters, aspects, categories, weekly, cumulative)
                },
                counterRepository.observeDailyTotalsSince(dashboardStart)
            ) { sources, daily ->
                val counters = sources.counters
                val aspects = sources.aspects
                val categories = sources.categories
                val weekly = sources.weekly
                val cumulative = sources.cumulative
                val activeCounters = counters.filterNot { it.isArchived }
                // The dashboard is habit-only: a plain tally counter no longer inflates the streak
                // and "active habits" numbers. Counters without the flag still appear in the full
                // "All counters" list below.
                val activeHabits = activeCounters.filter { it.isHabit }
                val habitIds = activeHabits.map { it.id }.toSet()
                val todayKey = today.toString()
                val todayTotals = daily.filter { it.dayKey == todayKey }.associate { it.counterId to it.total }
                val dailyByCounter = daily.groupBy { it.counterId }
                // 7-day dots for every counter (habit or not), so the list surfaces patterns too.
                val last7DaysByCounter = counters.associate { counter ->
                    counter.id to lastNDays(today, dailyByCounter[counter.id].orEmpty(), 7)
                }
                val dashboardHabits = activeHabits.map { counter ->
                    val rows = dailyByCounter[counter.id].orEmpty()
                    CounterDashboardHabit(
                        counter = counter,
                        todayTotal = todayTotals[counter.id] ?: 0,
                        weekTotal = weekly[counter.id] ?: 0,
                        cumulativeTotal = cumulative[counter.id] ?: 0,
                        streakDays = streakDays(today, rows),
                        last7Days = lastNDays(today, rows, 7)
                    )
                }.sortedWith(compareByDescending<CounterDashboardHabit> { it.todayTotal > 0 }.thenByDescending { it.streakDays }.thenBy { it.counter.sortOrder })
                CountersUiState(
                    counters = counters,
                    aspects = aspects,
                    categoriesByAspect = categories.groupBy { it.aspectId },
                    weeklyTotals = weekly,
                    cumulativeTotals = cumulative,
                    todayTotals = todayTotals,
                    last7DaysByCounter = last7DaysByCounter,
                    dashboardHabits = dashboardHabits,
                    activeHabitCount = activeHabits.size,
                    touchedTodayCount = dashboardHabits.count { it.todayTotal > 0 },
                    totalToday = todayTotals.filterKeys { it in habitIds }.values.sum(),
                    bestStreakDays = dashboardHabits.maxOfOrNull { it.streakDays } ?: 0,
                    last7DayTotals = (6 downTo 0).map { offset ->
                        val key = today.minusDays(offset.toLong()).toString()
                        daily.filter { it.dayKey == key && it.counterId in habitIds }.sumOf { it.total }
                    }
                )
            }.collect { _uiState.value = it }
        }
    }

    private fun lastNDays(today: LocalDate, rows: List<CounterDailyTotal>, days: Int): List<Int> {
        val totals = rows.associate { it.dayKey to it.total }
        return ((days - 1) downTo 0).map { offset -> totals[today.minusDays(offset.toLong()).toString()] ?: 0 }
    }

    private fun streakDays(today: LocalDate, rows: List<CounterDailyTotal>): Int {
        val activeDays = rows.filter { it.total > 0 }.map { it.dayKey }.toSet()
        var cursor = today
        var streak = 0
        while (activeDays.contains(cursor.toString())) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    fun createCounter(name: String, categoryId: String?, isHabit: Boolean, reminderHour: Int?) {
        if (name.isBlank()) return
        // A reminder only makes sense for a habit; drop it otherwise so state can't go inconsistent.
        val hour = reminderHour?.takeIf { isHabit }
        viewModelScope.launch {
            counterService.create(name.trim(), categoryId, isHabit, hour)
        }
    }

    fun saveCounter(counter: Counter, name: String, categoryId: String?, isHabit: Boolean, reminderHour: Int?) {
        if (name.isBlank()) return
        val hour = reminderHour?.takeIf { isHabit }
        viewModelScope.launch {
            counterService.save(counter, name.trim(), categoryId, isHabit, hour)
        }
    }

    fun setArchived(counter: Counter, archived: Boolean) {
        viewModelScope.launch { counterService.setArchived(counter.id, archived) }
    }

    /** Tap-to-increment: a single tick stamped at now. */
    fun increment(counter: Counter) {
        viewModelScope.launch { counterService.log(counter.id) }
    }

    /** Backdate / bulk: one event of [delta] stamped at [occurredAtMillis]. */
    fun logBackdated(counter: Counter, occurredAtMillis: Long, delta: Int) {
        if (delta == 0) return
        viewModelScope.launch { counterService.log(counter.id, delta = delta, occurredAt = occurredAtMillis) }
    }

    /**
     * Attach a wellness check-in to the moment a habit was marked. Offered (never forced) right
     * after ticking a habit, so completing a habit can double as a quick energy/sensory read.
     * Persists a standard daytime CHECKIN, so it also flows into the wellness reports.
     */
    fun logWellnessCheckin(energy: Int, sensory: Int, note: String?) {
        viewModelScope.launch {
            wellnessRepository.logCheckin(energy = energy, sensory = sensory, note = note)
        }
    }

    fun categoryNameFor(categoryId: String?): String? {
        if (categoryId == null) return null
        return _uiState.value.categoriesByAspect.values.flatten().firstOrNull { it.id == categoryId }?.name
    }
}

class CountersViewModelFactory(
    private val counterRepository: CounterRepository,
    private val aspectRepository: AspectRepository,
    private val weekRepository: WeekRepository,
    private val wellnessRepository: WellnessRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CountersViewModel(counterRepository, aspectRepository, weekRepository, wellnessRepository) as T
}
