package com.citation.core.speech

/**
 * The voices Citation knows how to fetch — a short, curated list rather than a directory.
 *
 * Every entry is a Piper voice published under an open licence, packaged for the on-device runtime
 * (weights plus the token table beside them). They are ordinary files on an ordinary HTTPS host:
 * nothing here is an account, an API key or a per-use cost, which is the property that makes the
 * on-device track worth the work in the first place. Download once, speak forever, offline.
 *
 * English only, and that is a real limit rather than an oversight: the pronunciation data that turns
 * text into phonemes ships with the app trimmed to English (see the reader's `EspeakData`), so a
 * voice in another language would need its dictionary added there before it could say a word.
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

    private const val BASE = "https://huggingface.co/csukuangfj"

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
            sizeBytes = 63_201_425,
            sha256 = "ea793a648ce0370665f99fbf891eae3b3a973565c72b08534bb5b2c04e8f2332",
            tokensSha256 = "87c8ef66eae5473ed0cc0366b3964c736ca6c5f676c979522ea31234e47430b9",
            notes = "Even and unhurried; the safe first choice for long-form fiction."
        ),
        piper(
            id = "en_US-amy-medium",
            name = "Amy",
            language = "en-US",
            quality = VoiceQuality.MEDIUM,
            sizeBytes = 63_201_425,
            sha256 = "fbaa8e36d8f26fe6f3ebb65cab461e629d8b37a5b7c5fb78fb64317db73e1c25",
            tokensSha256 = "87c8ef66eae5473ed0cc0366b3964c736ca6c5f676c979522ea31234e47430b9"
        ),
        piper(
            id = "en_US-hfc_female-medium",
            name = "HFC Female",
            language = "en-US",
            quality = VoiceQuality.MEDIUM,
            sizeBytes = 63_201_425,
            sha256 = "6d8b3711715f17f29b9f0ded97571924ead9a06e300bfdf3680b014a51ddc9e5",
            tokensSha256 = "620e1aecf1a68fea3ba5850d137b0138fa2037c9b372dad13b95a2a215d0849a"
        ),
        piper(
            id = "en_US-ryan-high",
            name = "Ryan",
            language = "en-US",
            quality = VoiceQuality.HIGH,
            sizeBytes = 120_786_923,
            sha256 = "4343c10fd88301574d2012b3d006fa5fc8fdf18d04ca9564ef399eed180d8788",
            tokensSha256 = "42d1a69ed2b91a51928a711aa228ed9f3dc021c6d359a3e9c4f37eb1d20f80bd",
            notes = "The best sounding here, and the slowest to synthesize — check it keeps up " +
                "with 1.5× on your phone before committing to a long book."
        ),
        piper(
            id = "en_GB-alba-medium",
            name = "Alba",
            language = "en-GB",
            quality = VoiceQuality.MEDIUM,
            sizeBytes = 63_201_430,
            sha256 = "c904d007a8047ab13628b021351b983d0a2627c0d7a81c64a6fe9ad661adb1cf",
            tokensSha256 = "87c8ef66eae5473ed0cc0366b3964c736ca6c5f676c979522ea31234e47430b9",
            notes = "Scottish English."
        ),
        piper(
            id = "en_GB-jenny_dioco-medium",
            name = "Jenny",
            language = "en-GB",
            quality = VoiceQuality.MEDIUM,
            sizeBytes = 63_201_430,
            sha256 = "bd5207a2752d8766a8b771437776fd7575031a9f9876eaf13b8db778b150993a",
            tokensSha256 = "87c8ef66eae5473ed0cc0366b3964c736ca6c5f676c979522ea31234e47430b9"
        ),
        piper(
            id = "en_GB-northern_english_male-medium",
            name = "Northern English Male",
            language = "en-GB",
            quality = VoiceQuality.MEDIUM,
            sizeBytes = 63_201_430,
            sha256 = "d23e7891af7062eb188283dba94866e25ffd5b01a0d9fb9a23c71a39b75b2308",
            tokensSha256 = "2619c1a9de1bcf928162f40c583caf39368cfd6b2340c7bcad51dc634411ec36"
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

    /**
     * One catalogue entry. The two files sit at the root of the voice's own repository, so the URLs
     * are derived from the id rather than restated — a wrong path becomes a download that fails
     * rather than a voice that half-installs.
     */
    private fun piper(
        id: String,
        name: String,
        language: String,
        quality: VoiceQuality,
        sizeBytes: Long,
        sha256: String,
        tokensSha256: String,
        sampleRate: Int = 22_050,
        notes: String? = null
    ) = VoiceModel(
        id = id,
        name = name,
        language = language,
        quality = quality,
        sampleRate = sampleRate,
        sizeBytes = sizeBytes,
        modelUrl = "$BASE/vits-piper-$id/resolve/main/$id.onnx",
        tokensUrl = "$BASE/vits-piper-$id/resolve/main/tokens.txt",
        sha256 = sha256,
        tokensSha256 = tokensSha256,
        notes = notes
    )
}
