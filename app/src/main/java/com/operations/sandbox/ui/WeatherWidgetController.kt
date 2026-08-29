package com.operations.sandbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lifeops.app.LifeOpsApp
import com.lifeops.app.data.model.WeatherReport
import com.lifeops.app.data.repository.WeatherRepository
import com.lifeops.app.data.weather.DeviceLocationProvider
import com.lifeops.app.util.DayOutlook
import com.lifeops.app.util.TodayOutlook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Why the home screen's weather widget shows what it shows.
 *
 * It owns no data of its own. LifeOps already has the whole weather stack — the NWS client, the
 * offline-first Room cache, the background refresh — so the sandbox borrows it rather than growing
 * a second one, and the reading the widget shows is the same reading LifeOps' weather screen and
 * its counter stamps see. What the sandbox adds is the *choice of place*: it asks the device where
 * it is and keeps LifeOps' device-location row pointed there.
 *
 * The reading always comes from the cache, so the widget paints on the first frame and a phone in
 * airplane mode still shows this morning's forecast rather than an error. The network is touched
 * only once the cache has aged out, and a failed touch is a quiet line under a real reading, never
 * a replacement for one.
 *
 * Someone who never grants location is not left with a dead tile: the widget falls back to the
 * first place they added in LifeOps and keeps that one fresh instead.
 */
@Stable
class WeatherWidgetController(
    private val weather: WeatherRepository,
    private val device: DeviceLocationProvider,
    private val scope: CoroutineScope
) {

    /** The "nothing to show yet" cases — only rendered when there is no cached reading instead. */
    enum class Prompt {
        /** Locating, or waiting for a first forecast to land. */
        WORKING,

        /** No location permission, and no hand-added place to fall back to. */
        NEEDS_PERMISSION,

        /** Permission held, but location is switched off device-wide. */
        LOCATION_OFF,

        /** Permitted and enabled, and still no provider produced a fix. */
        NO_FIX,

        /** We know where the user is; NWS wouldn't give a forecast for it. */
        NO_FORECAST
    }

    var report by mutableStateOf<WeatherReport?>(null)
        private set

    var outlook by mutableStateOf<DayOutlook?>(null)
        private set

    var prompt by mutableStateOf(Prompt.WORKING)
        private set

    /** True when the reading on screen is for wherever the phone is now. */
    var followingDevice by mutableStateOf(false)
        private set

    /** A fix or a fetch is in flight. */
    var busy by mutableStateOf(false)
        private set

    /** A quiet line under the reading when something went wrong but there is still data to show. */
    var note by mutableStateOf<String?>(null)
        private set

    /** The user has said no to the location prompt. Asking again from the same button would just
     *  be a button that does nothing, so the tile stops offering it and points elsewhere instead. */
    var permissionDeclined by mutableStateOf(false)
        private set

    /** Worth offering "use my location" — they have a reading, but not one that follows them. */
    val canFollowDevice: Boolean get() = !followingDevice

    private var started = false

    /** The fix currently in flight, if any. The home screen starts one on first composition and
     *  another on resume, and both arrive together on launch — without this the first open would
     *  fetch the same forecast twice. It also makes "Try again" un-mashable. */
    private var fixInFlight: Job? = null

    /** The system permission dialog came back with a no. */
    fun onPermissionDeclined() {
        permissionDeclined = true
        prompt = Prompt.NEEDS_PERMISSION
    }

    /** Begin observing the cache and go looking for the device. Safe to call on recomposition. */
    fun start() {
        if (started) return
        started = true
        scope.launch { observeCachedReading() }
        locate()
    }

    /**
     * Coming back to the home screen. A cached fix makes this nearly free, and it is exactly when
     * the user cares that the number is current — they have just picked the phone up.
     */
    fun onResume() {
        if (started) locate()
    }

    /**
     * Stream whichever tracked location is the relevant one. The device row sorts ahead of every
     * hand-added place, so "first" means "where you are" whenever we know it and falls back to the
     * user's own first location when we don't.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeCachedReading() {
        weather.observeLocations()
            .flatMapLatest { tracked ->
                val target = tracked.firstOrNull()
                followingDevice = target != null && weather.isDeviceLocation(target.id)
                if (target == null) flowOf(null) else weather.observeReport(target.id)
            }
            .collect { cached ->
                report = cached
                outlook = cached?.let { TodayOutlook.from(it.daily) }
            }
    }

    /**
     * Ask the device where it is, point the tracked location at it, and top the forecast up if it
     * has aged out. Every failure lands on a [Prompt] rather than an exception, and none of them
     * clears a reading that is already on screen.
     */
    fun locate() {
        if (fixInFlight?.isActive == true) return
        fixInFlight = scope.launch {
            busy = true
            try {
                when (val fix = device.currentFix()) {
                    is DeviceLocationProvider.Fix.Located -> {
                        // A grant given elsewhere (system settings) counts; the tile shouldn't keep
                        // remembering a refusal the user has since reversed.
                        permissionDeclined = false
                        val here = weather.setDeviceLocation(fix.latitude, fix.longitude)
                        refreshIfStale(here.id)
                    }

                    DeviceLocationProvider.Fix.PermissionMissing -> fallBack(Prompt.NEEDS_PERMISSION)
                    DeviceLocationProvider.Fix.LocationDisabled -> fallBack(Prompt.LOCATION_OFF)
                    DeviceLocationProvider.Fix.Unavailable -> fallBack(Prompt.NO_FIX)
                }
            } finally {
                busy = false
            }
        }
    }

    /**
     * No fix, for [reason]. There may still be a tracked location worth showing — the last place we
     * located before the grant was revoked, or one the user typed into LifeOps — in which case that
     * becomes the widget's subject and is refreshed like any other, and the missing fix never needs
     * mentioning. Only with nothing at all to show does the reason reach the screen.
     */
    private suspend fun fallBack(reason: Prompt) {
        val fallback = weather.observeLocations().first().firstOrNull()
        if (fallback == null) prompt = reason else refreshIfStale(fallback.id)
    }

    /** Go to NWS only once the newest cached reading has aged out. */
    private suspend fun refreshIfStale(locationId: String) {
        val age = weather.cachedSnapshotAgeMinutes(locationId)
        if (age != null && age < STALE_AFTER_MINUTES) {
            note = null
            return
        }
        if (weather.refresh(locationId).isSuccess) {
            note = null
            return
        }
        // Nothing cached and nothing fetched: say why the tile is empty rather than spinning
        // forever. NWS is US-only, which is the likeliest reason a valid fix yields no forecast.
        if (report == null) prompt = Prompt.NO_FORECAST
        else note = "Couldn't refresh — showing the last saved reading."
    }

    companion object {
        /**
         * How old the cached reading may be before the widget goes to the network. Forty-five
         * minutes keeps "right now" honest while staying well clear of hitting a free public API
         * every time someone glances at their home screen.
         */
        const val STALE_AFTER_MINUTES = 45L
    }
}

/**
 * Build the controller against LifeOps' weather stack, or return null when LifeOps isn't installed
 * in this process — the widget then simply isn't part of the home screen, rather than crashing it.
 *
 * Held at the shell level like [BackupController], so backing out to Settings and returning doesn't
 * restart a location fix or drop an in-flight refresh.
 */
@Composable
fun rememberWeatherWidgetController(): WeatherWidgetController? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        LifeOpsApp.getOrNull()?.let { lifeOps ->
            WeatherWidgetController(
                weather = lifeOps.weatherRepository,
                device = DeviceLocationProvider(context.applicationContext),
                scope = scope
            )
        }
    }
}
