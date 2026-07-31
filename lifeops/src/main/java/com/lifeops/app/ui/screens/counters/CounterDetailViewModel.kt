package com.lifeops.app.ui.screens.counters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.db.dao.CounterWeeklyTotal
import com.lifeops.app.data.model.Counter
import com.lifeops.app.data.model.CounterEvent
import com.lifeops.app.data.repository.CounterRepository
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class CounterDetailUiState(
    val counter: Counter? = null,
    val weekCount: Int = 0,
    val totalCount: Int = 0,
    val trend: List<CounterWeeklyTotal> = emptyList(),  // oldest week first
    val events: List<CounterEvent> = emptyList()        // newest first
)

class CounterDetailViewModel(
    private val counterId: String,
    private val counterRepository: CounterRepository
) : ViewModel() {

    private val counterService = com.lifeops.app.connection.service.CounterService(counterRepository)

    private val weekKey = DateUtil.weekIndexFor(System.currentTimeMillis())

    private val _uiState = MutableStateFlow(CounterDetailUiState())
    val uiState: StateFlow<CounterDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                counterRepository.observeCounter(counterId),
                counterRepository.observeWeeklyTotal(counterId, weekKey),
                counterRepository.observeCumulativeTotal(counterId),
                counterRepository.observeWeeklyTrend(counterId),
                counterRepository.observeEvents(counterId)
            ) { counter, week, total, trend, events ->
                CounterDetailUiState(counter, week, total, trend, events)
            }.collect { _uiState.value = it }
        }
    }

    fun increment() {
        viewModelScope.launch { counterService.log(counterId) }
    }
}

class CounterDetailViewModelFactory(
    private val counterId: String,
    private val counterRepository: CounterRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CounterDetailViewModel(counterId, counterRepository) as T
}
