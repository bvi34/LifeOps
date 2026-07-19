package com.lifeops.app.ui.screens.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.ActivityTemplate
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskWeatherRequirement
import com.lifeops.app.data.model.WeatherLocation
import com.lifeops.app.data.model.WeatherReport
import com.lifeops.app.data.repository.ActivityTemplateRepository
import com.lifeops.app.data.repository.PersonRepository
import com.lifeops.app.data.repository.TaskRepository
import com.lifeops.app.data.repository.WeatherRepository
import com.lifeops.app.data.repository.WeekRepository
import com.lifeops.app.util.BestTime
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.OutdoorAssessment
import com.lifeops.app.util.OutdoorScore
import com.lifeops.app.util.SevereWeatherIntel
import com.lifeops.app.util.WeatherCard
import com.lifeops.app.util.WeatherCards
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WeatherUiState(
    val locations: List<WeatherLocation> = emptyList(),
    val selectedLocationId: String? = null,
    val report: WeatherReport? = null,
    val assessment: OutdoorAssessment? = null,
    val cards: List<WeatherCard> = emptyList(),
    val weekTasks: List<Task> = emptyList(),
    val requirements: Map<String, TaskWeatherRequirement> = emptyMap(),
    val people: List<Person> = emptyList(),
    val taskPeople: Map<String, List<String>> = emptyMap(),
    val activityTemplates: List<ActivityTemplate> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: String? = null,
    /** One-shot: a radar URL for the screen to open in the browser, then clear. */
    val radarUrl: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherViewModel(
    private val weatherRepository: WeatherRepository,
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository,
    private val personRepository: PersonRepository,
    private val activityTemplateRepository: ActivityTemplateRepository
) : ViewModel() {

    private val selectedId = MutableStateFlow<String?>(null)
    private val _uiState = MutableStateFlow(WeatherUiState())
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            weatherRepository.observeLocations().collect { locs ->
                val sel = selectedId.value
                if (sel == null || locs.none { it.id == sel }) selectedId.value = locs.firstOrNull()?.id
                _uiState.update { it.copy(locations = locs, selectedLocationId = selectedId.value) }
            }
        }
        viewModelScope.launch {
            selectedId
                .flatMapLatest { id -> if (id == null) flowOf(null) else weatherRepository.observeReport(id) }
                .collect { report ->
                    _uiState.update { it.copy(selectedLocationId = selectedId.value, report = report) }
                    recompute()
                }
        }
        viewModelScope.launch {
            weatherRepository.observeRequirements().collect { reqs ->
                _uiState.update { it.copy(requirements = reqs) }; recompute()
            }
        }
        viewModelScope.launch {
            val week = weekRepository.getOrCreateCurrentWeek()
            taskRepository.observeTasksForWeek(week.id).collect { tasks ->
                _uiState.update { it.copy(weekTasks = tasks) }; recompute()
            }
        }
        viewModelScope.launch {
            personRepository.observeAll().collect { people ->
                _uiState.update { it.copy(people = people) }; recompute()
            }
        }
        viewModelScope.launch {
            personRepository.observeTaskPeople().collect { links ->
                _uiState.update { it.copy(taskPeople = links) }; recompute()
            }
        }
        viewModelScope.launch {
            activityTemplateRepository.observeAll().collect { templates ->
                _uiState.update { it.copy(activityTemplates = templates) }
            }
        }
    }

    /** Recompute the outdoor rating, best-window, and cards from the current cached state. */
    private fun recompute() {
        val s = _uiState.value
        val report = s.report
        if (report == null) {
            _uiState.update { it.copy(assessment = null, cards = emptyList()) }
            return
        }
        val assessment = OutdoorScore.forCurrent(report.current, report.alerts)
        val dayPeriods = report.daily.filter { it.isDaytime }

        val generalBest = if (dayPeriods.isNotEmpty()) {
            BestTime.best(TaskWeatherRequirement(taskId = ""), dayPeriods)?.label
        } else null

        val taskCards = s.weekTasks.mapNotNull { task ->
            val req = s.requirements[task.id] ?: return@mapNotNull null
            if (!req.outdoorPreferred) return@mapNotNull null
            val involvedIds = s.taskPeople[task.id] ?: emptyList()
            val involvedPeople = s.people.filter { it.id in involvedIds }
            val window = BestTime.best(req, dayPeriods, involvedPeople) ?: return@mapNotNull null
            WeatherCard.TaskRecommendation(
                taskTitle = task.title,
                windowLabel = window.label,
                matchPercent = window.matchPercent,
                reasons = window.reasons.take(3)
            )
        }

        val advisories = SevereWeatherIntel.advise(report.alerts, report.hourly, DateUtil.now())
        val cards = WeatherCards.build(report.alerts, assessment, generalBest, taskCards, advisories)
        _uiState.update { it.copy(assessment = assessment, cards = cards) }
    }

    fun selectLocation(id: String) {
        selectedId.value = id
        _uiState.update { it.copy(selectedLocationId = id) }
    }

    fun addLocation(latitude: Double, longitude: Double, name: String) {
        viewModelScope.launch {
            val created = weatherRepository.addLocation(latitude, longitude, name)
            selectedId.value = created.id
            refresh()
        }
    }

    fun deleteLocation(id: String) {
        viewModelScope.launch { weatherRepository.deleteLocation(id) }
    }

    /** Manual refresh of the selected location from NWS. */
    fun refresh() {
        val id = selectedId.value ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, message = null) }
            val result = weatherRepository.refresh(id)
            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    message = if (result.isFailure) "Couldn't refresh weather — showing last saved data." else null
                )
            }
        }
    }

    fun setRequirement(requirement: TaskWeatherRequirement) {
        viewModelScope.launch { weatherRepository.setRequirement(requirement) }
    }

    /** On-demand radar: resolve the nearest station, then hand the screen a URL to open. Falls
     *  back to the national radar if the station lookup fails (offline / no coverage). */
    fun openRadar() {
        val id = selectedId.value ?: return
        viewModelScope.launch {
            val station = weatherRepository.radarStationFor(id)
            val url = if (station != null) "https://radar.weather.gov/station/$station/standard"
            else "https://radar.weather.gov"
            _uiState.update { it.copy(radarUrl = url) }
        }
    }

    fun clearRadarUrl() {
        _uiState.update { it.copy(radarUrl = null) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}

class WeatherViewModelFactory(
    private val weatherRepository: WeatherRepository,
    private val weekRepository: WeekRepository,
    private val taskRepository: TaskRepository,
    private val personRepository: PersonRepository,
    private val activityTemplateRepository: ActivityTemplateRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        WeatherViewModel(
            weatherRepository, weekRepository, taskRepository, personRepository, activityTemplateRepository
        ) as T
}
