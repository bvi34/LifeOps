package com.lifeops.app.ui.screens.counters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Category
import com.lifeops.app.data.model.Counter
import com.lifeops.app.data.repository.AspectRepository
import com.lifeops.app.data.repository.CounterRepository
import com.lifeops.app.data.repository.WeekRepository
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

data class CountersUiState(
    val counters: List<Counter> = emptyList(),
    val aspects: List<Aspect> = emptyList(),
    val categoriesByAspect: Map<String, List<Category>> = emptyMap(),
    val weeklyTotals: Map<String, Int> = emptyMap(),   // counterId -> count this week
    val cumulativeTotals: Map<String, Int> = emptyMap() // counterId -> all-time count
)

class CountersViewModel(
    private val counterRepository: CounterRepository,
    private val aspectRepository: AspectRepository,
    private val weekRepository: WeekRepository
) : ViewModel() {

    // Stable within the current week; the VM is recreated on navigation, which re-reads it.
    private val weekKey = DateUtil.weekIndexFor(System.currentTimeMillis())

    private val _uiState = MutableStateFlow(CountersUiState())
    val uiState: StateFlow<CountersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                counterRepository.observeAll(),
                aspectRepository.observeAspects(),
                aspectRepository.observeCategories(),
                counterRepository.observeWeeklyTotalsByCounter(weekKey),
                counterRepository.observeCumulativeTotalsByCounter()
            ) { counters, aspects, categories, weekly, cumulative ->
                CountersUiState(
                    counters = counters,
                    aspects = aspects,
                    categoriesByAspect = categories.groupBy { it.aspectId },
                    weeklyTotals = weekly,
                    cumulativeTotals = cumulative
                )
            }.collect { _uiState.value = it }
        }
    }

    fun createCounter(name: String, categoryId: String?) {
        if (name.isBlank()) return
        viewModelScope.launch { counterRepository.createCounter(UUID.randomUUID().toString(), name.trim(), categoryId) }
    }

    fun saveCounter(counter: Counter, name: String, categoryId: String?) {
        if (name.isBlank()) return
        viewModelScope.launch { counterRepository.update(counter.copy(name = name.trim(), categoryId = categoryId)) }
    }

    fun setArchived(counter: Counter, archived: Boolean) {
        viewModelScope.launch { counterRepository.setArchived(counter, archived) }
    }

    /** Tap-to-increment: a single tick stamped at now. */
    fun increment(counter: Counter) {
        viewModelScope.launch { counterRepository.logEvent(counter.id) }
    }

    /** Backdate / bulk: one event of [delta] stamped at [occurredAtMillis]. */
    fun logBackdated(counter: Counter, occurredAtMillis: Long, delta: Int) {
        if (delta == 0) return
        viewModelScope.launch { counterRepository.logBulk(counter.id, occurredAtMillis, delta) }
    }

    fun categoryNameFor(categoryId: String?): String? {
        if (categoryId == null) return null
        return _uiState.value.categoriesByAspect.values.flatten().firstOrNull { it.id == categoryId }?.name
    }
}

class CountersViewModelFactory(
    private val counterRepository: CounterRepository,
    private val aspectRepository: AspectRepository,
    private val weekRepository: WeekRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CountersViewModel(counterRepository, aspectRepository, weekRepository) as T
}
