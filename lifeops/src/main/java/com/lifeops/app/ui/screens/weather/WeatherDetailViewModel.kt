package com.lifeops.app.ui.screens.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.TaskWeatherRequirement
import com.lifeops.app.data.model.WeatherLocation
import com.lifeops.app.data.model.WeatherReport
import com.lifeops.app.data.repository.WeatherRepository
import com.lifeops.app.util.BestTime
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.OutdoorAssessment
import com.lifeops.app.util.OutdoorScore
import com.lifeops.app.util.RecommendedWindow
import com.lifeops.app.util.SevereWeatherIntel
import com.lifeops.app.util.WeatherAdvisory
import com.lifeops.app.util.WeatherDetail
import com.lifeops.app.util.WeatherDetailView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WeatherDetailUiState(
    val location: WeatherLocation? = null,
    /** True when this is the device row, so the screen can say "Current location". */
    val followsDevice: Boolean = false,
    val report: WeatherReport? = null,
    val detail: WeatherDetailView? = null,
    val assessment: OutdoorAssessment? = null,
    val advisories: List<WeatherAdvisory> = emptyList(),
    /** The best few daylight windows ahead, weather alone — no task, no calendar. */
    val outdoorWindows: List<RecommendedWindow> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: String? = null
)

/**
 * The detailed weather screen's state — the long answer to the short one on the weather screen's
 * "Today's conditions" card, which is what opens it.
 *
 * Everything here is already in the cache: the hourly and daily products are stored whole on every
 * refresh, so drilling in costs nothing and works offline. The only network call this screen can
 * make is the one the user asks for with the refresh button.
 */
class WeatherDetailViewModel(
    private val locationId: String,
    private val weatherRepository: WeatherRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeatherDetailUiState())
    val uiState: StateFlow<WeatherDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            weatherRepository.observeLocation(locationId).collect { location ->
                _uiState.update {
                    it.copy(
                        location = location,
                        followsDevice = weatherRepository.isDeviceLocation(locationId)
                    )
                }
            }
        }
        viewModelScope.launch {
            weatherRepository.observeReport(locationId).collect { report ->
                _uiState.update { it.copy(report = report).withDerivations(report) }
            }
        }
    }

    /** Re-derive everything the screen shows from one cached report. */
    private fun WeatherDetailUiState.withDerivations(report: WeatherReport?): WeatherDetailUiState {
        if (report == null) {
            return copy(detail = null, assessment = null, advisories = emptyList(), outdoorWindows = emptyList())
        }
        val now = DateUtil.now()
        val daylight = report.daily.filter { it.isDaytime }
        return copy(
            detail = WeatherDetail.build(report, nowIso = now),
            assessment = OutdoorScore.forCurrent(report.current, report.alerts),
            advisories = SevereWeatherIntel.advise(report.alerts, report.hourly, now),
            // A requirement with nothing set asks only "when is it simply nicest out?" — the
            // task-aware version of this ranking lives on the weather screen's cards.
            outdoorWindows = BestTime.recommend(TaskWeatherRequirement(taskId = ""), daylight)
                .filter { !it.disqualified }
                .take(BEST_WINDOWS_SHOWN)
        )
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, message = null) }
            val result = weatherRepository.refresh(locationId)
            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    message = if (result.isFailure) "Couldn't refresh — showing the last saved reading." else null
                )
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    companion object {
        /** Enough to choose between, few enough to read at a glance. */
        private const val BEST_WINDOWS_SHOWN = 3
    }
}

class WeatherDetailViewModelFactory(
    private val locationId: String,
    private val weatherRepository: WeatherRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        WeatherDetailViewModel(locationId, weatherRepository) as T
}
