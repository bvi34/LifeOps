package com.lifeops.app.util

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator ("slippy map") tile math — the pure half of the radar map, so the question
 * *"which tile holds this latitude, and where on screen does its pin land?"* stays a JVM-testable
 * one rather than an Android one. Nothing here fetches anything or knows what a tile looks like.
 *
 * The scheme is the one every raster tile server speaks (OSM, NOAA's GeoServer, NEXRAD mosaics):
 * at zoom *z* the world is a 2^z × 2^z grid of 256-pixel tiles, x increasing east from −180°,
 * y increasing *south* from the top. Tile coordinates are kept as `Double`s throughout so a
 * position inside a tile (which is what a map centre and a dropped pin both are) is expressible
 * without a second set of "pixel offset" fields.
 *
 * Latitude is clamped to [MAX_LATITUDE]: Mercator sends the poles to infinity, and no radar
 * product covers them anyway.
 */
object TileMath {

    /** Standard raster tile edge, in pixels, before any display scaling. */
    const val TILE_SIZE_PX = 256

    /** The Mercator cutoff (≈85.0511°) — the latitude where the projection's square world ends. */
    const val MAX_LATITUDE = 85.05112878

    /** Half the equatorial circumference in Web Mercator metres — the EPSG:3857 world edge. */
    const val WORLD_EDGE_METERS = 20_037_508.342789244

    /** Number of tiles along one edge of the world at [zoom]. */
    fun tileCount(zoom: Int): Int = 1 shl zoom.coerceIn(0, 22)

    /** Fractional tile x for [longitude]; 0 at the antimeridian, [tileCount] at the far side. */
    fun tileX(longitude: Double, zoom: Int): Double =
        (normalizeLongitude(longitude) + 180.0) / 360.0 * tileCount(zoom)

    /** Fractional tile y for [latitude]; 0 at the north edge, increasing southward. */
    fun tileY(latitude: Double, zoom: Int): Double {
        val lat = Math.toRadians(clampLatitude(latitude))
        val merc = ln(tan(lat) + 1.0 / cos(lat))
        return (1.0 - merc / PI) / 2.0 * tileCount(zoom)
    }

    /** Inverse of [tileX]: the longitude at fractional tile position [x]. */
    fun longitudeAt(x: Double, zoom: Int): Double =
        normalizeLongitude(x / tileCount(zoom) * 360.0 - 180.0)

    /** Inverse of [tileY]: the latitude at fractional tile position [y]. */
    fun latitudeAt(y: Double, zoom: Int): Double {
        val n = PI * (1.0 - 2.0 * y / tileCount(zoom))
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Clamp to the Mercator-representable band. */
    fun clampLatitude(latitude: Double): Double = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)

    /** Wrap a longitude into (−180, 180]; a pan east past the antimeridian comes out the west side. */
    fun normalizeLongitude(longitude: Double): Double {
        var lon = longitude
        while (lon > 180.0) lon -= 360.0
        while (lon <= -180.0) lon += 360.0
        return lon
    }

    /**
     * Wrap a tile column into the valid range for [zoom]. Columns wrap around the globe (x −1 is
     * the last column), which is what keeps a pan across the antimeridian from tearing a hole in
     * the map; rows do not wrap — see [isRowInRange].
     */
    fun wrapColumn(x: Int, zoom: Int): Int {
        val count = tileCount(zoom)
        return ((x % count) + count) % count
    }

    /** True when tile row [y] exists at [zoom]. Rows above the north edge or below the south
     *  simply have no tile, so the map draws empty sky there rather than repeating itself. */
    fun isRowInRange(y: Int, zoom: Int): Boolean = y in 0 until tileCount(zoom)

    /**
     * The EPSG:3857 bounding box of tile ([x], [y]) at [zoom], as `minX, minY, maxX, maxY` metres —
     * the form a WMS `GetMap` request wants. Note the y flip: tile rows count south from the top
     * while Mercator northings count north from the equator, so the tile's *top* edge is the
     * bounding box's *max* y.
     */
    fun tileBoundsMeters(x: Int, y: Int, zoom: Int): DoubleArray {
        val span = 2 * WORLD_EDGE_METERS / tileCount(zoom)
        val minX = -WORLD_EDGE_METERS + x * span
        val maxY = WORLD_EDGE_METERS - y * span
        return doubleArrayOf(minX, maxY - span, minX + span, maxY)
    }

    /**
     * How many tiles of [tilePixels] are needed to cover [dimensionPixels] and still fill the
     * screen once the centre tile is only partly visible — the half-open radius used to build the
     * visible grid around a centre tile.
     */
    fun visibleRadius(dimensionPixels: Float, tilePixels: Float): Int {
        if (tilePixels <= 0f) return 0
        return floor(dimensionPixels / 2f / tilePixels).toInt() + 1
    }

    /**
     * Metres per screen pixel at [latitude] and [zoom] with tiles drawn [tilePixels] wide — the
     * number a scale bar is built from. Mercator stretches east–west distances away from the
     * equator, hence the cosine.
     */
    fun metersPerPixel(latitude: Double, zoom: Int, tilePixels: Float = TILE_SIZE_PX.toFloat()): Double {
        val worldPixels = tileCount(zoom).toDouble() * tilePixels
        return 2 * WORLD_EDGE_METERS * cos(Math.toRadians(clampLatitude(latitude))) / worldPixels
    }
}
