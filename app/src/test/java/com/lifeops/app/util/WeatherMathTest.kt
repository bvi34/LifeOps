package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test

class WeatherMathTest {

    @Test
    fun `heat index equals air temperature below 80F`() {
        assertEquals(75.0, WeatherMath.heatIndexF(75.0, 90.0), 0.0001)
    }

    @Test
    fun `heat index adds apparent heat in hot humid conditions`() {
        // 95F at 55% RH feels well above 95 per the NWS chart (~110F range).
        val hi = WeatherMath.heatIndexF(95.0, 55.0)
        assertTrue("expected hotter-than-air, got $hi", hi > 105.0)
        assertTrue("expected physically plausible, got $hi", hi < 120.0)
    }

    @Test
    fun `heat index low-humidity correction pulls the number down`() {
        // Hot but very dry: correction subtracts, so it must stay below the humid case.
        val dry = WeatherMath.heatIndexF(100.0, 10.0)
        val humid = WeatherMath.heatIndexF(100.0, 50.0)
        assertTrue(dry < humid)
    }

    @Test
    fun `wind chill equals air temperature when warm or calm`() {
        assertEquals(60.0, WeatherMath.windChillF(60.0, 20.0), 0.0001) // too warm
        assertEquals(30.0, WeatherMath.windChillF(30.0, 2.0), 0.0001)  // too calm
    }

    @Test
    fun `wind chill drops below air temperature in cold wind`() {
        // 20F with a 20 mph wind is meaningfully colder than 20F.
        val wc = WeatherMath.windChillF(20.0, 20.0)
        assertTrue("expected colder-than-air, got $wc", wc < 15.0)
    }

    @Test
    fun `feels like picks heat index in heat and wind chill in cold`() {
        assertTrue(WeatherMath.feelsLikeF(95.0, 55.0, 5.0) > 95.0)
        assertTrue(WeatherMath.feelsLikeF(20.0, 60.0, 20.0) < 20.0)
        // Mild middle: neither adjustment applies.
        assertEquals(65.0, WeatherMath.feelsLikeF(65.0, 50.0, 10.0), 0.0001)
    }

    @Test
    fun `feels like skips adjustment when the needed input is missing`() {
        // Hot but humidity unknown -> no heat index, falls back to air temp.
        assertEquals(95.0, WeatherMath.feelsLikeF(95.0, null, 5.0), 0.0001)
        // Cold but wind unknown -> no wind chill.
        assertEquals(20.0, WeatherMath.feelsLikeF(20.0, 60.0, null), 0.0001)
    }

    @Test
    fun `rounded feels like returns whole degrees`() {
        val f = WeatherMath.feelsLikeRounded(95, 55, 5)
        assertTrue(f > 95)
    }
}
