package com.lifeops.app.data.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * "Where is the user right now?", answered well enough for weather.
 *
 * Deliberately built on the platform [LocationManager] rather than Play Services: the suite carries
 * no Google Play dependency, and a forecast grid is ~2.5 km square, so a coarse network fix is not a
 * compromise — it is already finer than the answer. That's also why `ACCESS_COARSE_LOCATION` is all
 * this asks for: the fuzzing Android applies to a coarse-only caller cannot move you into a
 * different forecast in any way that matters, and the permission prompt the user sees is the mild
 * one.
 *
 * Cheapest-first: a recent last-known fix costs nothing and is what you get almost every time; only
 * a cold or stale cache spins up a provider for a single active fix, and even that is bounded by a
 * timeout with the stale fix kept as the fallback. Every failure mode is a value in [Fix], not an
 * exception, because the widget has something honest to say about each one.
 */
class DeviceLocationProvider(private val context: Context) {

    /** The outcome of asking for a fix. Every branch is something the UI can phrase for the user. */
    sealed interface Fix {
        data class Located(val latitude: Double, val longitude: Double) : Fix

        /** The runtime permission hasn't been granted — the UI should ask for it. */
        data object PermissionMissing : Fix

        /** Location is switched off device-wide; no permission grant can work around it. */
        data object LocationDisabled : Fix

        /** Permitted and enabled, but no provider produced a fix in time (indoors, airplane mode). */
        data object Unavailable : Fix
    }

    private val locationManager: LocationManager?
        get() = ContextCompat.getSystemService(context, LocationManager::class.java)

    /** True once either location permission is held; coarse alone is enough for weather. */
    fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * A usable fix, or the reason there isn't one. Returns a cached fix younger than [maxAgeMillis]
     * without touching a radio; otherwise requests one active fix, waiting at most [timeoutMillis]
     * before falling back to the freshest stale fix it has.
     */
    suspend fun currentFix(
        maxAgeMillis: Long = FRESH_ENOUGH_MILLIS,
        timeoutMillis: Long = FIX_TIMEOUT_MILLIS
    ): Fix {
        if (!hasPermission()) return Fix.PermissionMissing
        val manager = locationManager ?: return Fix.Unavailable
        if (!LocationManagerCompat.isLocationEnabled(manager)) return Fix.LocationDisabled

        val cached = freshestLastKnown(manager)
        if (cached != null && ageMillis(cached) <= maxAgeMillis) return cached.asFix()

        val provider = activeProvider(manager)
        val fresh = provider?.let {
            withTimeoutOrNull(timeoutMillis) { requestSingleFix(manager, it) }
        }

        // A stale cached fix still beats no answer: you have to travel a long way for it to be
        // wrong about the weather, and the next refresh will correct it.
        return (fresh ?: cached)?.asFix() ?: Fix.Unavailable
    }

    private fun Location.asFix() = Fix.Located(latitude, longitude)

    // Both call sites below are reached only through currentFix(), which returns PermissionMissing
    // before either can run — the grant is checked there rather than demanded in this class's
    // signatures, because "we aren't allowed" is an answer this provider is meant to give.
    /** Newest last-known fix across every provider the device will talk to us about. */
    @SuppressLint("MissingPermission")
    private fun freshestLastKnown(manager: LocationManager): Location? =
        providerPreference()
            .mapNotNull { provider ->
                // Providers a device lacks, and ones a coarse-only grant won't hand over, throw
                // rather than return null — neither is an error worth propagating.
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

    /** The first enabled provider in preference order, for the one active request. */
    private fun activeProvider(manager: LocationManager): String? =
        providerPreference().firstOrNull { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }

    /**
     * Fused first where the platform has it (cheapest accurate answer), then network — a cell/wifi
     * fix is fast, works indoors, and is plenty for a forecast grid — and GPS only as the last
     * resort, since it is the one that costs battery and needs a view of the sky.
     */
    private fun providerPreference(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
    }

    /** One fix from [provider], cancelled with the calling coroutine. Null if the provider fails. */
    @SuppressLint("MissingPermission")
    private suspend fun requestSingleFix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            val executor = Executor { it.run() }
            continuation.invokeOnCancellation { runCatching { signal.cancel() } }
            try {
                LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // SecurityException (grant revoked mid-flight) or an unusable provider: no fix.
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private fun ageMillis(location: Location) = System.currentTimeMillis() - location.time

    companion object {
        /**
         * How old a cached fix may be and still be used as-is. Ten minutes of ordinary movement
         * cannot leave the forecast grid you were in, so re-acquiring would spend battery to
         * confirm the same numbers.
         */
        const val FRESH_ENOUGH_MILLIS = 10 * 60 * 1000L

        /** Cap on the active request. Beyond this the widget is better off showing stale-but-there. */
        const val FIX_TIMEOUT_MILLIS = 15_000L
    }
}
