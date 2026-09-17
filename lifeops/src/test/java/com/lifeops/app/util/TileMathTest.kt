package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test

class TileMathTest {

    @Test
    fun `world is a power-of-two grid`() {
        assertEquals(1, TileMath.tileCount(0))
        assertEquals(4, TileMath.tileCount(2))
        assertEquals(1024, TileMath.tileCount(10))
    }

    @Test
    fun `null island sits at the centre of the world`() {
        val z = 4
        assertEquals(TileMath.tileCount(z) / 2.0, TileMath.tileX(0.0, z), 1e-9)
        assertEquals(TileMath.tileCount(z) / 2.0, TileMath.tileY(0.0, z), 1e-9)
    }

    @Test
    fun `corners map to the grid edges`() {
        val z = 3
        // ±180° is one meridian, and the grid's seam: both land on the far edge, and a hair east
        // of it is the grid's origin.
        assertEquals(TileMath.tileCount(z).toDouble(), TileMath.tileX(180.0, z), 1e-9)
        assertEquals(TileMath.tileCount(z).toDouble(), TileMath.tileX(-180.0, z), 1e-9)
        assertEquals(0.0, TileMath.tileX(-179.999999, z), 1e-6)
        assertEquals(0.0, TileMath.tileY(TileMath.MAX_LATITUDE, z), 1e-6)
        assertEquals(TileMath.tileCount(z).toDouble(), TileMath.tileY(-TileMath.MAX_LATITUDE, z), 1e-6)
    }

    @Test
    fun `a known tile matches the published slippy-map example`() {
        // The OSM wiki's worked example: 41.85 N, 87.65 W at zoom 13 is tile 2101, 3045.
        assertEquals(2101, TileMath.tileX(-87.65, 13).toInt())
        assertEquals(3045, TileMath.tileY(41.85, 13).toInt())
    }

    @Test
    fun `projection round-trips through its inverse`() {
        val z = 11
        for (lat in listOf(-64.0, -12.5, 0.0, 35.4676, 47.6062, 71.2)) {
            for (lon in listOf(-179.0, -97.5164, -0.1276, 0.0, 139.6917, 179.0)) {
                val x = TileMath.tileX(lon, z)
                val y = TileMath.tileY(lat, z)
                assertEquals(lon, TileMath.longitudeAt(x, z), 1e-6)
                assertEquals(lat, TileMath.latitudeAt(y, z), 1e-6)
            }
        }
    }

    @Test
    fun `latitude clamps at the mercator cutoff and longitude wraps`() {
        assertEquals(TileMath.MAX_LATITUDE, TileMath.clampLatitude(89.9), 1e-9)
        assertEquals(-TileMath.MAX_LATITUDE, TileMath.clampLatitude(-90.0), 1e-9)
        assertEquals(-175.0, TileMath.normalizeLongitude(185.0), 1e-9)
        assertEquals(175.0, TileMath.normalizeLongitude(-185.0), 1e-9)
        assertEquals(180.0, TileMath.normalizeLongitude(180.0), 1e-9)
    }

    @Test
    fun `columns wrap around the globe but rows do not`() {
        assertEquals(7, TileMath.wrapColumn(-1, 3))
        assertEquals(0, TileMath.wrapColumn(8, 3))
        assertEquals(3, TileMath.wrapColumn(3, 3))
        assertTrue(TileMath.isRowInRange(0, 3))
        assertTrue(TileMath.isRowInRange(7, 3))
        assertFalse(TileMath.isRowInRange(-1, 3))
        assertFalse(TileMath.isRowInRange(8, 3))
    }

    @Test
    fun `tile bounds tile the world and flip y`() {
        // Zoom 1: four tiles, each a quarter of the Mercator square.
        val topLeft = TileMath.tileBoundsMeters(0, 0, 1)
        assertEquals(-TileMath.WORLD_EDGE_METERS, topLeft[0], 1e-6)   // minX at the west edge
        assertEquals(0.0, topLeft[1], 1e-6)                            // minY at the equator
        assertEquals(0.0, topLeft[2], 1e-6)                            // maxX at the prime meridian
        assertEquals(TileMath.WORLD_EDGE_METERS, topLeft[3], 1e-6)     // maxY at the north edge

        val bottomRight = TileMath.tileBoundsMeters(1, 1, 1)
        assertEquals(0.0, bottomRight[0], 1e-6)
        assertEquals(-TileMath.WORLD_EDGE_METERS, bottomRight[1], 1e-6)
        assertEquals(TileMath.WORLD_EDGE_METERS, bottomRight[2], 1e-6)
        assertEquals(0.0, bottomRight[3], 1e-6)
    }

    @Test
    fun `visible radius always covers the viewport plus a margin`() {
        assertEquals(3, TileMath.visibleRadius(1080f, 256f))
        assertEquals(1, TileMath.visibleRadius(256f, 256f))
        assertEquals(0, TileMath.visibleRadius(1080f, 0f))
    }

    @Test
    fun `metres per pixel halves each zoom step and shrinks away from the equator`() {
        val equatorZ5 = TileMath.metersPerPixel(0.0, 5)
        val equatorZ6 = TileMath.metersPerPixel(0.0, 6)
        assertEquals(equatorZ5 / 2, equatorZ6, 1e-6)
        assertTrue(TileMath.metersPerPixel(60.0, 5) < equatorZ5)
    }
}
