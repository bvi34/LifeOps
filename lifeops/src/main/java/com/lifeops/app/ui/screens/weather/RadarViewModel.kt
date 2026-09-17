package com.lifeops.app.ui.screens.weather

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.CurrentConditions
import com.lifeops.app.data.model.WeatherAlert
import com.lifeops.app.data.model.WeatherLocation
import com.lifeops.app.data.repository.WeatherRepository
import com.lifeops.app.data.weather.RadarProduct
import com.lifeops.app.data.weather.TileLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class RadarUiState(
    val location: WeatherLocation? = null,
    /** True when the pin is the device row — "you are here" rather than "this place you saved". */
    val followsDevice: Boolean = false,
    val current: CurrentConditions? = null,
    val alerts: List<WeatherAlert> = emptyList(),
    val product: RadarProduct = RadarProduct.NWS_REFLECTIVITY,
    /** Lets the user lift the overlay off to read the map underneath it. */
    val radarVisible: Boolean = true,
    /** Nearest NWS radar station id, resolved lazily; shown as provenance for what's on screen. */
    val stationId: String? = null,
    /** One-shot: the official radar page to hand to the browser, then clear. */
    val browserUrl: String? = null
)

/**
 * The radar screen's state: what the pin is, what's cached about it, and the tiles the map is
 * currently made of.
 *
 * Tiles deliberately do *not* live in [RadarUiState]. A screenful is a couple of hundred
 * independent bitmaps arriving in whatever order the network returns them, and threading each one
 * through an immutable state object would rebuild the whole map on every arrival. Instead the
 * [TileLoader]'s cache *is* the store, and [tileRevision] is a single counter the canvas reads so a
 * newly-landed tile invalidates the draw pass and nothing else.
 */
class RadarViewModel(
    private val locationId: String,
    private val weatherRepository: WeatherRepository,
    private val tileLoader: TileLoader = TileLoader()
) : ViewModel() {

    private val _uiState = MutableStateFlow(RadarUiState())
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    /** Bumped whenever a tile lands, so the map redraws without any of its state changing. */
    var tileRevision by mutableIntStateOf(0)
        private set

    private val inFlight = mutableSetOf<String>()

    /**
     * Tiles that came back empty. Remembered so a coverage hole (radar simply has nothing for that
     * square) isn't re-requested on every single draw pass; a manual refresh is what clears it.
     */
    private val unavailable = mutableSetOf<String>()

    /** Politeness, not performance: both tile services are free and shared. */
    private val gate = Semaphore(MAX_PARALLEL_TILES)

    init {
        viewModelScope.launch {
            weatherRepository.observeLocation(locationId).collect { location ->
                _uiState.update {
                    it.copy(location = location, followsDevice = weatherRepository.isDeviceLocation(locationId))
                }
            }
        }
        viewModelScope.launch {
            weatherRepository.observeReport(locationId).collect { report ->
                _uiState.update { it.copy(current = report?.current, alerts = report?.alerts.orEmpty()) }
            }
        }
        viewModelScope.launch {
            // Provenance only, and only worth one network call: which NWS radar the pin sits under.
            val station = weatherRepository.radarStationFor(locationId)
            _uiState.update { it.copy(stationId = station) }
        }
    }

    // --- Tiles ---

    /** The bitmap for [url] if it is already in memory; null means "not yet" or "never". */
    fun tile(url: String): ImageBitmap? = tileLoader.cached(url)

    /** Ask for every tile in [urls] that isn't cached, already loading, or known to be empty. */
    fun requestTiles(urls: Collection<String>) {
        urls.forEach { url ->
            if (url in unavailable || tileLoader.cached(url) != null) return@forEach
            if (!inFlight.add(url)) return@forEach
            viewModelScope.launch {
                val bitmap = gate.withPermit { tileLoader.load(url) }
                inFlight.remove(url)
                if (bitmap == null) unavailable += url else tileRevision++
            }
        }
    }

    /** Throw away every tile so the radar is re-fetched rather than re-shown. */
    fun refreshTiles() {
        tileLoader.clear()
        unavailable.clear()
        tileRevision++
    }

    // --- Layers ---

    fun selectProduct(product: RadarProduct) {
        if (product == _uiState.value.product) return
        _uiState.update { it.copy(product = product) }
        // The old product's tiles are a different picture of the same sky; drop them rather than
        // leave half a mosaic showing while the new one fills in.
        refreshTiles()
    }

    fun toggleRadar() = _uiState.update { it.copy(radarVisible = !it.radarVisible) }

    /** Hand the browser the official NWS radar page — the escape hatch when tiles won't come. */
    fun openOfficialRadar() {
        val station = _uiState.value.stationId
        val url = if (station != null) "https://radar.weather.gov/station/$station/standard"
        else "https://radar.weather.gov"
        _uiState.update { it.copy(browserUrl = url) }
    }

    fun clearBrowserUrl() = _uiState.update { it.copy(browserUrl = null) }

    companion object {
        private const val MAX_PARALLEL_TILES = 6
    }
}

class RadarViewModelFactory(
    private val locationId: String,
    private val weatherRepository: WeatherRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RadarViewModel(locationId, weatherRepository) as T
}
