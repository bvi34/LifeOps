package com.citation.core.speech

/**
 * How the book is spoken — the voice half of [com.citation.core.reader.ReaderSettings], and kept
 * separate from it for the same reason the reader's own settings were gathered into one value:
 * persisted in one place, testable without a device, and given to a book on its own when the reader
 * wants this one narrated differently.
 */
data class SpeechSettings(
    /**
     * The installed voice to speak with, or `null` to let the narrator choose — the best installed
     * neural voice, and the platform engine when none is installed. See [VoiceModel].
     */
    val voiceId: String? = null,

    /**
     * Which engine to prefer.
     *
     * The default asks for the good voice and accepts the ordinary one: a reader who has installed
     * nothing still gets working speech from the platform engine, and the day they install a neural
     * voice it is simply used. Nobody has to know an engine exists to be able to listen.
     */
    val engine: EnginePreference = EnginePreference.NEURAL_ELSE_SYSTEM,

    /** Speaking rate, `1f` being the voice's own pace. */
    val rate: Float = 1f,

    /** Pitch, `1f` being the voice's own. Honoured by the platform engine; ignored by most neural ones. */
    val pitch: Float = 1f,

    /** Roll on into the next chapter rather than stopping at the end of this one. */
    val autoAdvanceChapter: Boolean = true,

    /**
     * Say the chapter's title when one starts.
     *
     * On, because a listener has no page to glance at: without it, chapters run together into an
     * undifferentiated hour. Suppressed automatically when the chapter's own first heading already
     * says the same thing, which most EPUBs' do.
     */
    val announceChapterTitle: Boolean = true,

    /**
     * Count time spent listening toward the reading-pace estimate.
     *
     * **Off, and it should stay off.** [com.citation.core.reader.ReadingPace] exists to answer "how
     * long will this take *you* to read", measured from how fast this reader actually reads. Time
     * spent listening measures the speaking rate of a voice instead — a listener at 1.5× would drag
     * every "12 min left" in the app toward a number that has nothing to do with their reading. The
     * *position* still advances, and is still saved; only the pace measurement declines to learn
     * from it.
     */
    val bankListeningTowardPace: Boolean = false,

    /** The sleep timer to arm when playback starts. */
    val sleepMode: SleepMode = SleepMode.OFF,

    /** What gets spoken and how long the rests are. */
    val content: SpeechOptions = SpeechOptions()
) {

    /** [rate], clamped to what engines actually honour. */
    fun withRate(value: Float) = copy(rate = value.coerceIn(MIN_RATE, MAX_RATE))

    /** [pitch], clamped likewise. */
    fun withPitch(value: Float) = copy(pitch = value.coerceIn(MIN_PITCH, MAX_PITCH))

    /** "1.5×" / "1×" — the label a speed control shows. */
    val rateLabel: String
        get() {
            val rounded = Math.round(rate * 100f) / 100f
            val trimmed = if (rounded == rounded.toInt().toFloat()) {
                rounded.toInt().toString()
            } else {
                rounded.toString().trimEnd('0').trimEnd('.')
            }
            return "$trimmed×"
        }

    companion object {
        /** Below half speed, every engine slurs; above three times, nothing is intelligible. */
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 3f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2f

        /** The speeds a control offers by default. */
        val RATE_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    }
}

/** Which engine the narrator asks for first. */
enum class EnginePreference {
    /** A downloaded neural voice when one is installed and loadable, otherwise the platform's. */
    NEURAL_ELSE_SYSTEM,

    /** The neural engine or nothing — for a reader who would rather be told than be read to badly. */
    NEURAL_ONLY,

    /**
     * The platform's own text-to-speech, always.
     *
     * A real preference, not a fallback: it costs no storage, it is the engine a reader has already
     * tuned in system settings, and it is the one that reports word boundaries, so it is the only
     * way to get a word-by-word highlight rather than a sentence one.
     */
    SYSTEM
}
