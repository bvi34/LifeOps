package com.citation.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A voice the reader typed in: what is allowed to become a filename, a URL, and a name. */
class CustomVoiceTest {

    private val taken = VoiceCatalog.VOICES.map { it.id }.toSet()

    private fun link(model: String, tokens: String = "") = VoiceSource.Link(model, tokens)

    private fun define(
        name: String = "Ada",
        language: String = "en-US",
        source: VoiceSource = link("https://example.org/voices/ada/ada.onnx"),
        speaker: Int = 0,
        sampleRate: Int = CustomVoice.DEFAULT_SAMPLE_RATE,
        taken: Set<String> = this.taken
    ) = CustomVoice.define(
        VoiceDraft(name = name, language = language, source = source, speaker = speaker, sampleRate = sampleRate),
        taken
    )

    private fun defined(outcome: CustomVoice.Outcome): VoiceModel =
        (outcome as? CustomVoice.Outcome.Defined)?.model
            ?: throw AssertionError("expected a voice, got $outcome")

    private fun rejection(outcome: CustomVoice.Outcome): String =
        (outcome as? CustomVoice.Outcome.Rejected)?.reason
            ?: throw AssertionError("expected a rejection, got $outcome")

    @Test
    fun `a link and a name are the whole of it`() {
        val voice = defined(define())
        assertEquals("ada", voice.id)
        assertEquals("Ada", voice.name)
        assertEquals(VoiceOrigin.USER, voice.origin)
        assertEquals("https://example.org/voices/ada/ada.onnx", voice.modelUrl)
        // Filled in from where the model lives, so the second field is one nobody has to type.
        assertEquals("https://example.org/voices/ada/tokens.txt", voice.tokensUrl)
        // No publisher, so no publisher's checksum to claim. The transport is the integrity.
        assertNull(voice.sha256)
        assertNull(voice.tokensSha256)
    }

    @Test
    fun `the id is a filename, derived rather than asked for`() {
        assertEquals("northern-english-male", defined(define(name = "Northern English Male")).id)
        assertEquals("ada-s-voice-2", defined(define(name = "  Ada's Voice 2!  ")).id)
        // A name with nothing sluggable in it is still a name, and still gets a file to live in.
        assertEquals("voice", defined(define(name = "日本語")).id)
    }

    @Test
    fun `a second Alba is a second voice, not an overwrite`() {
        val first = defined(define(name = "Alba"))
        val second = defined(define(name = "Alba", taken = taken + first.id))
        val third = defined(define(name = "Alba", taken = taken + first.id + second.id))
        assertEquals("alba", first.id)
        assertEquals("alba-2", second.id)
        assertEquals("alba-3", third.id)
    }

    @Test
    fun `an id can never climb out of the voice store`() {
        assertFalse(CustomVoice.isSafeId("../../etc/passwd"))
        assertFalse(CustomVoice.isSafeId("a/b"))
        assertFalse(CustomVoice.isSafeId("a\\b"))
        assertFalse(CustomVoice.isSafeId(".hidden"))
        assertFalse(CustomVoice.isSafeId("a..b"))
        assertFalse(CustomVoice.isSafeId(""))
        assertFalse(CustomVoice.isSafeId("a".repeat(CustomVoice.MAX_ID_LENGTH + 1)))
        assertTrue(CustomVoice.isSafeId("en_US-lessac-medium"))
        assertTrue(CustomVoice.isSafeId("ada-2"))
    }

    @Test
    fun `every id a name can produce is safe to open a file with`() {
        listOf("../..", "/etc/passwd", "a b", "....", "?", "..\\..\\x", "  ", "Ada & Bob")
            .forEach { name ->
                val id = CustomVoice.idFor(name, emptySet())
                assertTrue("unsafe id from '$name': $id", CustomVoice.isSafeId(id))
            }
    }

    @Test
    fun `a de-duplicating suffix cannot push an id past its limit`() {
        val long = "x".repeat(200)
        val id = CustomVoice.idFor(long, setOf(CustomVoice.idFor(long, emptySet())))
        assertTrue(id.length <= CustomVoice.MAX_ID_LENGTH)
        assertTrue(CustomVoice.isSafeId(id))
    }

    @Test
    fun `plain http is refused, because the file is fed to a native runtime`() {
        assertTrue(rejection(define(source = link("http://example.org/v/ada.onnx"))).contains("https://"))
        assertTrue(
            rejection(define(source = link("https://example.org/v/ada.onnx", "http://example.org/v/tokens.txt")))
                .contains("https://")
        )
        // The scheme is a scheme, not a prefix of a hostname.
        assertTrue(rejection(define(source = link("https:/example.org/ada.onnx"))).isNotEmpty())
    }

    @Test
    fun `a token table is looked for beside the model, and asked for when there is nowhere to look`() {
        assertEquals(
            "https://example.org/v/tokens.txt",
            CustomVoice.siblingTokensUrl("https://example.org/v/ada.onnx")
        )
        // A link pasted from a browser carries the download query; it is a query, not a directory.
        assertEquals(
            "https://example.org/v/tokens.txt",
            CustomVoice.siblingTokensUrl("https://example.org/v/ada.onnx?download=true#top")
        )
        assertNull(CustomVoice.siblingTokensUrl("https://example.org/"))
        assertNull(CustomVoice.siblingTokensUrl("https://example.org"))
        assertTrue(rejection(define(source = link("https://example.org/"))).contains("tokens.txt"))
    }

    @Test
    fun `a stated token link is used as given`() {
        val voice = defined(
            define(source = link("https://a.example/model.onnx", "https://b.example/other/tokens.txt"))
        )
        assertEquals("https://b.example/other/tokens.txt", voice.tokensUrl)
    }

    @Test
    fun `files on the device need no links at all`() {
        val voice = defined(define(source = VoiceSource.Device))
        assertEquals("", voice.modelUrl)
        assertEquals("", voice.tokensUrl)
        assertEquals(VoiceOrigin.USER, voice.origin)
    }

    @Test
    fun `a name, a language and a speaker are each held to something`() {
        assertTrue(rejection(define(name = "   ")).contains("name"))
        assertTrue(rejection(define(name = "n".repeat(CustomVoice.MAX_NAME_LENGTH + 1))).contains("longer"))
        assertTrue(rejection(define(language = "English")).contains("en-US"))
        assertTrue(rejection(define(language = "")).contains("en-US"))
        assertTrue(rejection(define(speaker = -1)).contains("negative"))
        assertTrue(rejection(define(sampleRate = 100)).contains("100"))
        assertEquals("de-DE", defined(define(language = "de-DE")).language)
        assertEquals("pt-BR", defined(define(language = "pt-BR")).language)
    }

    @Test
    fun `a speaker number says the model has more than one voice in it`() {
        val voice = defined(define(speaker = 7))
        assertEquals(7, voice.speaker)
        assertTrue(voice.speakers > voice.speaker)
        assertEquals(1, defined(define()).speakers)
    }

    @Test
    fun `the two files picked the wrong way round is caught by their sizes`() {
        assertNotNull(CustomVoice.checkFiles(modelBytes = 1_024, tokensBytes = 63_000_000))
        assertNotNull(CustomVoice.checkFiles(modelBytes = 63_000_000, tokensBytes = 63_000_000))
        assertNull(CustomVoice.checkFiles(modelBytes = 63_201_425, tokensBytes = 1_024))
        // A provider that reports no size is not evidence of anything and never blocks an import.
        assertNull(CustomVoice.checkFiles(modelBytes = null, tokensBytes = null))
    }

    @Test
    fun `an added voice is unknown in size until its file lands`() {
        val voice = defined(define())
        assertEquals(0L, voice.sizeBytes)
        // ...and says so, rather than claiming to be nought megabytes.
        assertEquals("en-US · Medium", voice.summary)
        assertEquals("en-US · Medium · 61 MB", voice.copy(sizeBytes = 63_201_425).summary)
    }
}
