package com.operations.sandbox.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.lifeops.app.data.model.AlertSeverity
import com.lifeops.app.data.model.WeatherAlert
import com.lifeops.app.data.model.WeatherReport
import com.lifeops.app.util.DayOutlook
import com.lifeops.app.util.WeatherGlyph
import java.util.Locale

/**
 * The weather tile on the Operations Sandbox home screen: what it is doing right now, and what the
 * rest of today holds, for wherever the phone happens to be.
 *
 * It sits directly under the clock because it answers the same kind of question — the ambient facts
 * you want before you have decided to open anything. So it is deliberately one glance deep: a
 * temperature big enough to read at arm's length, the day's high and low, and a single line of
 * detail. Anything more (the hourly strip, radar, which outdoor task fits which window) is LifeOps'
 * weather screen, which is what a tap opens.
 *
 * It shares the dock's translucent surface rather than the clock's ink-on-wallpaper, because unlike
 * the clock it is a thing you can press, and because a card is what keeps a five-line reading
 * legible over a photograph.
 */
@Composable
fun WeatherWidget(
    controller: WeatherWidgetController,
    onOpenWeather: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Re-check on every return to the home screen. A cached fix makes it nearly free, and the
    // forecast is only fetched again once it has actually aged out.
    LifecycleResumeEffect(controller) {
        controller.onResume()
        onPauseOrDispose { }
    }

    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) controller.locate() else controller.onPermissionDeclined()
    }

    Surface(
        onClick = onOpenWeather,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            val report = controller.report
            if (report != null) {
                Reading(
                    report = report,
                    outlook = controller.outlook,
                    followingDevice = controller.followingDevice,
                    note = controller.note,
                    // Someone reading a place they typed into LifeOps can switch to a live one.
                    // Once they do, followingDevice is true and the offer removes itself.
                    canUseMyLocation = controller.canFollowDevice && !controller.permissionDeclined,
                    onUseMyLocation = {
                        permissionRequest.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                )
            } else {
                EmptyState(
                    // While a fix or a first fetch is in flight there is nothing to complain
                    // about yet — say we're working on it rather than that we failed.
                    prompt = if (controller.busy) WeatherWidgetController.Prompt.WORKING
                    else controller.prompt,
                    declinedOnce = controller.permissionDeclined,
                    onAskForLocation = { permissionRequest.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                    onRetry = controller::locate
                )
            }

            // A hairline rather than a spinner: refreshing is routine, and the reading above it
            // stays readable throughout.
            if (controller.busy && report != null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }
        }
    }
}

/** The reading itself. */
@Composable
private fun Reading(
    report: WeatherReport,
    outlook: DayOutlook?,
    followingDevice: Boolean,
    note: String?,
    canUseMyLocation: Boolean,
    onUseMyLocation: () -> Unit
) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val muted = ink.copy(alpha = 0.72f)

    Column {
        report.topAlert?.let { AlertStrip(it) }

        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    WeatherGlyph.forShortForecast(report.current.shortForecast),
                    fontSize = 34.sp
                )
                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${report.current.temperatureF}°",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Light,
                            color = ink
                        )
                        // Only worth the space when the air lies about how it feels.
                        if (report.current.feelsLikeF != report.current.temperatureF) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "feels ${report.current.feelsLikeF}°",
                                style = MaterialTheme.typography.labelMedium,
                                color = muted,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }
                    Text(
                        report.current.shortForecast,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (outlook != null && (outlook.highF != null || outlook.lowF != null)) {
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        outlook.highF?.let {
                            Text(
                                "H $it°",
                                style = MaterialTheme.typography.titleMedium,
                                color = ink
                            )
                        }
                        outlook.lowF?.let {
                            Text(
                                "L $it°",
                                style = MaterialTheme.typography.titleMedium,
                                color = muted
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            DetailLine(
                report = report,
                outlook = outlook,
                followingDevice = followingDevice,
                color = muted
            )

            note?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = muted)
            }

            if (canUseMyLocation) {
                TextButton(
                    onClick = onUseMyLocation,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text("Use my location", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Place · chance of precipitation · wind — the one line of supporting detail. */
@Composable
private fun DetailLine(
    report: WeatherReport,
    outlook: DayOutlook?,
    followingDevice: Boolean,
    color: Color
) {
    val place = report.location.name.ifBlank {
        String.format(Locale.getDefault(), "%.2f, %.2f", report.location.latitude, report.location.longitude)
    }
    val facts = buildList {
        add(place)
        // The window's chance, not the current hour's: "it's dry now but 60% later" is the useful
        // fact, and a 0% line is noise.
        outlook?.precipitationProbabilityPct?.takeIf { it > 0 }?.let { add("$it% chance") }
        report.current.wind.speedMph.takeIf { it > 0 }?.let { speed ->
            val direction = report.current.wind.directionCardinal?.takeIf { it.isNotBlank() }
            add(if (direction != null) "$direction $speed mph" else "$speed mph")
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        // A pin only when the reading really is following the phone; a hand-added place gets no
        // badge it hasn't earned.
        if (followingDevice) {
            Icon(
                Icons.Filled.Place,
                contentDescription = "Your current location",
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            facts.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A watch/warning/advisory, tinted by how much it should alarm you. */
@Composable
private fun AlertStrip(alert: WeatherAlert) {
    val urgent = alert.severity.rank >= AlertSeverity.SEVERE.rank
    val background =
        if (urgent) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.tertiaryContainer
    val foreground =
        if (urgent) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onTertiaryContainer

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(background)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            alert.event,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * What the tile says when it has no reading. Each case names the actual obstacle and, where the
 * user can clear it, offers the one button that does.
 */
@Composable
private fun EmptyState(
    prompt: WeatherWidgetController.Prompt,
    declinedOnce: Boolean,
    onAskForLocation: () -> Unit,
    onRetry: () -> Unit
) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val muted = ink.copy(alpha = 0.72f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        PromptIcon(prompt, tint = muted)
        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                when (prompt) {
                    WeatherWidgetController.Prompt.WORKING -> "Checking the sky…"
                    WeatherWidgetController.Prompt.NEEDS_PERMISSION -> "Weather where you are"
                    WeatherWidgetController.Prompt.LOCATION_OFF -> "Location is off"
                    WeatherWidgetController.Prompt.NO_FIX -> "Couldn't find you"
                    WeatherWidgetController.Prompt.NO_FORECAST -> "No forecast for here"
                },
                style = MaterialTheme.typography.titleSmall,
                color = ink
            )
            Text(
                when (prompt) {
                    WeatherWidgetController.Prompt.WORKING ->
                        "Fetching the current conditions."

                    WeatherWidgetController.Prompt.NEEDS_PERMISSION ->
                        if (declinedOnce) "Add a place by hand on LifeOps' weather screen instead."
                        else "An approximate location picks the right forecast — only the coordinates are sent."

                    WeatherWidgetController.Prompt.LOCATION_OFF ->
                        "Turn location on in system settings, then try again."

                    WeatherWidgetController.Prompt.NO_FIX ->
                        "No position from the device just now — worth another try in a moment."

                    WeatherWidgetController.Prompt.NO_FORECAST ->
                        "Forecasts come from the US National Weather Service, so coverage stops at the US border."
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )
        }

        when (prompt) {
            WeatherWidgetController.Prompt.WORKING -> {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            WeatherWidgetController.Prompt.NEEDS_PERMISSION ->
                if (!declinedOnce) TextButton(onClick = onAskForLocation) { Text("Use my location") }

            else -> TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun PromptIcon(prompt: WeatherWidgetController.Prompt, tint: Color) {
    val icon: ImageVector = when (prompt) {
        WeatherWidgetController.Prompt.LOCATION_OFF -> Icons.Filled.LocationOff
        WeatherWidgetController.Prompt.NO_FORECAST -> Icons.Filled.Warning
        else -> Icons.Filled.MyLocation
    }
    Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}
