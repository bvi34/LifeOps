package com.lifeops.app.util

import com.lifeops.app.data.weather.BaseMapLayer
import com.lifeops.app.data.weather.RadarProduct
import com.lifeops.app.data.weather.TileLayer
import org.junit.Assert.*
import org.junit.Test

class MapCameraTest {

    private val oklahomaCity = MapCamera(35.4676, -97.5164, 8f)

    @Test
    fun `whole zoom levels draw tiles at their native size`() {
        assertEquals(8, oklahomaCity.sourceZoom)
        assertEquals(256f, oklahomaCity.tilePixels, 1e-4f)
    }

    @Test
    fun `a fractional zoom scales the level below it`() {
        val halfway = oklahomaCity.zoomedTo(8.5f)
        assertEquals(8, halfway.sourceZoom)
        assertEquals(256f * 1.4142f, halfway.tilePixels, 0.1f)

        val nearlyNine = oklahomaCity.zoomedTo(8.99f)
        assertEquals(8, nearlyNine.sourceZoom)
        assertTrue(nearlyNine.tilePixels < 512f)
    }

    @Test
    fun `pinching by two is one whole zoom level`() {
        assertEquals(9f, oklahomaCity.zoomedBy(2f).zoom, 1e-4f)
        assertEquals(7f, oklahomaCity.zoomedBy(0.5f).zoom, 1e-4f)
    }

    @Test
    fun `zoom is clamped to the levels the map can serve`() {
        assertEquals(MapCamera.MAX_ZOOM.toFloat(), oklahomaCity.zoomedTo(40f).zoom, 1e-4f)
        assertEquals(MapCamera.MIN_ZOOM.toFloat(), oklahomaCity.zoomedTo(0f).zoom, 1e-4f)
        // A degenerate pinch factor must not produce NaN or throw.
        assertEquals(oklahomaCity.zoom, oklahomaCity.zoomedBy(0f).zoom, 1e-4f)
    }

    @Test
    fun `dragging right looks further west and dragging down looks further north`() {
        val west = oklahomaCity.panned(dxPixels = 100f, dyPixels = 0f)
        assertTrue(west.longitude < oklahomaCity.longitude)
        assertEquals(oklahomaCity.latitude, west.latitude, 1e-9)

        val north = oklahomaCity.panned(dxPixels = 0f, dyPixels = 100f)
        assertTrue(north.latitude > oklahomaCity.latitude)
    }

    @Test
    fun `panning back and forth returns to where it started`() {
        val there = oklahomaCity.panned(220f, -140f)
        val back = there.panned(-220f, 140f)
        assertEquals(oklahomaCity.latitude, back.latitude, 1e-6)
        assertEquals(oklahomaCity.longitude, back.longitude, 1e-6)
    }

    @Test
    fun `latitude cannot be dragged off the top of the world`() {
        val slammed = oklahomaCity.zoomedTo(3f).panned(0f, 100_000f)
        assertTrue(slammed.latitude <= TileMath.MAX_LATITUDE)
        assertFalse(slammed.latitude.isNaN())
    }

    @Test
    fun `the pin sits at the centre when the camera is on it`() {
        val point = MapLayout.screenPointOf(
            oklahomaCity.latitude, oklahomaCity.longitude, oklahomaCity, 1080f, 1920f
        )
        assertEquals(540f, point.x, 1e-3f)
        assertEquals(960f, point.y, 1e-3f)
    }

    @Test
    fun `a pin north-east of the camera draws up and to the right`() {
        val point = MapLayout.screenPointOf(
            oklahomaCity.latitude + 0.5, oklahomaCity.longitude + 0.5, oklahomaCity, 1080f, 1920f
        )
        assertTrue(point.x > 540f)
        assertTrue(point.y < 960f)
    }

    @Test
    fun `placements cover the viewport and centre on the camera`() {
        val tiles = MapLayout.placements(BaseMapLayer.STREETS, oklahomaCity, 1080f, 1920f)
        assertTrue(tiles.isNotEmpty())
        // Every visible pixel is covered: some tile starts at or before each edge, and some tile
        // ends at or after it.
        assertTrue(tiles.any { it.left <= 0f && it.left + it.size > 0f })
        assertTrue(tiles.any { it.left < 1080f && it.left + it.size >= 1080f })
        assertTrue(tiles.any { it.top <= 0f && it.top + it.size > 0f })
        assertTrue(tiles.any { it.top < 1920f && it.top + it.size >= 1920f })
        assertEquals(tiles.size, tiles.map { it.url }.distinct().size)
    }

    @Test
    fun `a zero-sized viewport asks for nothing`() {
        assertTrue(MapLayout.placements(BaseMapLayer.STREETS, oklahomaCity, 0f, 0f).isEmpty())
    }

    @Test
    fun `a layer is never asked past the zoom it publishes`() {
        val deep = oklahomaCity.zoomedTo(13f)
        val radar = RadarProduct.NEXRAD_MOSAIC.layer
        val tiles = MapLayout.placements(radar, deep, 1080f, 1920f)
        assertTrue(tiles.isNotEmpty())
        // Its deepest level is drawn larger instead of requesting a level that 404s.
        tiles.forEach { assertTrue("/${radar.maxZoom}/" in it.url) }
        assertTrue(tiles.first().size > TileMath.TILE_SIZE_PX)
    }

    @Test
    fun `rows off the top of the world are skipped rather than wrapped`() {
        val northPole = MapCamera(TileMath.MAX_LATITUDE, 0.0, 3f)
        val tiles = MapLayout.placements(BaseMapLayer.STREETS, northPole, 1080f, 1920f)
        tiles.forEach { assertFalse("/-1.png" in it.url) }
    }

    @Test
    fun `a WMS layer asks for a tile-shaped bounding box`() {
        val wms = RadarProduct.NWS_REFLECTIVITY.layer
        val url = wms.urlFor(0, 0, 1)!!
        assertTrue("request=GetMap" in url)
        assertTrue("crs=EPSG:3857" in url)
        assertTrue("width=256" in url && "height=256" in url)
        assertTrue("bbox=-20037508.342789,0.000000,0.000000,20037508.342789" in url)
    }

    @Test
    fun `an XYZ layer substitutes its coordinates and wraps the column`() {
        val layer = TileLayer.Xyz("Test", "none", 10, "https://example.test/{z}/{x}/{y}.png")
        assertEquals("https://example.test/3/7/2.png", layer.urlFor(-1, 2, 3))
        assertNull(layer.urlFor(0, 8, 3))
    }

    @Test
    fun `the scale bar picks the largest round distance that fits`() {
        // ~76 m/px is roughly zoom 11 in the mid-latitudes: 5 miles is ~106 px, 10 would be ~212.
        val (label, pixels) = MapLayout.scaleBar(metersPerPixel = 76.0, maxPixels = 160f)!!
        assertEquals("5 mi", label)
        assertTrue(pixels <= 160f)

        // Zoomed right in, the bar drops to feet rather than rounding to zero miles.
        val close = MapLayout.scaleBar(metersPerPixel = 0.5, maxPixels = 160f)!!
        assertEquals("250 ft", close.first)
        assertTrue(close.second <= 160f)

        // Zoomed all the way out, the bar tops out at the longest step it knows.
        assertEquals("500 mi", MapLayout.scaleBar(metersPerPixel = 20_000.0, maxPixels = 160f)!!.first)

        // And where even the shortest step would overflow the space, there is no bar at all.
        assertNull(MapLayout.scaleBar(metersPerPixel = 0.01, maxPixels = 160f))
        assertNull(MapLayout.scaleBar(metersPerPixel = 0.0, maxPixels = 160f))
    }
}
