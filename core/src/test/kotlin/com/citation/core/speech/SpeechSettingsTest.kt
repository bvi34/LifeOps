package com.citation.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpeechSettingsTest {

    @Test
    fun `rate and pitch clamp to what engines honour`() {
        assertEquals(SpeechSettings.MAX_RATE, SpeechSettings().withRate(9f).rate, 0.001f)
        assertEquals(SpeechSettings.MIN_RATE, SpeechSettings().withRate(0.1f).rate, 0.001f)
        assertEquals(SpeechSettings.MAX_PITCH, SpeechSettings().withPitch(4f).pitch, 0.001f)
    }

    @Test
    fun `the speed label reads the way a speed control should`() {
        assertEquals("1×", SpeechSettings().rateLabel)
        assertEquals("1.5×", SpeechSettings().withRate(1.5f).rateLabel)
        assertEquals("2×", SpeechSettings().withRate(2f).rateLabel)
        assertEquals("0.75×", SpeechSettings().withRate(0.75f).rateLabel)
    }

    @Test
    fun `listening does not teach the reading-pace estimate by default`() {
        // ReadingPace answers "how fast do *you* read"; a voice at 1.5x would answer something else.
        assertFalse(SpeechSettings().bankListeningTowardPace)
    }
}
