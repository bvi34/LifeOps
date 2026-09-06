package com.citation.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Choosing a voice: the question asked from three places, answered in one. */
class VoiceTest {

    private fun installed(id: String, quality: VoiceQuality, language: String = "en-US") =
        InstalledVoice(
            model = VoiceCatalog.modelFor(id) ?: VoiceModel(
                id = id, name = id, language = language, quality = quality,
                sampleRate = 22_050, sizeBytes = 1_000, modelUrl = "", configUrl = ""
            ).copy(quality = quality),
            modelPath = "/voices/$id.onnx",
            configPath = "/voices/$id.onnx.json"
        )

    @Test
    fun `nothing installed means no neural voice`() {
        assertNull(VoiceSelection.resolve(emptyList(), SpeechSettings()))
    }

    @Test
    fun `the reader's chosen voice wins`() {
        val voices = listOf(installed("en_US-amy-medium", VoiceQuality.MEDIUM), installed("en_US-ryan-high", VoiceQuality.HIGH))
        val chosen = VoiceSelection.resolve(voices, SpeechSettings(voiceId = "en_US-amy-medium"))
        assertEquals("en_US-amy-medium", chosen?.model?.id)
    }

    @Test
    fun `a deleted voice falls back to the best remaining one instead of silence`() {
        val voices = listOf(installed("en_US-amy-medium", VoiceQuality.MEDIUM), installed("en_US-ryan-high", VoiceQuality.HIGH))
        val chosen = VoiceSelection.resolve(voices, SpeechSettings(voiceId = "en_GB-alba-medium"))
        assertEquals("en_US-ryan-high", chosen?.model?.id)
    }

    @Test
    fun `asking for the system engine skips the neural voices entirely`() {
        val voices = listOf(installed("en_US-ryan-high", VoiceQuality.HIGH))
        assertNull(VoiceSelection.resolve(voices, SpeechSettings(engine = EnginePreference.SYSTEM)))
    }

    @Test
    fun `the best installed voice is the highest quality one, stably`() {
        val voices = listOf(
            installed("en_US-amy-medium", VoiceQuality.MEDIUM),
            installed("en_US-lessac-medium", VoiceQuality.MEDIUM),
            installed("en_US-ryan-high", VoiceQuality.HIGH)
        )
        assertEquals("en_US-ryan-high", VoiceSelection.best(voices)?.model?.id)
        assertEquals(VoiceSelection.best(voices), VoiceSelection.best(voices.reversed()))
    }

    @Test
    fun `a book's language sorts its voices to the top without hiding the others`() {
        val voices = listOf(
            installed("en_US-amy-medium", VoiceQuality.MEDIUM),
            installed("en_GB-alba-medium", VoiceQuality.MEDIUM, language = "en-GB")
        )
        val sorted = VoiceSelection.forLanguage(voices, "en-GB")
        assertEquals("en_GB-alba-medium", sorted.first().model.id)
        assertEquals(2, sorted.size)
    }

    @Test
    fun `catalogue entries are complete, unique and consistently addressed`() {
        val ids = VoiceCatalog.VOICES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        VoiceCatalog.VOICES.forEach { voice ->
            assertTrue(voice.modelUrl.startsWith("https://"))
            assertTrue(voice.modelUrl.endsWith("/${voice.id}.onnx"))
            assertTrue(voice.configUrl.endsWith("/${voice.id}.onnx.json"))
            assertEquals("${voice.id}.onnx", voice.modelFileName)
            assertEquals("${voice.id}.onnx.json", voice.configFileName)
            assertNotNull(voice.md5)
            assertTrue(voice.sizeBytes > 0)
            assertFalse(voice.name.isBlank())
        }
    }

    @Test
    fun `the default voice is in the catalogue`() {
        assertEquals(VoiceCatalog.DEFAULT_VOICE_ID, VoiceCatalog.default().id)
        assertNotNull(VoiceCatalog.modelFor(VoiceCatalog.DEFAULT_VOICE_ID))
        assertNull(VoiceCatalog.modelFor("nonesuch"))
    }

    @Test
    fun `suggestions lead with the book's own language`() {
        assertEquals("en-GB", VoiceCatalog.suggestedFor("en-GB").first().language)
        assertEquals(VoiceCatalog.VOICES, VoiceCatalog.suggestedFor(null))
    }

    @Test
    fun `sizes read as whole megabytes, rounded up`() {
        assertEquals("61 MB", VoiceModel.megabytes(63_201_294))
        assertEquals("1 MB", VoiceModel.megabytes(1))
    }
}
