package com.lifeops.app.util

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure great-circle distance, so "has the user actually moved?" stays a JVM-testable question
 * rather than an Android one.
 *
 * The device-location path in WeatherRepository is the caller: a fresh GPS fix a few hundred metres
 * from the last one is the same weather, and re-pointing the tracked location at it would throw
 * away a perfectly good cached forecast without changing a single number on screen.
 */
object Geo {

    /** Mean Earth radius (IUGG), metres. */
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Haversine distance in metres between two WGS84 points. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        // min() guards the sqrt against a hair over 1.0 from floating-point rounding at antipodes.
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }
}
