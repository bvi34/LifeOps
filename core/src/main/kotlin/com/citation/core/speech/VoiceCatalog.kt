package com.citation.core.speech

/**
 * The voices Citation knows how to fetch — a short, curated list rather than a directory.
 *
 * Every entry is a Piper voice published under an open licence by the Rhasspy project, with its size
 * and checksum taken from the publisher's own manifest. They are ordinary files on an ordinary HTTPS
 * host: nothing here is an account, an API key or a per-use cost, which is the property that makes
 * the on-device track worth the work in the first place. Download once, speak forever, offline.
 *
 * Curated on purpose. The full catalogue runs to hundreds of voices across dozens of languages, and
 * a reader who wants one good English narrator should not be made to shop. A handful covers the
 * common cases; [modelFor] resolves any id, so a voice fetched from outside this list still installs
 * and still plays.
 *
 * These are *starting points*, not a promise: a URL that has moved shows up as a download that
 * fails, which the voice picker reports and the reader retries — not as anything that breaks
 * reading. Speech is additive to a reader that has always worked in silence.
 */
object VoiceCatalog {

    private const val BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/main"

    /**
     * The default when a reader asks for a voice and expresses no preference: a mid-sized American
     * English narrator that is clear at 1.5×, which is where most people who listen end up.
     */
    const val DEFAULT_VOICE_ID = "en_US-lessac-medium"

    /** Every voice offered by name. */
    val VOICES: List<VoiceModel> = listOf(
        piper(
            id = "en_US-lessac-medium",
            name = "Lessac",
            language = "en-US",
            quality = VoiceQuality.MEDIUM,
            path = "en/en_US/lessac/medium",
            sizeBytes = 63_201_294,
            md5 = "2fc642b535197b6305c7c8f92dc8b24f",
            notes = "Even and unhurried; the safe first choice for long-form fiction."
        ),
        piper(
            id = "en_US-amy-medium",
            name = "Amy",
            language = "en-US",
            quality = VoiceQuality.MEDIUM,
            path = "en/en_US/amy/medium",
            sizeBytes = 63_201_294,
            md5 = "778d28aeb95fcdf8a882344d9df142fc"
        ),
        piper(
            id = "en_US-hfc_female-medium",
            name = "HFC Female",
            language = "en-US",
            quality = VoiceQuality.MEDIUM,
            path = "en/en_US/hfc_female/medium",
            sizeBytes = 63_201_294,
            md5 = "7abec91f1d6e19e913fbc4a333f62787"
        ),
        piper(
            id = "en_US-ryan-high",
            name = "Ryan",
            language = "en-US",
            quality = VoiceQuality.HIGH,
            path = "en/en_US/ryan/high",
            sizeBytes = 120_786_792,
            md5 = "5d879a17bddf5007f76655b445ba78b4",
            notes = "The best sounding here, and the slowest to synthesize — check it keeps up " +
                "with 1.5× on your phone before committing to a long book."
        ),
        piper(
            id = "en_GB-alba-medium",
            name = "Alba",
            language = "en-GB",
            quality = VoiceQuality.MEDIUM,
            path = "en/en_GB/alba/medium",
            sizeBytes = 63_201_294,
            md5 = "c07f313752bb3aba8061041666251654",
            notes = "Scottish English."
        ),
        piper(
            id = "en_GB-jenny_dioco-medium",
            name = "Jenny",
            language = "en-GB",
            quality = VoiceQuality.MEDIUM,
            path = "en/en_GB/jenny_dioco/medium",
            sizeBytes = 63_201_294,
            md5 = "d08f2f7edf0c858275a7eca74ff2a9e4"
        ),
        piper(
            id = "en_GB-northern_english_male-medium",
            name = "Northern English Male",
            language = "en-GB",
            quality = VoiceQuality.MEDIUM,
            path = "en/en_GB/northern_english_male/medium",
            sizeBytes = 63_201_294,
            md5 = "4c9a9735bfb76ad67c8b31b23d6840a0"
        )
    )

    /** The catalogue entry for [id], or `null` for a voice this build does not know by name. */
    fun modelFor(id: String): VoiceModel? = VOICES.firstOrNull { it.model(id) }

    /** The default voice, for the reader who opens the picker and wants to be told what to take. */
    fun default(): VoiceModel = modelFor(DEFAULT_VOICE_ID) ?: VOICES.first()

    /**
     * Voices worth offering for a book in [language] — an exact match first, then the same primary
     * language, then everything else, because a reader may well want an English narrator anyway.
     */
    fun suggestedFor(language: String?): List<VoiceModel> {
        if (language.isNullOrBlank()) return VOICES
        val tag = language.lowercase().replace('_', '-')
        val primary = tag.substringBefore('-')
        return VOICES.sortedWith(
            compareByDescending<VoiceModel> { it.language.lowercase() == tag }
                .thenByDescending { it.language.lowercase().substringBefore('-') == primary }
                .thenByDescending { it.quality.ordinal }
        )
    }

    private fun VoiceModel.model(id: String) = this.id == id

    private fun piper(
        id: String,
        name: String,
        language: String,
        quality: VoiceQuality,
        path: String,
        sizeBytes: Long,
        md5: String,
        sampleRate: Int = 22_050,
        notes: String? = null
    ) = VoiceModel(
        id = id,
        name = name,
        language = language,
        quality = quality,
        sampleRate = sampleRate,
        sizeBytes = sizeBytes,
        modelUrl = "$BASE/$path/$id.onnx",
        configUrl = "$BASE/$path/$id.onnx.json",
        md5 = md5,
        notes = notes
    )
}
