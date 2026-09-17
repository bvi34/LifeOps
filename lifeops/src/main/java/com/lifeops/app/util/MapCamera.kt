package com.lifeops.app.util

import com.lifeops.app.data.weather.TileLayer
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * The radar map's camera and the tile layout it implies — pure, Android-free, and therefore
 * testable, in the same spirit as [TileMath] underneath it. Everything about *what the map is
 * looking at* and *which squares that needs* is decided here; the Compose layer only paints the
 * answer.
 *
 * Zoom is a `Float` rather than an integer step because a pinch is continuous. The whole part
 * picks the tile level actually requested from the server; the fraction becomes the scale those
 * 256-pixel tiles are drawn at, which is what makes a pinch smooth instead of a jump between two
 * fixed levels.
 */
data class MapCamera(
    val latitude: Double,
    val longitude: Double,
    val zoom: Float
) {
    /** The integer tile level the camera stands on; the fraction above it becomes scale. */
    val sourceZoom: Int get() = floor(zoom).toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** Screen width of one [sourceZoom] tile at this zoom — exactly 256 px at a whole level. */
    val tilePixels: Float get() = TileMath.TILE_SIZE_PX * 2f.pow(zoom - sourceZoom)

    /** Metres per screen pixel here — what a scale bar is measured in. */
    val metersPerPixel: Double get() = TileMath.metersPerPixel(latitude, sourceZoom, tilePixels)

    fun zoomedBy(factor: Float): MapCamera = zoomedTo(zoom + log2(factor))

    fun zoomedTo(level: Float): MapCamera =
        copy(zoom = level.coerceIn(MIN_ZOOM.toFloat(), MAX_ZOOM.toFloat()))

    /**
     * Drag the map by a screen delta. The camera moves against the finger — pulling the map right
     * means looking further west — and the result is re-projected, so the same drag covers fewer
     * degrees of latitude near the pole than at the equator, exactly as the map shows.
     */
    fun panned(dxPixels: Float, dyPixels: Float): MapCamera {
        val z = sourceZoom
        val tile = tilePixels
        if (tile <= 0f) return this
        val x = TileMath.tileX(longitude, z) - dxPixels / tile
        val y = TileMath.tileY(latitude, z) - dyPixels / tile
        val count = TileMath.tileCount(z).toDouble()
        return copy(
            // Latitude clamps (where longitude wraps) so a hard drag can't fling the map off the
            // top of the world into blank space it has no way back from.
            latitude = TileMath.latitudeAt(y.coerceIn(0.0, count), z),
            longitude = TileMath.longitudeAt(x, z)
        )
    }

    companion object {
        const val MIN_ZOOM = 3
        const val MAX_ZOOM = 13

        /** Close enough to place a town, wide enough to see weather coming toward it. */
        const val DEFAULT_ZOOM = 8f

        private fun log2(value: Float): Float =
            if (value <= 0f) 0f else (ln(value.toDouble()) / ln(2.0)).toFloat()
    }
}

/** One tile, and the square of screen it covers. */
data class TilePlacement(val url: String, val left: Float, val top: Float, val size: Float)

/** A position on the viewport, in pixels from its top-left. */
data class MapPoint(val x: Float, val y: Float)

object MapLayout {

    /**
     * The tiles of [layer] needed to fill a [widthPixels] × [heightPixels] viewport for [camera].
     *
     * A layer is only ever asked for imagery it actually publishes: past its own `maxZoom` its
     * deepest tiles are drawn larger instead. That is why radar stays visible — blockier, but
     * honest — when you zoom in past what the radar product carries, while the street map
     * underneath keeps sharpening.
     */
    fun placements(
        layer: TileLayer,
        camera: MapCamera,
        widthPixels: Float,
        heightPixels: Float
    ): List<TilePlacement> {
        if (widthPixels <= 0f || heightPixels <= 0f) return emptyList()
        val z = minOf(camera.sourceZoom, layer.maxZoom)
        val tile = TileMath.TILE_SIZE_PX * 2f.pow(camera.zoom - z)
        val centerX = TileMath.tileX(camera.longitude, z)
        val centerY = TileMath.tileY(camera.latitude, z)
        val radiusX = TileMath.visibleRadius(widthPixels, tile)
        val radiusY = TileMath.visibleRadius(heightPixels, tile)
        val originX = floor(centerX).toInt()
        val originY = floor(centerY).toInt()

        val out = ArrayList<TilePlacement>((2 * radiusX + 1) * (2 * radiusY + 1))
        for (dy in -radiusY..radiusY) {
            for (dx in -radiusX..radiusX) {
                val x = originX + dx
                val y = originY + dy
                val url = layer.urlFor(x, y, z) ?: continue
                out += TilePlacement(
                    url = url,
                    left = widthPixels / 2f + ((x - centerX) * tile).toFloat(),
                    top = heightPixels / 2f + ((y - centerY) * tile).toFloat(),
                    size = tile
                )
            }
        }
        return out
    }

    /** Where a latitude/longitude lands on the viewport for [camera]. */
    fun screenPointOf(
        latitude: Double,
        longitude: Double,
        camera: MapCamera,
        widthPixels: Float,
        heightPixels: Float
    ): MapPoint {
        val z = camera.sourceZoom
        val tile = camera.tilePixels
        val dx = (TileMath.tileX(longitude, z) - TileMath.tileX(camera.longitude, z)) * tile
        val dy = (TileMath.tileY(latitude, z) - TileMath.tileY(camera.latitude, z)) * tile
        return MapPoint(widthPixels / 2f + dx.toFloat(), heightPixels / 2f + dy.toFloat())
    }

    /**
     * A "nice" scale bar no wider than [maxPixels]: the largest round distance that fits, and how
     * many pixels long to draw it. US units, because the whole weather stack is a US product, and
     * feet below a quarter mile so the bar stays meaningful when you zoom right in. Returns null
     * only when even the shortest step overflows the space given.
     */
    fun scaleBar(metersPerPixel: Double, maxPixels: Float): Pair<String, Float>? {
        if (metersPerPixel <= 0.0 || maxPixels <= 0f) return null
        for ((meters, label) in NICE_DISTANCES.reversed()) {
            val pixels = (meters / metersPerPixel).toFloat()
            if (pixels <= maxPixels) return label to pixels
        }
        return null
    }

    private const val METERS_PER_MILE = 1609.344
    private const val METERS_PER_FOOT = 0.3048

    /** Round distances a person actually thinks in, shortest first. */
    private val NICE_DISTANCES: List<Pair<Double, String>> = listOf(
        100 * METERS_PER_FOOT to "100 ft",
        250 * METERS_PER_FOOT to "250 ft",
        500 * METERS_PER_FOOT to "500 ft",
        1_000 * METERS_PER_FOOT to "1000 ft",
        2_000 * METERS_PER_FOOT to "2000 ft",
        METERS_PER_MILE to "1 mi",
        2 * METERS_PER_MILE to "2 mi",
        5 * METERS_PER_MILE to "5 mi",
        10 * METERS_PER_MILE to "10 mi",
        25 * METERS_PER_MILE to "25 mi",
        50 * METERS_PER_MILE to "50 mi",
        100 * METERS_PER_MILE to "100 mi",
        250 * METERS_PER_MILE to "250 mi",
        500 * METERS_PER_MILE to "500 mi"
    )
}
