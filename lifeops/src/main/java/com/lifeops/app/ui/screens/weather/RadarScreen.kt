@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.weather

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.WeatherLocation
import com.lifeops.app.data.weather.BaseMapLayer
import com.lifeops.app.data.weather.REFLECTIVITY_SCALE
import com.lifeops.app.data.weather.RadarProduct
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.util.MapCamera
import com.lifeops.app.util.MapLayout
import java.util.Locale

/**
 * Radar — what the sky is doing around the pin, reached by tapping the current-conditions card on
 * the weather screen.
 *
 * The pin is the tracked location itself, which is the whole point of opening this from a reading:
 * when that location is the device row it is literally *you are here*, and when it is a place typed
 * in by hand it is that place. Either way the storm on screen is the one the numbers came from.
 *
 * The map is assembled here from raster tiles rather than delegated to a maps SDK: no API key, no
 * new dependency, and the same `HttpURLConnection` the forecast already travels over. Panning and
 * pinching move the camera freely, and the pin stays where it belongs on the globe — the button in
 * the corner brings the camera back to it.
 */
@Composable
fun RadarScreen(
    viewModel: RadarViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var layerMenuOpen by remember { mutableStateOf(false) }

    // NaN latitude means "not positioned yet" — the location arrives from the database a moment
    // after the screen does, and the camera adopts it exactly once so a pan survives every
    // subsequent emission (and a rotation).
    var camera by rememberSaveable(stateSaver = CameraSaver) {
        mutableStateOf(MapCamera(Double.NaN, Double.NaN, MapCamera.DEFAULT_ZOOM))
    }
    val location = state.location
    LaunchedEffect(location?.id) {
        val fixed = location ?: return@LaunchedEffect
        if (camera.latitude.isNaN()) {
            camera = MapCamera(fixed.latitude, fixed.longitude, MapCamera.DEFAULT_ZOOM)
        }
    }

    LaunchedEffect(state.browserUrl) {
        state.browserUrl?.let { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            viewModel.clearBrowserUrl()
        }
    }

    Scaffold(
        topBar = {
            AppHeader(
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    IconButton(onClick = { viewModel.toggleRadar() }) {
                        Icon(
                            if (state.radarVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (state.radarVisible) "Hide radar" else "Show radar"
                        )
                    }
                    IconButton(onClick = { viewModel.refreshTiles() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh radar")
                    }
                    Box {
                        IconButton(onClick = { layerMenuOpen = true }) {
                            Icon(Icons.Default.Layers, contentDescription = "Radar layers")
                        }
                        DropdownMenu(expanded = layerMenuOpen, onDismissRequest = { layerMenuOpen = false }) {
                            RadarProduct.entries.forEach { product ->
                                DropdownMenuItem(
                                    text = { Text(product.label) },
                                    leadingIcon = {
                                        RadioButton(
                                            selected = product == state.product,
                                            onClick = { layerMenuOpen = false; viewModel.selectProduct(product) }
                                        )
                                    },
                                    onClick = { layerMenuOpen = false; viewModel.selectProduct(product) }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Open NWS radar site") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                                onClick = { layerMenuOpen = false; viewModel.openOfficialRadar() }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (camera.latitude.isNaN()) {
                LocatingState()
            } else {
                MapSurface(
                    camera = camera,
                    onCameraChange = { camera = it },
                    state = state,
                    viewModel = viewModel
                )
                MapControls(
                    onZoomIn = { camera = camera.zoomedTo(camera.zoom + 1f) },
                    onZoomOut = { camera = camera.zoomedTo(camera.zoom - 1f) },
                    onRecenter = {
                        location?.let { camera = camera.copy(latitude = it.latitude, longitude = it.longitude) }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                )
                if (state.radarVisible) {
                    ReflectivityLegend(
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    )
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScaleBar(camera)
                    PinCard(state)
                }
            }
        }
    }
}

@Composable
private fun LocatingState() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(12.dp))
        Text("Placing the pin…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MapSurface(
    camera: MapCamera,
    onCameraChange: (MapCamera) -> Unit,
    state: RadarUiState,
    viewModel: RadarViewModel
) {
    val pinColor = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        RadarMapCanvas(
            camera = camera,
            onCameraChange = onCameraChange,
            baseLayer = BaseMapLayer.STREETS,
            radarLayer = state.product.layer.takeIf { state.radarVisible },
            pin = state.location?.let { it.latitude to it.longitude },
            widthPixels = widthPx,
            heightPixels = heightPx,
            pinColor = pinColor,
            tileRevision = viewModel.tileRevision,
            tileFor = viewModel::tile,
            onTilesNeeded = viewModel::requestTiles
        )
    }
}

@Composable
private fun MapControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MapButton(onRecenter) { Icon(Icons.Default.MyLocation, contentDescription = "Back to the pin") }
        MapButton(onZoomIn) { Icon(Icons.Default.Add, contentDescription = "Zoom in") }
        MapButton(onZoomOut) { Icon(Icons.Default.Remove, contentDescription = "Zoom out") }
    }
}

@Composable
private fun MapButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * How far is that? A map without a scale is a picture; with one it's a measurement — and on radar
 * the only question that matters is how many miles away the weather is.
 */
@Composable
private fun ScaleBar(camera: MapCamera, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val maxPixels = with(density) { 140.dp.toPx() }
    val (label, pixels) = MapLayout.scaleBar(camera.metersPerPixel, maxPixels) ?: return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        // Its own backing, because street tiles go from near-white to near-black and a bar that
        // picks either one disappears over half of them.
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 2.dp
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(with(density) { pixels.toDp() })
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The reflectivity ramp, so the colours on the map mean something specific. */
@Composable
private fun ReflectivityLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row {
                REFLECTIVITY_SCALE.forEach { stop ->
                    Box(Modifier.size(width = LEGEND_SWATCH_WIDTH, height = 8.dp).background(Color(stop.colorArgb)))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.width(LEGEND_SWATCH_WIDTH * REFLECTIVITY_SCALE.size),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Only the named stops get a caption; the unnamed ones are the ramp between them.
                REFLECTIVITY_SCALE.filter { it.label.isNotBlank() }.forEach {
                    Text(it.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** What the pin is, and the reading it came from — the map's caption. */
@Composable
private fun PinCard(state: RadarUiState, modifier: Modifier = Modifier) {
    val location = state.location ?: return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        location.displayName(state.followsDevice),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        formatCoordinates(location.latitude, location.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                state.current?.let { current ->
                    Text(
                        "${current.temperatureF}°",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            val provenance = buildList {
                state.current?.shortForecast?.takeIf { it.isNotBlank() }?.let { add(it) }
                add(state.product.label)
                state.stationId?.let { add("Radar $it") }
            }.joinToString("  ·  ")
            Text(
                provenance,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            state.alerts.maxByOrNull { it.severity.rank }?.let { alert ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        alert.event.ifBlank { "Weather alert" },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                "${BaseMapLayer.STREETS.attribution}  ·  ${state.product.layer.attribution}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

/** "Current location" beats a coordinate, and a coordinate beats an empty line. */
internal fun WeatherLocation.displayName(followsDevice: Boolean): String = when {
    name.isNotBlank() -> name
    followsDevice -> "Current location"
    else -> formatCoordinates(latitude, longitude)
}

internal fun formatCoordinates(latitude: Double, longitude: Double): String = String.format(
    Locale.US,
    "%.4f° %s, %.4f° %s",
    kotlin.math.abs(latitude), if (latitude >= 0) "N" else "S",
    kotlin.math.abs(longitude), if (longitude >= 0) "E" else "W"
)

/** One swatch of the reflectivity ramp; the legend's width is a multiple of it. */
private val LEGEND_SWATCH_WIDTH = 22.dp

private val CameraSaver = listSaver<MapCamera, Any>(
    save = { listOf(it.latitude, it.longitude, it.zoom) },
    restore = { MapCamera(it[0] as Double, it[1] as Double, it[2] as Float) }
)
