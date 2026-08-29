package com.lifeops.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun `identical points are zero apart`() {
        assertEquals(0.0, Geo.distanceMeters(35.2226, -97.4395, 35.2226, -97.4395), 0.0001)
    }

    @Test
    fun `a tenth of a degree of latitude is about eleven kilometres`() {
        // A degree of latitude is ~111 km anywhere on Earth, so this is a source-free check.
        val meters = Geo.distanceMeters(35.0, -97.0, 35.1, -97.0)
        assertEquals(11_100.0, meters, 100.0)
    }

    @Test
    fun `known city pair matches the published great-circle distance`() {
        // Oklahoma City to Dallas, ~306 km as the crow flies.
        val meters = Geo.distanceMeters(35.4676, -97.5164, 32.7767, -96.7970)
        assertEquals(306_000.0, meters, 2_000.0)
    }

    @Test
    fun `distance is symmetric`() {
        val there = Geo.distanceMeters(40.7128, -74.0060, 34.0522, -118.2437)
        val back = Geo.distanceMeters(34.0522, -118.2437, 40.7128, -74.0060)
        assertEquals(there, back, 0.0001)
    }

    @Test
    fun `a walk across the street stays under the repository's move threshold`() {
        // ~200 m: the jitter a phone on a desk produces must never re-point the tracked location.
        val meters = Geo.distanceMeters(35.2226, -97.4395, 35.2244, -97.4395)
        assertTrue("expected a short hop, got $meters", meters < 500.0)
    }

    @Test
    fun `antipodal points do not blow up the arcsine`() {
        val meters = Geo.distanceMeters(0.0, 0.0, 0.0, 180.0)
        assertEquals(20_015_000.0, meters, 10_000.0)
    }
}
